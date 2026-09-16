import copy
import importlib.util
import os
import pathlib
import unittest
import uuid

spec = importlib.util.spec_from_file_location("repair", pathlib.Path(__file__).with_name("backfill_hdpay_reserve.py"))
repair = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repair)


def fixture():
    return {
        "orders": [{"order": "VQR-1", "provider": "P-1", "provider_status": 3, "vnd": "200000", "usdt": "10.000000", "wallet_biz": "VQR-1", "settled_at": "2026-09-16 01:00:00"}],
        "intents": [{"order": "VQR-1", "user": 41, "rail": "HDPAY", "status": "CREDITED", "target": "WALLET_TOPUP", "requested": "10", "credited": "10", "payable": "200000", "received": "200000", "deleted": 0}],
        "ledgers": [{"biz": "VQR-1", "user": 41, "type": "VIETQR_DEPOSIT", "asset": "USDT", "direction": "IN", "amount": "10", "status": "SUCCESS", "deleted": 0}],
        "wallets": [{"user": 41, "available": "7", "pending": "3", "cumulative": "10", "version": 2}],
        "withdrawals": [{"order": "WD-1", "user": 41, "amount": "3", "status": "REVIEW_PENDING", "hold": None}],
        "reserves": [], "held_reconciliations": [], "open_reviews": [],
    }


class PlanTest(unittest.TestCase):
    def test_missing_and_matching_replay_use_same_source_digest(self):
        state = fixture()
        first = repair.make_plan(state)
        self.assertEqual(first["missing_total"], "10.000000")
        state["reserves"] = [{"reserve": "RSV-TOPUP-VQR-1", "voucher": "VQR-1", "direction": "IN", "amount": "10", "status": "CONFIRMED", "deleted": 0}]
        replay = repair.make_plan(state)
        self.assertEqual(replay["missing"], [])
        self.assertEqual(first["source_sha256"], replay["source_sha256"])

    def test_conflicting_or_unmapped_reserve_is_rejected(self):
        for changes in ({"amount": "11"}, {"direction": "OUT"}, {"deleted": 1}, {"status": "PENDING"}, {"reserve": "OTHER"}, {"voucher": "MANUAL-INJECTION"}):
            with self.subTest(changes=changes):
                state = fixture()
                state["reserves"] = [{"reserve": "RSV-TOPUP-VQR-1", "voucher": "VQR-1", "direction": "IN", "amount": "10", "status": "CONFIRMED", "deleted": 0} | changes]
                with self.assertRaises(ValueError):
                    repair.make_plan(state)

    def test_inconsistent_receipt_or_wallet_is_rejected(self):
        for table, change in (("orders", {"provider_status": 4}), ("orders", {"wallet_biz": "OTHER"}), ("orders", {"usdt": "0"}), ("intents", {"rail": "MANUAL"}), ("intents", {"deleted": 1}), ("intents", {"received": "199999"}), ("intents", {"target": "COMMERCE_ORDER"}), ("ledgers", {"amount": "11"}), ("ledgers", {"user": 42}), ("ledgers", {"status": "PENDING"})):
            with self.subTest(table=table, change=change):
                state = fixture()
                state[table][0].update(change)
                with self.assertRaises(ValueError):
                    repair.make_plan(state)

    def test_existing_vietqr_recognition_review_or_dispatch_blocks(self):
        for table in ("held_reconciliations", "open_reviews"):
            state = fixture()
            state[table] = [{"ref": "EXISTING"}]
            with self.assertRaises(ValueError):
                repair.make_plan(state)
        for status in ("REVIEW_PASSED", "PROCESSING", "EXTENDED_HOLD", "SUBMITTED"):
            state = fixture()
            state["withdrawals"][0]["status"] = status
            with self.assertRaises(ValueError):
                repair.make_plan(state)

    def test_missing_or_duplicated_source_fact_blocks(self):
        for table in ("intents", "ledgers"):
            for rows in ([], fixture()[table] * 2):
                state = fixture()
                state[table] = rows
                with self.assertRaises(ValueError):
                    repair.make_plan(state)

    def test_user_action_invalidates_approved_source_digest(self):
        state = fixture()
        old = repair.make_plan(state)["source_sha256"]
        state["wallets"][0]["available"] = "6"
        self.assertNotEqual(old, repair.make_plan(state)["source_sha256"])


@unittest.skipUnless(os.environ.get("NEXION_RESERVE_REPAIR_IT") == "true", "isolated MySQL opt-in required")
class MySqlTest(unittest.TestCase):
    def connect(self, schema):
        return repair.Mysql([os.environ["NEXION_ISOLATED_MYSQL_CLIENT"], "--no-defaults", "--host=127.0.0.1", "--port=13306", "--user=root", "--batch", "--raw", "--skip-column-names", "--unbuffered", schema])

    def setUp(self):
        self.schema = "nexion_reserve_repair_it_" + uuid.uuid4().hex
        self.admin = self.connect("mysql")
        self.assertEqual(self.admin.execute("SELECT @@port"), ["13306"])
        self.admin.execute("CREATE DATABASE " + self.schema)
        self.db = self.connect(self.schema)
        self.db.execute("""
CREATE TABLE nx_hdpay_payin_order(merchant_order_id VARCHAR(64) PRIMARY KEY,provider_order_id VARCHAR(64),provider_status INT,amount_vnd DECIMAL(20,2),settled_usdt DECIMAL(18,6),wallet_ledger_biz_no VARCHAR(96),settled_at DATETIME,settlement_status VARCHAR(24));
CREATE TABLE nx_vietqr_intent(intent_no VARCHAR(64) PRIMARY KEY,user_id BIGINT,payment_rail VARCHAR(24),status VARCHAR(24),settlement_target_type VARCHAR(24),requested_usdt DECIMAL(18,6),credited_usdt DECIMAL(18,6),payable_vnd DECIMAL(20,2),received_vnd DECIMAL(20,2),is_deleted INT);
CREATE TABLE nx_user_wallet(user_id BIGINT PRIMARY KEY,usdt_available DECIMAL(18,6),pending_withdraw DECIMAL(18,6),cumulative_deposit_usdt DECIMAL(18,6),version BIGINT,is_deleted INT);
CREATE TABLE nx_wallet_ledger(id BIGINT PRIMARY KEY,biz_no VARCHAR(96),user_id BIGINT,biz_type VARCHAR(32),asset VARCHAR(16),direction VARCHAR(16),amount DECIMAL(18,6),status VARCHAR(24),is_deleted INT);
CREATE TABLE nx_withdrawal_order(withdrawal_no VARCHAR(96) PRIMARY KEY,user_id BIGINT,amount DECIMAL(18,6),status VARCHAR(24),d2_hold_until DATETIME,is_deleted INT);
CREATE TABLE nx_vietqr_reconciliation(reconciliation_no VARCHAR(96) PRIMARY KEY,intent_no VARCHAR(64),received_vnd DECIMAL(20,2),status VARCHAR(24),is_deleted INT);
CREATE TABLE nx_hdpay_settlement_review(review_no VARCHAR(64),merchant_order_id VARCHAR(64),status VARCHAR(24));
INSERT INTO nx_hdpay_payin_order VALUES('VQR-1','P-1',3,200000,10,'VQR-1','2026-09-16 01:00:00','CREDITED');
INSERT INTO nx_vietqr_intent VALUES('VQR-1',41,'HDPAY','CREDITED','WALLET_TOPUP',10,10,200000,200000,0);
INSERT INTO nx_user_wallet VALUES(41,7,3,10,2,0);
INSERT INTO nx_wallet_ledger VALUES(1,'VQR-1',41,'VIETQR_DEPOSIT','USDT','IN',10,'SUCCESS',0);
INSERT INTO nx_withdrawal_order VALUES('WD-1',41,3,'REVIEW_PENDING',NULL,0)
""")
        # Use the actual reserve and audit DDL, not an approximation of constraints.
        schema = pathlib.Path(__file__).resolve().parents[1] / "schema.sql"
        import re
        for name in ("nx_treasury_reserve_ledger", "nx_audit_log"):
            ddl = re.search(r"CREATE TABLE IF NOT EXISTS " + name + r" \(.*?;", schema.read_text(encoding="utf-8"), re.S)
            self.db.execute(ddl.group())

    def tearDown(self):
        self.db.close()
        self.admin.execute("DROP DATABASE " + self.schema)
        self.admin.close()

    def test_plan_write_replay_and_wallet_invariance(self):
        self.db.execute("START TRANSACTION READ ONLY")
        initial = repair.snapshot(self.db)
        plan = repair.make_plan(initial)
        self.db.execute("ROLLBACK")
        self.db.execute("START TRANSACTION")
        self.assertEqual(repair.make_plan(repair.snapshot(self.db, True))["source_sha256"], plan["source_sha256"])
        repair.apply_plan(self.db, plan)
        after = repair.make_plan(repair.snapshot(self.db, True))
        self.assertFalse(after["missing"])
        self.assertEqual(after["source_sha256"], plan["source_sha256"])
        self.db.execute("COMMIT")
        self.db.execute("START TRANSACTION")
        repair.apply_plan(self.db, repair.make_plan(repair.snapshot(self.db, True)))
        self.db.execute("COMMIT")
        self.assertEqual(self.db.execute("SELECT COUNT(*),SUM(amount_usd) FROM nx_treasury_reserve_ledger"), ["1\t10.000000"])
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM nx_audit_log WHERE action='D1_HDPAY_RESERVE_BACKFILLED'"), ["1"])

    def test_audit_failure_rolls_back_inserted_reserve(self):
        self.db.execute("ALTER TABLE nx_audit_log ADD CONSTRAINT forced_failure CHECK(action <> 'D1_HDPAY_RESERVE_BACKFILLED')")
        self.db.execute("START TRANSACTION")
        plan = repair.make_plan(repair.snapshot(self.db, True))
        with self.assertRaises(RuntimeError):
            repair.apply_plan(self.db, plan)
        self.db.close()
        self.db = self.connect(self.schema)
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM nx_treasury_reserve_ledger"), ["0"])
        self.assertEqual(repair.make_plan(repair.snapshot(self.db))["source_sha256"], plan["source_sha256"])


if __name__ == "__main__":
    unittest.main()

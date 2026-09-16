import copy
import os
import pathlib
import re
import unittest
import reverse_test_bank_premature_reserve as repair
import test_repair_test_bank_dispatch_deadline as deadline_tests


def fixture():
    state = deadline_tests.fixture()
    state.pop('config')
    state['order'][0].update(status='REVIEW_PENDING', version=5)
    state['approval'][0].pop('at')
    state['wallet'] = [{'user': repair.USER, 'available': '151.000000', 'pending': '30.000000', 'version': 6, 'deleted': 0}]
    state['unfreeze'] = [{'id': 48726, 'actor': 'superadmin', 'result': 'SUCCESS'}]
    state.update(dispatch=[], callback=[], settlement=[])
    state['reserves'] = [{'id': 134, 'reserve': repair.OLD_RESERVE, 'voucher': repair.OLD_VOUCHER,
                          'direction': 'OUT', 'amount': '30.000000', 'status': 'CONFIRMED', 'deleted': 0, 'evidence': 'original-approval'}]
    return state


def reversal():
    return {'id': 135, 'reserve': repair.REV_RESERVE, 'voucher': repair.REV_VOUCHER,
            'direction': 'IN', 'amount': '30.000000', 'status': 'CONFIRMED', 'deleted': 0,
            'evidence': 'reverse:' + repair.OLD_VOUCHER}


class PlanTest(unittest.TestCase):
    def test_exact_original_only_and_no_dispatch(self):
        change = repair.plan(fixture())
        self.assertFalse(change['scheduler_may_dispatch'])
        self.assertEqual(change['amount'], '30.000000')
        self.assertEqual(change['source_sha256'], repair.digest(fixture()))

    def test_changed_or_missing_source_blocks(self):
        for key in fixture():
            if fixture()[key]:
                state = fixture(); state[key] = []
                with self.subTest(key=key), self.assertRaises(ValueError): repair.plan(state)
        for key, changes in [('order', {'status': 'REVIEW_PASSED'}), ('order', {'attempts': 1}),
            ('order', {'version': 6}), ('order', {'amount': '31.000000'}), ('order', {'user': 1}),
            ('wallet', {'available': '152.000000'}), ('payout', {'state': 'DISPATCHING'}),
            ('payout', {'provider': 123}), ('payout', {'version': 1}), ('payout', {'risk': None}),
            ('reserves', {'amount': '29.000000'}), ('reserves', {'deleted': 1}), ('reserves', {'direction': 'IN'}),
            ('reserves', {'voucher': 'another'}), ('reserves', {'id': 133})]:
            state = fixture(); state[key][0].update(changes)
            with self.subTest(key=key, changes=changes), self.assertRaises(ValueError): repair.plan(state)
        for key in ('callback', 'dispatch', 'settlement'):
            state = fixture(); state[key] = [{'id': 123}]
            with self.subTest(key=key), self.assertRaises(ValueError): repair.plan(state)

    def test_replay_validates_exact_evidence_even_after_later_dispatch(self):
        state = fixture(); state['reserves'].append(reversal()); state['repair'] = [{'id': 48727, 'result': 'SUCCESS'}]
        state['payout'][0].update(state='PAID', provider=123)
        self.assertTrue(repair.plan(state)['already_applied'])
        for key, changes in [('reserves', {'amount': '29.000000'}), ('reserves', {'evidence': 'unrelated'}),
                             ('repair', {'result': 'FAILED'})]:
            broken = copy.deepcopy(state); broken[key][-1].update(changes)
            with self.subTest(changes=changes), self.assertRaises(ValueError): repair.plan(broken)
        state['repair'] = []
        with self.assertRaises(ValueError): repair.plan(state)


@unittest.skipUnless(os.environ.get('NEXION_RESERVE_CORRECTION_IT') == 'true', 'isolated MySQL opt-in required')
class MySqlTest(unittest.TestCase):
    def setUp(self):
        self.base = deadline_tests.MySqlTest()
        self.base.setUp(); self.db = self.base.db
        self.db.execute("UPDATE nx_withdrawal_order SET status='REVIEW_PENDING',d2_version=5")
        self.db.execute('CREATE TABLE nx_user_wallet(user_id BIGINT PRIMARY KEY,usdt_available DECIMAL(24,6),pending_withdraw DECIMAL(24,6),version BIGINT,is_deleted INT)')
        self.db.execute(f'INSERT INTO nx_user_wallet VALUES({repair.USER},151,30,6,0)')
        self.db.execute('CREATE TABLE nx_hdpay_payout_callback(event_hash CHAR(64) PRIMARY KEY,withdrawal_no VARCHAR(96))')
        self.db.execute('CREATE TABLE nx_withdrawal_payout_ledger(event_no VARCHAR(96) PRIMARY KEY,withdrawal_no VARCHAR(96))')
        schema = (pathlib.Path(__file__).resolve().parents[1] / 'schema.sql').read_text(encoding='utf-8')
        self.db.execute(re.search(r'CREATE TABLE IF NOT EXISTS nx_treasury_reserve_ledger\s*\([\s\S]*?;', schema).group(0))
        q = repair.encoded
        self.db.execute(f"INSERT INTO nx_treasury_reserve_ledger(id,reserve_no,voucher_no,direction,amount_usd,idempotency_key) VALUES(134,{q(repair.OLD_RESERVE)},{q(repair.OLD_VOUCHER)},'OUT',30,'original-approval')")
        self.db.execute(f"INSERT INTO nx_audit_log(id,service_name,resource_type,action,biz_no,actor_type,actor_username,result,risk_level) VALUES(48726,'nexion-backend','WITHDRAWAL','D2_WITHDRAWAL_REVIEW_UNFREEZE',{q(repair.ORDER)},'ADMIN','superadmin','SUCCESS','HIGH')")

    def tearDown(self):
        self.base.tearDown()

    def test_atomic_correction_rollback_and_idempotent_evidence(self):
        before = repair.snapshot(self.db); change = repair.plan(before)
        self.assertEqual(before, fixture())
        self.db.execute('START TRANSACTION'); repair.apply(self.db, before, change); self.db.execute('ROLLBACK')
        self.assertEqual(repair.snapshot(self.db), before)
        self.db.execute('START TRANSACTION'); repair.apply(self.db, before, change); self.db.execute('COMMIT')
        after = repair.snapshot(self.db)
        self.assertEqual(after['order'], before['order']); self.assertEqual(after['wallet'], before['wallet'])
        self.assertEqual(after['payout'], before['payout']); self.assertTrue(repair.plan(after)['already_applied'])
        self.assertEqual(self.db.execute("SELECT SUM(IF(direction='IN',amount_usd,-amount_usd)) FROM nx_treasury_reserve_ledger"), ['0.000000'])
        self.assertEqual(len(after['repair']), 1)

    def test_stale_approval_blocks_without_funds_or_audit_writes(self):
        before = repair.snapshot(self.db); change = repair.plan(before)
        self.db.execute("UPDATE nx_withdrawal_order SET status='REVIEW_PASSED',d2_version=6")
        self.db.execute('START TRANSACTION')
        with self.assertRaisesRegex(ValueError, 'PLAN_CHANGED'): repair.apply(self.db, before, change)
        self.db.execute('ROLLBACK')
        after = repair.snapshot(self.db)
        self.assertEqual(after['reserves'], before['reserves']); self.assertEqual(after['repair'], [])

    def test_locked_snapshot_prevents_concurrent_approval(self):
        self.db.execute('START TRANSACTION'); repair.snapshot(self.db, True)
        contender = self.base.connect(self.base.schema)
        try:
            contender.execute('SET SESSION innodb_lock_wait_timeout=1')
            with self.assertRaises(RuntimeError): contender.execute("UPDATE nx_withdrawal_order SET status='REVIEW_PASSED'")
            contender.errors.seek(0); self.assertIn('Lock wait timeout', contender.errors.read())
        finally:
            contender.close(); self.db.execute('ROLLBACK')

    def test_audit_failure_rolls_back_correction(self):
        before = repair.snapshot(self.db); change = repair.plan(before)
        self.db.execute("CREATE TRIGGER fail_audit BEFORE INSERT ON nx_audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test audit failure'")
        self.db.execute('START TRANSACTION')
        with self.assertRaises(RuntimeError): repair.apply(self.db, before, change)
        self.db.close()  # mysql exits on error; connection termination rolls back.
        self.db = self.base.db = self.base.connect(self.base.schema)
        self.assertEqual(repair.snapshot(self.db), before)


if __name__ == '__main__':
    unittest.main()

"""Explicit, TEST-only repair of credited HDPay receipts missing from D3.

No wallet or withdrawal writes. --plan is read-only; --apply requires the
reviewed source digest and running backend SHA. All reserve/audit writes share
one transaction. A lost response is resolved by rerunning the same plan.
"""
import argparse
import datetime
import decimal
import hashlib
import json
import os
import pathlib
import re
import subprocess
import tempfile
import uuid

D = decimal.Decimal


def encoded(value):
    return "CONVERT(0x" + str(value).encode().hex() + " USING utf8mb4)"


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def reserve_no(order):
    prefix = "RSV-TOPUP-"
    return prefix + (order if len(prefix + order) <= 64 else hashlib.sha256(order.encode()).hexdigest()[:64-len(prefix)])


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def make_plan(state):
    orders = state["orders"]
    require(0 < len(orders) <= 100, "CREDITED_ORDER_SET_OUTSIDE_REVIEW_LIMIT")
    require(not state["held_reconciliations"], "VIETQR_RESERVE_ALREADY_RECOGNIZED")
    require(not state["open_reviews"], "UNRESOLVED_SETTLEMENT_REVIEW")
    require(all(w["status"] in ("REVIEW_PENDING", "CONFIRMED", "REFUNDED") for w in state["withdrawals"]),
            "WITHDRAWAL_MAY_DISPATCH_OR_RELEASE_AUTOMATICALLY")
    by_voucher = {}
    for row in state["reserves"]:
        require(row["voucher"] not in by_voucher, "DUPLICATE_RESERVE_VOUCHER")
        by_voucher[row["voucher"]] = row
    wanted, missing = set(), []
    for order in orders:
        key = order["order"]
        require(re.fullmatch(r"[A-Za-z0-9_-]{1,64}", key) is not None, "INVALID_ORDER_REFERENCE")
        require(key not in wanted, "DUPLICATE_SOURCE_ORDER")
        wanted.add(key)
        amount = D(order["usdt"] or "0")
        require(amount > 0 and amount == amount.quantize(D("0.000001")), "INVALID_SETTLED_AMOUNT")
        require(order["provider"] and order["provider_status"] == 3 and order["settled_at"]
                and order["wallet_biz"] == key, "INCOMPLETE_PROVIDER_SETTLEMENT")
        intents = [x for x in state["intents"] if x["order"] == key]
        ledgers = [x for x in state["ledgers"] if x["biz"] == key]
        require(len(intents) == len(ledgers) == 1, "SOURCE_FACTS_NOT_UNIQUE")
        intent, ledger = intents[0], ledgers[0]
        require(intent["deleted"] == 0 and intent["rail"] == "HDPAY" and intent["status"] == "CREDITED"
                and intent["target"] in (None, "", "WALLET_TOPUP")
                and D(intent["requested"]) == D(intent["credited"]) == amount
                and D(intent["payable"]) == D(intent["received"]) == D(order["vnd"]), "INTENT_MISMATCH")
        require(ledger["deleted"] == 0 and ledger["status"] == "SUCCESS"
                and ledger["type"] == "VIETQR_DEPOSIT" and ledger["asset"] == "USDT"
                and ledger["direction"] == "IN" and ledger["user"] == intent["user"]
                and D(ledger["amount"]) == amount, "WALLET_LEDGER_MISMATCH")
        row = by_voucher.get(key)
        if row is not None:
            require(row["reserve"] == reserve_no(key) and row["direction"] == "IN"
                    and row["status"] == "CONFIRMED" and row["deleted"] == 0
                    and D(row["amount"]) == amount, "EXISTING_RESERVE_CONFLICT")
        else:
            missing.append({"order": key, "user": intent["user"], "amount": str(amount),
                            "provider": order["provider"], "settled_at": order["settled_at"]})
    # Unmapped manual injections might already cover these receipts. Stop for
    # reconciliation instead of assuming that a different voucher means new money.
    require(set(by_voucher) <= wanted, "UNMAPPED_RESERVE_REQUIRES_RECONCILIATION")
    source = {k: state[k] for k in ("orders", "intents", "ledgers", "wallets", "withdrawals")}
    return {"source_sha256": digest(source), "source_count": len(orders),
            "source_total": str(sum((D(x["usdt"]) for x in orders), D(0))),
            "missing": missing, "missing_total": str(sum((D(x["amount"]) for x in missing), D(0)))}


class Mysql:
    def __init__(self, argv, password=None):
        self.errors = tempfile.TemporaryFile(mode="w+")
        self.process = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=self.errors, text=True, encoding="utf-8", bufsize=1)
        if password is not None:
            require("\n" not in password and "\r" not in password, "INVALID_CREDENTIAL_FORMAT")
            self.process.stdin.write(password + "\n")
            self.process.stdin.flush()

    def execute(self, sql):
        marker = "DONE_" + uuid.uuid4().hex
        self.process.stdin.write(sql.rstrip("; \n") + ";\nSELECT '" + marker + "';\n")
        self.process.stdin.flush()
        rows = []
        while True:
            line = self.process.stdout.readline()
            if not line:
                raise RuntimeError("MYSQL_COMMAND_FAILED_TRANSACTION_UNCOMMITTED")
            line = line.rstrip("\r\n")
            if line == marker:
                return rows
            rows.append(line)

    def close(self):
        self.process.stdin.close()
        self.process.wait(timeout=15)
        self.process.stdout.close()
        self.errors.close()


def snapshot(db, lock=False):
    suffix = " FOR UPDATE" if lock else ""
    source = "SELECT merchant_order_id FROM nx_hdpay_payin_order WHERE settlement_status='CREDITED'"
    queries = {
        "orders": "SELECT JSON_OBJECT('order',merchant_order_id,'provider',provider_order_id,'provider_status',provider_status,'vnd',CAST(amount_vnd AS CHAR),'usdt',CAST(settled_usdt AS CHAR),'wallet_biz',wallet_ledger_biz_no,'settled_at',CAST(settled_at AS CHAR)) FROM nx_hdpay_payin_order WHERE settlement_status='CREDITED' ORDER BY merchant_order_id",
        "intents": f"SELECT JSON_OBJECT('order',intent_no,'user',user_id,'rail',payment_rail,'status',status,'target',settlement_target_type,'requested',CAST(requested_usdt AS CHAR),'credited',CAST(credited_usdt AS CHAR),'payable',CAST(payable_vnd AS CHAR),'received',CAST(received_vnd AS CHAR),'deleted',is_deleted) FROM nx_vietqr_intent WHERE intent_no IN ({source}) ORDER BY intent_no",
        "wallets": "SELECT JSON_OBJECT('user',user_id,'available',CAST(usdt_available AS CHAR),'pending',CAST(pending_withdraw AS CHAR),'cumulative',CAST(cumulative_deposit_usdt AS CHAR),'version',version) FROM nx_user_wallet WHERE is_deleted=0 ORDER BY user_id",
        "ledgers": f"SELECT JSON_OBJECT('biz',biz_no,'user',user_id,'type',biz_type,'asset',asset,'direction',direction,'amount',CAST(amount AS CHAR),'status',status,'deleted',is_deleted) FROM nx_wallet_ledger WHERE biz_no IN ({source}) ORDER BY biz_no,id",
        "reserves": "SELECT JSON_OBJECT('reserve',reserve_no,'voucher',voucher_no,'direction',direction,'amount',CAST(amount_usd AS CHAR),'status',status,'deleted',is_deleted) FROM nx_treasury_reserve_ledger ORDER BY voucher_no",
        "withdrawals": "SELECT JSON_OBJECT('order',withdrawal_no,'user',user_id,'amount',CAST(amount AS CHAR),'status',status,'hold',CAST(d2_hold_until AS CHAR)) FROM nx_withdrawal_order WHERE is_deleted=0 ORDER BY withdrawal_no",
        "held_reconciliations": f"SELECT JSON_OBJECT('ref',reconciliation_no) FROM nx_vietqr_reconciliation WHERE intent_no IN ({source}) AND is_deleted=0 AND received_vnd>0 AND status IN ('OPEN','CREDITED','RETURN_PENDING') ORDER BY reconciliation_no",
        "open_reviews": f"SELECT JSON_OBJECT('ref',review_no) FROM nx_hdpay_settlement_review WHERE merchant_order_id IN ({source}) AND status='OPEN' ORDER BY review_no",
    }
    return {key: [json.loads(row) for row in db.execute(query + suffix)] for key, query in queries.items()}


def apply_plan(db, plan):
    for item in plan["missing"]:
        order, amount = item["order"], item["amount"]
        key = "HDPAY:" + order
        reason = "HDPay credited receipt reserve repair 20260916; original settled_at=" + item["settled_at"]
        db.execute("INSERT INTO nx_treasury_reserve_ledger(reserve_no,voucher_no,direction,amount_usd,reason,operator,idempotency_key,status) VALUES ("
                   + ",".join(map(encoded, (reserve_no(order), order, "IN", amount, reason, "ops:hdpay-reserve-repair", key, "CONFIRMED"))) + ")")
        detail = json.dumps({**item, "source_sha256": plan["source_sha256"], "walletModified": False,
                             "withdrawalModified": False}, sort_keys=True)
        db.execute("INSERT INTO nx_audit_log(service_name,action,resource_type,resource_id,biz_no,user_id,actor_type,actor_username,result,risk_level,detail_json,retention_policy_months,expire_at) VALUES ("
                   + ",".join(map(encoded, ("nexion-backend", "D1_HDPAY_RESERVE_BACKFILLED", "HDPAY_PAYIN_ORDER", order, order, item["user"], "SYSTEM", "ops:hdpay-reserve-repair", "SUCCESS", "CRITICAL", detail)))
                   + ",120,DATE_ADD(NOW(),INTERVAL 120 MONTH))")


def server_context(expected_sha):
    require(re.fullmatch(r"[a-f0-9]{40}", expected_sha) is not None, "FULL_BACKEND_SHA_REQUIRED")
    state = json.loads(pathlib.Path("/srv/nexgrid/cd/state.json").read_text())
    require(state["backend"]["sha"] == expected_sha and state["backend"]["branch"] == "test", "UNEXPECTED_RELEASE")
    for name in ("transaction.json", "HALTED", "HALTED.json", "migrations/active.json", "migrations/START_BLOCKED"):
        require(not pathlib.Path("/srv/nexgrid/cd", name).exists(), "DEPLOYMENT_BUSY")
    pid = subprocess.check_output(["systemctl", "show", "nexgrid-backend", "-p", "MainPID", "--value"], text=True).strip()
    args = pathlib.Path("/proc", pid, "cmdline").read_bytes().decode().split("\0")
    require("--nexion.deployment.public-test=true" in args, "PUBLIC_TEST_REQUIRED")
    require("--spring.profiles.active=dev" in args, "EXPECTED_PROFILE_REQUIRED")
    env = dict(x.split("=", 1) for x in pathlib.Path("/proc", pid, "environ").read_bytes().decode().split("\0") if "=" in x)
    return env


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--plan", action="store_true")
    mode.add_argument("--apply", action="store_true")
    parser.add_argument("--expected-backend-sha", required=True)
    parser.add_argument("--expected-source-sha")
    args = parser.parse_args()
    require(not args.apply or re.fullmatch(r"[a-f0-9]{64}", args.expected_source_sha or ""), "REVIEWED_SOURCE_SHA_REQUIRED")
    # Match the existing deployer's flock path; held until the transaction closes.
    import fcntl
    with open("/srv/nexgrid/cd/lock", "r+") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        env = server_context(args.expected_backend_sha)
        command = ["docker", "exec", "-i", "nexgrid-mysql", "sh", "-c",
                   'read -r MYSQL_PWD; export MYSQL_PWD; exec mysql -u "$1" --batch --raw --skip-column-names --unbuffered nexion',
                   "sh", env["NEXION_DB_USERNAME"]]
        db = Mysql(command, env["NEXION_DB_PASSWORD"])
        try:
            db.execute("SET SESSION innodb_lock_wait_timeout=10")
            db.execute("START TRANSACTION" if args.apply else "START TRANSACTION READ ONLY")
            before = snapshot(db, args.apply)
            plan = make_plan(before)
            if args.apply:
                require(plan["source_sha256"] == args.expected_source_sha, "SOURCE_CHANGED_REPLAN_REQUIRED")
                root = pathlib.Path("/srv/nexgrid/backups") / ("hdpay-reserve-" + datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8])
                root.mkdir(mode=0o700)
                before_path = root / "before.json"
                before_path.write_text(json.dumps({"backend_sha": args.expected_backend_sha, "plan": plan, "snapshot": before}, sort_keys=True))
                before_path.chmod(0o600)
                apply_plan(db, plan)
                after = snapshot(db, True)
                verified = make_plan(after)
                require(not verified["missing"] and verified["source_sha256"] == plan["source_sha256"], "POSTCHECK_FAILED")
                # Validate process/release again before commit; no wallet, order,
                # callback, notification or provider operation is performed here.
                server_context(args.expected_backend_sha)
                db.execute("COMMIT")
                result = {"status": "APPLIED", "inserted": len(plan["missing"]), "amount": plan["missing_total"],
                          "source_sha256": plan["source_sha256"], "backup": str(root)}
                (root / "result.json").write_text(json.dumps(result, sort_keys=True))
                print(json.dumps(result, sort_keys=True))
            else:
                db.execute("ROLLBACK")
                print(json.dumps(plan, sort_keys=True))
        finally:
            db.close()  # EOF also rolls back any uncommitted transaction.


if __name__ == "__main__":
    main()

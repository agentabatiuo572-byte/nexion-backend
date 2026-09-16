"""Explicit public-test policy change; never approves or dispatches a withdrawal.

Run --plan, review its digest, then --apply with that digest and deployed SHA.
The one named pending order loses its old H1 wait but remains pending for the
operator. A private before snapshot and required audit accompany the transaction.
"""
import argparse
import datetime
import json
import pathlib
import uuid
from backfill_hdpay_reserve import Mysql, digest, encoded, require, server_context

ORDER = "WD-E68CF7F89B9444B0806A01E2A7437E0C"
VALUES = {
    "growth.phase.month.3.withdrawCooldownDays": "0",
    "growth.phase.withdraw_cooldown_days": "0",
    "withdrawal.daily_count_limit": "10",
    "wallet.withdrawal.daily_count_limit": "10",
    "withdrawal.k4_time_validation_enabled": "false",
}
KEYS = list(VALUES) + ["withdrawal.d5.version", "H1.rhythm.currentMonth", "growth.phase.current_month"]


def snapshot(db, lock=False):
    suffix = " FOR UPDATE" if lock else ""
    queries = {
        "config": "SELECT JSON_OBJECT('key',config_key,'value',config_value,'status',status,'deleted',is_deleted) FROM nx_config_item WHERE config_key IN (" + ",".join(map(encoded, KEYS)) + ") ORDER BY config_key",
        "orders": "SELECT JSON_OBJECT('order',withdrawal_no,'status',status,'hold',CAST(d2_hold_until AS CHAR),'due',CAST(d5_payout_due_at AS CHAR),'created',CAST(created_at AS CHAR),'version',d2_version,'attempts',chain_broadcast_attempts,'owner',d2_lifecycle_owner,'previous',d2_previous_status) FROM nx_withdrawal_order WHERE is_deleted=0 ORDER BY withdrawal_no",
        "payout": "SELECT JSON_OBJECT('order',withdrawal_no,'state',state,'provider',provider_order_id,'risk',approved_risk_hash) FROM nx_hdpay_payout WHERE withdrawal_no=" + encoded(ORDER),
    }
    return {key: [json.loads(row) for row in db.execute(sql + suffix)] for key, sql in queries.items()}


def plan(state):
    config = {row["key"]: row["value"] for row in state["config"]}
    require(all(row["status"] == 1 and row["deleted"] == 0 for row in state["config"]), "INACTIVE_CONFIG")
    require(config.get("H1.rhythm.currentMonth") == config.get("growth.phase.current_month") == "3", "CURRENT_MONTH_CHANGED")
    require(config.get("withdrawal.d5.version", "").isdigit(), "MISSING_D5_VERSION")
    require(all(row["status"] in ("REVIEW_PENDING", "CONFIRMED", "REFUNDED") for row in state["orders"]), "AUTOMATIC_DISPATCH_OR_RELEASE_PRESENT")
    matches = [row for row in state["orders"] if row["order"] == ORDER]
    require(len(matches) == 1, "ORDER_MISSING")
    order = matches[0]
    require(order["status"] == "REVIEW_PENDING" and order["attempts"] == 0 and order["owner"] is None and order["previous"] in (None,"REVIEW_PENDING"), "ORDER_ALREADY_ACTED_ON")
    require(len(state["payout"]) == 1 and state["payout"][0] == {"order":ORDER,"state":"READY","provider":None,"risk":None}, "PAYOUT_ALREADY_ACTED_ON")
    changes = {key:value for key,value in VALUES.items() if config.get(key) != value}
    adjust_order = order["hold"] is not None and order["hold"] > order["created"]
    return {"source_sha256":digest(state), "changes":changes, "adjust_pending_order":adjust_order,
            "order":ORDER, "new_d5_version":str(int(config["withdrawal.d5.version"]) + (1 if changes else 0)),
            "already_applied":not changes and not adjust_order}


def apply(db, before, change):
    for key,value in change["changes"].items():
        value_type = "BOOLEAN" if key.endswith("enabled") else "NUMBER"
        db.execute("INSERT INTO nx_config_item(config_key,config_value,value_type,config_group,visibility,remark,status,is_deleted) VALUES ("
                   + ",".join(map(encoded, (key,value,value_type,"wallet" if "withdrawal" in key else "growth","ADMIN","Public-test withdrawal acceptance policy 20260916")))
                   + ",1,0) ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),updated_at=CURRENT_TIMESTAMP")
    if change["changes"]:
        db.execute("UPDATE nx_config_item SET config_value=" + encoded(change["new_d5_version"]) + ",updated_at=CURRENT_TIMESTAMP WHERE config_key='withdrawal.d5.version'")
    if change["adjust_pending_order"]:
        db.execute("UPDATE nx_withdrawal_order SET d2_hold_until=created_at,d5_payout_due_at=DATE_ADD(created_at,INTERVAL 24 HOUR),d2_version=d2_version+1,updated_at=CURRENT_TIMESTAMP WHERE withdrawal_no="
                   + encoded(ORDER) + " AND status='REVIEW_PENDING' AND chain_broadcast_attempts=0")
    detail = json.dumps({"plan":change,"config_before":before["config"],"order_before":next(x for x in before["orders"] if x["order"]==ORDER),"payoutSubmitted":False},sort_keys=True)
    db.execute("INSERT INTO nx_audit_log(service_name,action,resource_type,resource_id,biz_no,actor_type,actor_username,result,risk_level,detail_json,retention_policy_months,expire_at) VALUES ("
               + ",".join(map(encoded,("nexion-backend","TEST_WITHDRAWAL_POLICY_CHANGED","WITHDRAWAL_POLICY",ORDER,ORDER,"SYSTEM","ops:test-withdrawal-policy","SUCCESS","HIGH",detail)))
               + ",120,DATE_ADD(NOW(),INTERVAL 120 MONTH))")


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    mode=parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--plan",action="store_true")
    mode.add_argument("--apply",action="store_true")
    parser.add_argument("--expected-backend-sha",required=True)
    parser.add_argument("--expected-source-sha")
    args=parser.parse_args()
    import fcntl
    with open("/srv/nexgrid/cd/lock","r+") as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        env=server_context(args.expected_backend_sha)
        command=["docker","exec","-i","nexgrid-mysql","sh","-c",'read -r MYSQL_PWD; export MYSQL_PWD; exec mysql -u "$1" --batch --raw --skip-column-names --unbuffered nexion',"sh",env["NEXION_DB_USERNAME"]]
        db=Mysql(command,env["NEXION_DB_PASSWORD"])
        try:
            db.execute("SET SESSION innodb_lock_wait_timeout=10")
            db.execute("START TRANSACTION" if args.apply else "START TRANSACTION READ ONLY")
            before=snapshot(db,args.apply)
            change=plan(before)
            if not args.apply or change["already_applied"]:
                db.execute("ROLLBACK")
                print(json.dumps(change,sort_keys=True))
                return
            require(args.expected_source_sha==change["source_sha256"],"SOURCE_CHANGED_REPLAN_REQUIRED")
            root=pathlib.Path("/srv/nexgrid/backups")/("withdrawal-policy-"+datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d-%H%M%S")+"-"+uuid.uuid4().hex[:8])
            root.mkdir(mode=0o700)
            backup=root/"before.json"
            backup.write_text(json.dumps(before,sort_keys=True))
            backup.chmod(0o600)
            apply(db,before,change)
            require(plan(snapshot(db,True))["already_applied"],"POSTCHECK_FAILED")
            server_context(args.expected_backend_sha)
            db.execute("COMMIT")
            print(json.dumps({"status":"APPLIED","backup":str(root),"changes":change,"order_status":"REVIEW_PENDING","payoutSubmitted":False},sort_keys=True))
        finally:
            db.close()


if __name__=="__main__":
    main()

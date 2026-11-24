"""Inspect one D1 anomaly or apply its externally approved cumulative-only repair."""

import argparse
import hashlib
import json
import pathlib
import re
import sys

from backfill_hdpay_reserve import Mysql, encoded, require


ACTION = "D1_HISTORICAL_TOPUP_CUMULATIVE_RESOLVED"
RESOURCE = "D1_TOPUP_BACKFILL_ANOMALY"
SIGNOFF_FIELDS = ("approvalRef", "financeOwner", "reviewer", "openingBasisRef",
                  "historyEvidenceRef", "historyCutoff", "reason")
AUDIT_TIME_FORMAT = "%Y-%m-%d %H:%i:%s.%f"


def anomaly(db, anomaly_id, lock=""):
    rows = db.execute(f"""
        SELECT JSON_OBJECT('id',id,'userId',user_id,'sourceType',source_type,'sourceNo',source_no,
                           'reasonCode',reason_code,'amountUsdt',CAST(amount_usdt AS CHAR),
                           'state',status,'updatedAt',CAST(updated_at AS CHAR))
          FROM nx_topup_cumulative_backfill_anomaly
         WHERE id={anomaly_id} AND is_deleted=0{lock}
        """)
    require(len(rows) == 1, "ANOMALY_NOT_FOUND")
    return json.loads(rows[0])


def wallet(db, user_id, lock=""):
    if user_id is None:
        return None
    rows = db.execute(f"""
        SELECT JSON_OBJECT('id',id,'incarnation',fund_incarnation,'version',version,
                           'cumulativeUsdt',CAST(cumulative_deposit_usdt AS CHAR))
          FROM nx_user_wallet
         WHERE user_id={user_id} AND is_deleted=0 AND sandbox=0{lock}
        """)
    return json.loads(rows[0]) if rows else None


def receipt(db, anomaly_id):
    ref = encoded(anomaly_id)
    rows = db.execute(f"""
        SELECT JSON_OBJECT('auditId',id,'at',DATE_FORMAT(created_at,'{AUDIT_TIME_FORMAT}'),'operator',actor_username,
                           'detail',detail_json,'valid',1)
          FROM nx_audit_log
         WHERE resource_type='{RESOURCE}' AND resource_id={ref} AND action='{ACTION}'
           AND result='SUCCESS' AND is_deleted=0
        UNION ALL
        SELECT JSON_OBJECT('auditId',original_audit_id,'at',DATE_FORMAT(original_created_at,'{AUDIT_TIME_FORMAT}'),
                           'operator',cold_payload->>'$.actorUsername',
                           'detail',CAST(cold_payload->>'$.detail' AS JSON),
                           'valid',content_sha256=SHA2(CAST(cold_payload AS CHAR),256)
                               AND cold_payload->'$.id'=original_audit_id)
          FROM nx_audit_log_archive
         WHERE cold_payload->>'$.resourceType'='{RESOURCE}' AND cold_payload->>'$.resourceId'={ref}
           AND cold_payload->>'$.action'='{ACTION}' AND cold_payload->>'$.result'='SUCCESS'
        """)
    require(len(rows) <= 1, "AUDIT_RECEIPT_CONFLICT")
    if not rows:
        return None
    found = json.loads(rows[0])
    require(found.pop("valid") == 1, "AUDIT_RECEIPT_INVALID")
    return found


def audit_policy(db):
    rows = db.execute("""
        SELECT JSON_ARRAY(config_key, config_value) FROM nx_config_item
         WHERE status=1 AND is_deleted=0
           AND config_key IN ('admin.a2.retention_months','admin.a2.schema_version')
        """)
    policy = dict(json.loads(row) for row in rows)
    months = re.fullmatch(r"([0-9]{1,3})\s*(?:个月|月|months?)?",
                          policy.get("admin.a2.retention_months", "").strip(), re.IGNORECASE)
    version = re.sub(r"^统一\s*schema\s*·?\s*", "",
                     policy.get("admin.a2.schema_version", "").strip(), flags=re.IGNORECASE).strip()
    require(months and 13 <= int(months[1]) <= 36 and version in ("v3", "v4"), "A2_AUDIT_POLICY_UNAVAILABLE")
    return int(months[1]), version


def load_approval(args):
    raw = args.manifest.read_bytes()
    manifest_sha = hashlib.sha256(raw).hexdigest()
    require(manifest_sha == args.approved_sha256, "FINANCE_APPROVED_MANIFEST_SHA_REQUIRED")
    plan = json.loads(raw)
    require(isinstance(plan, dict) and isinstance(plan.get("snapshot"), dict), "INVALID_MANIFEST")
    for field in SIGNOFF_FIELDS:
        value = plan.get(field)
        require(isinstance(value, str) and 0 < len(value.strip()) <= 128, "MISSING_OR_INVALID_" + field)
    with args.evidence.open("rb") as evidence:
        proof_sha = hashlib.file_digest(evidence, "sha256").hexdigest()
        require(evidence.tell() > 0 and proof_sha == plan.get("evidenceSha256"), "SIGNED_EVIDENCE_MISMATCH")
    target = plan.get("targetUsdt")
    require(isinstance(target, str) and re.fullmatch(r"(?:0|[1-9][0-9]{0,11})\.[0-9]{6}", target),
            "TARGET_MUST_BE_DECIMAL_18_6")
    expected = plan["snapshot"].get("anomaly")
    require(isinstance(expected, dict) and type(expected.get("id")) is int
            and 0 < expected["id"] <= 9223372036854775807, "INVALID_ANOMALY_ID")
    return plan, manifest_sha


def apply(db, database, plan, manifest_sha, operator):
    expected = plan["snapshot"]
    anomaly_id = expected["anomaly"]["id"]
    require(database == expected.get("database"), "APPROVED_DATABASE_MISMATCH")
    item = anomaly(db, anomaly_id, " FOR UPDATE")
    original = receipt(db, anomaly_id)
    if original:
        require(item["state"] == "RESOLVED" and original["detail"]["manifestSha256"] == manifest_sha,
                "ANOMALY_ALREADY_PROCESSED_OR_REOPENED")
        return {"status": "REPLAYED", **original}

    user_id = item["userId"]
    require(item["state"] == "OPEN" and user_id is not None and item["sourceType"] == "WALLET"
            and item["sourceNo"] == f"USER-{user_id}" and item["reasonCode"] == "UNBACKED_CUMULATIVE_BALANCE",
            "ANOMALY_NOT_ELIGIBLE_FOR_CUMULATIVE_ONLY_REPAIR")
    current = wallet(db, user_id, " FOR UPDATE")
    require(current is not None and current["incarnation"] and item == expected.get("anomaly")
            and current == expected.get("wallet"), "ANOMALY_OR_WALLET_CHANGED_REVIEW_AGAIN")

    retention_months, schema_version = audit_policy(db)
    target = plan["targetUsdt"]
    after_version = current["version"]
    if target != current["cumulativeUsdt"]:
        db.execute(f"""
            UPDATE nx_user_wallet
               SET cumulative_deposit_usdt={target},version=version+1,updated_at=NOW()
             WHERE id={current['id']}
            """)
        after_version += 1
    require(wallet(db, user_id, " FOR UPDATE") == {
        **current, "cumulativeUsdt": target, "version": after_version
    }, "WALLET_POSTCHECK_FAILED")
    db.execute(f"UPDATE nx_topup_cumulative_backfill_anomaly SET status='RESOLVED',updated_at=NOW() WHERE id={anomaly_id}")
    require(anomaly(db, anomaly_id, " FOR UPDATE")["state"] == "RESOLVED", "ANOMALY_POSTCHECK_FAILED")

    detail = {field: plan[field] for field in SIGNOFF_FIELDS}
    detail.update(snapshot={"database": database, "anomaly": item, "wallet": current},
                  targetUsdt=target, walletVersionAfter=after_version, manifestSha256=manifest_sha,
                  evidenceSha256=plan["evidenceSha256"], schemaVersion=schema_version)
    values = map(encoded, ("nexion-backend", ACTION, RESOURCE, anomaly_id, item["sourceNo"],
                           operator, json.dumps(detail, ensure_ascii=False)))
    service, action, resource, ref, source, actor, payload = values
    db.execute(f"""
        INSERT INTO nx_audit_log
          (service_name,action,resource_type,resource_id,biz_no,user_id,actor_type,actor_username,
           result,risk_level,detail_json,retention_policy_months,expire_at)
        VALUES ({service},{action},{resource},{ref},{source},{user_id},'SYSTEM',{actor},
                'SUCCESS','CRITICAL',{payload},{retention_months},DATE_ADD(NOW(),INTERVAL {retention_months} MONTH))
        """)
    audit_id, at = db.execute(f"""
        SELECT id,DATE_FORMAT(created_at,'{AUDIT_TIME_FORMAT}') FROM nx_audit_log WHERE id=LAST_INSERT_ID()
        """)[0].split("\t")
    return {"status": "APPLIED", "auditId": int(audit_id), "at": at, "operator": operator, "detail": detail}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mysql", default="mysql")
    parser.add_argument("--client-config", type=pathlib.Path, required=True, help="Existing MySQL client option file")
    parser.add_argument("--database", required=True)
    modes = parser.add_subparsers(dest="mode", required=True)
    inspect = modes.add_parser("inspect")
    inspect.add_argument("--anomaly-id", type=int, required=True)
    query = modes.add_parser("receipt")
    query.add_argument("--anomaly-id", type=int, required=True)
    execute = modes.add_parser("apply")
    execute.add_argument("--manifest", type=pathlib.Path, required=True)
    execute.add_argument("--approved-sha256", required=True, help="SHA-256 obtained from the finance sign-off")
    execute.add_argument("--evidence", type=pathlib.Path, required=True)
    args = parser.parse_args()
    writing = args.mode == "apply"
    if writing:
        plan, manifest_sha = load_approval(args)
    db = Mysql([args.mysql, f"--defaults-file={args.client_config.resolve(strict=True)}",
                "--batch", "--raw", "--skip-column-names", "--unbuffered", "--skip-force", "--skip-reconnect",
                "--default-character-set=utf8mb4", f"--database={args.database}"])
    try:
        db.execute("SET SESSION innodb_lock_wait_timeout=10")
        db.execute("START TRANSACTION" if writing else "START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY")
        database, operator = json.loads(db.execute(
            "SELECT JSON_ARRAY(JSON_OBJECT('schema',DATABASE(),'serverUuid',@@server_uuid),USER())"
        )[0])
        if writing:
            result = apply(db, database, plan, manifest_sha, operator)
            try:
                db.execute("COMMIT")
            except (OSError, RuntimeError) as exc:
                raise RuntimeError("COMMIT_RESULT_UNKNOWN_CHECK_ORIGINAL_RECEIPT") from exc
        else:
            if args.mode == "receipt":
                result = receipt(db, args.anomaly_id)
                require(result is not None, "RECEIPT_NOT_FOUND")
            else:
                item = anomaly(db, args.anomaly_id)
                result = {"database": database, "anomaly": item, "wallet": wallet(db, item["userId"])}
            db.execute("ROLLBACK")
    finally:
        db.close()
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError) as exc:
        sys.exit(str(exc))

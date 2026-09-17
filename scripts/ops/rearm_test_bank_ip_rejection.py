"""One user-authorized public-test rearm after documented IP rejection and fresh provider absence.

Does not approve, create a provider order, change recipients, or modify money.
Original attempts remain in audit; the new approval must take a fresh risk snapshot.
"""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import subprocess
import uuid
from backfill_hdpay_reserve import Mysql, digest, encoded, require, server_context
from reverse_test_bank_premature_reserve import (snapshot, ORDER, USER, NOW, check_entry,
                                               OLD_RESERVE, OLD_VOUCHER, REV_RESERVE, REV_VOUCHER)

ACTION = 'TEST_BANK_PAYOUT_REARMED_AFTER_IP_REJECTION'
DIAG = pathlib.Path('/srv/nexgrid/ops/bank-reserve-37c7315/query-diagnostic')
SOURCE_HASHES = {
    'QueryHdPayReadOnly.java': 'c799fe71e8eb620fc3c6cea3aec845586b3057dc6d3d8a8460495f24c266c72a',
    'CallHdPayPayoutOnce.java': 'a5aafd31eae1b0d8dd651e60ca7a286efe3fbbb50a504d9f34b8308f828fd6b4',
}


def validate(state):
    require(state['user'] == [{'id': USER, 'status': 'ACTIVE', 'sandbox': 0, 'deleted': 0}], 'USER_CHANGED')
    require(state['wallet'] == [{'user': USER, 'available': '151.000000', 'pending': '30.000000', 'version': 6, 'deleted': 0}], 'WALLET_CHANGED')
    require(len(state['order']) == len(state['payout']) == 1, 'ORDER_NOT_UNIQUE')
    w, p = state['order'][0], state['payout'][0]
    require(w['user'] == USER and w['chain'] == 'BANK-VND' and w['status'] == 'PROCESSING'
            and w['amount'] == '30.000000' and w['net'] == '29.000000' and w['attempts'] == 1
            and w['version'] == 7 and w['deleted'] == 0 and w['owner'] is None and w['previous'] is None, 'ORDER_CHANGED')
    require(p['state'] == 'DISPATCHING' and p['provider'] is None and p['provider_status'] is None, 'PROVIDER_ACTIVITY_EXISTS')
    require(not state['callback'] and not state['settlement'], 'PROVIDER_SETTLEMENT_EXISTS')
    require([x['id'] for x in state['dispatch']] == [48734], 'DISPATCH_HISTORY_CHANGED')
    require(bool(state['approval']) and state['approval'][-1] == {'id': 48733, 'actor': 'superadmin', 'result': 'SUCCESS'}, 'APPROVAL_CHANGED')
    require([x['id'] for x in state['reserves']] == [134, 135]
            and state['reserves'][0]['direction'] == 'OUT' and state['reserves'][1]['direction'] == 'IN'
            and all(x['amount'] == '30.000000' and x['status'] == 'CONFIRMED' and x['deleted'] == 0 for x in state['reserves']), 'RESERVE_CHANGED')
    check_entry(state['reserves'][0], OLD_RESERVE, OLD_VOUCHER, 'OUT')
    check_entry(state['reserves'][1], REV_RESERVE, REV_VOUCHER, 'IN', 'reverse:' + OLD_VOUCHER)


def provider_absence(env):
    # These exact private helpers were inspected: both bind ORDER and the runtime merchant.
    # The create helper is only hashed here; it must never be executed by this recovery.
    require(env.get('NEXION_HDPAY_MERCHANT_ID') == '2094724651524763649', 'MERCHANT_CHANGED')
    for name, expected in SOURCE_HASHES.items():
        require(hashlib.sha256((DIAG / name).read_bytes()).hexdigest() == expected, 'PROVIDER_EVIDENCE_SOURCE_CHANGED')
    receipt = json.loads((DIAG / 'user-requested-create-20260917-01.response.json').read_text())
    require(receipt.get('exitCode') == 0 and 'DIRECT_CREATE_HTTP 200' in receipt.get('output', ''), 'MISSING_REJECTION_RECEIPT')
    body = json.loads(receipt['output'].split('DIRECT_CREATE_RESPONSE ', 1)[1])
    require(body == {'code': 408, 'msg': '该ip禁止访问'}, 'REJECTION_RECEIPT_CHANGED')
    result = subprocess.run(['java', '--class-path', str(DIAG / '*'), str(DIAG / 'QueryHdPayReadOnly.java')],
                            env={**os.environ, **env}, text=True, capture_output=True, timeout=25)
    lines = result.stdout.splitlines()
    require(result.returncode == 0 and 'JAVA_QUERY_HTTP 200' in lines, 'PROVIDER_QUERY_UNAVAILABLE')
    reports = [json.loads(line.split('JAVA_QUERY_RESULT ', 1)[1]) for line in lines if line.startswith('JAVA_QUERY_RESULT ')]
    require(reports == [{'code': '500', 'msg': '代付订单不存在'}]
            and not any(line.startswith('JAVA_QUERY_ORDER ') for line in lines), 'PROVIDER_ABSENCE_NOT_CONFIRMED')
    return {'queryHttp': 200, 'queryCode': 500, 'queryMessage': '代付订单不存在', 'previousCreateCode': 408,
            'checkedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat()}


def apply(db, before, evidence, backup):
    validate(before)
    q = encoded(ORDER)
    require(snapshot(db, True) == before, 'LOCKED_STATE_CHANGED')
    require(db.execute('SELECT COUNT(*) FROM nx_audit_log WHERE action=' + encoded(ACTION) + ' AND biz_no=' + q) == ['0'], 'ALREADY_REARMED')
    # Clear only dispatch/review state. Never delete old audit/idempotency records or reset the quote.
    changed = db.execute("UPDATE nx_withdrawal_order SET status='REVIEW_PENDING',d2_hold_until=NULL,d2_lifecycle_owner=NULL,"
               "d2_previous_status=NULL,d2_freeze_period=NULL,failure_reason=NULL,chain_broadcast_attempts=0,"
               "updated_at=" + NOW + ",d2_version=d2_version+1"
               " WHERE withdrawal_no=" + q + " AND status='PROCESSING' AND chain='BANK-VND' AND d2_version=7"
               " AND chain_broadcast_attempts=1 AND d5_provider_cid IS NULL AND is_deleted=0; SELECT ROW_COUNT()")
    require(changed == ['1'], 'ORDER_CAS_FAILED')
    changed = db.execute("UPDATE nx_hdpay_payout SET state='READY',next_query_at=NULL,last_error=NULL,approved_risk_hash=NULL,"
               "updated_at=" + NOW + ",version=version+1 WHERE withdrawal_no=" + q + " AND state='DISPATCHING'"
               " AND provider_order_id IS NULL AND provider_status IS NULL AND version=" + str(before['payout'][0]['version']) + '; SELECT ROW_COUNT()')
    require(changed == ['1'], 'PAYOUT_CAS_FAILED')
    detail = json.dumps({'before': before, 'providerEvidence': evidence, 'backup': backup,
                         'reason': 'User requested original order pending for manual reapproval after BANK/server IP correction',
                         'moneyModified': False, 'providerCreateCalled': False, 'manualApprovalRequired': True}, sort_keys=True)
    db.execute('INSERT INTO nx_audit_log(service_name,action,resource_type,resource_id,biz_no,user_id,actor_type,actor_username,result,risk_level,detail_json,retention_policy_months,created_at,expire_at) VALUES ('
               + ','.join(map(encoded, ('nexion-backend', ACTION, 'WITHDRAWAL', ORDER, ORDER, USER, 'SYSTEM',
                                       'ops:test-bank-ip-rearm', 'SUCCESS', 'CRITICAL', detail)))
               + ',120,' + NOW + ',DATE_ADD(' + NOW + ',INTERVAL 120 MONTH))')
    after = snapshot(db, True)
    require(all(after[k] == before[k] for k in before if k not in ('order', 'payout')), 'UNRELATED_STATE_CHANGED')
    w, p = after['order'][0], after['payout'][0]
    require(w == {**before['order'][0], 'status': 'REVIEW_PENDING', 'hold': None, 'attempts': 0, 'version': 8}, 'ORDER_POSTCHECK_FAILED')
    require(p == {**before['payout'][0], 'state': 'READY', 'risk': None, 'next_query': None,
                  'version': before['payout'][0]['version'] + 1}, 'PAYOUT_POSTCHECK_FAILED')
    return after


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--expected-backend-sha', required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    import fcntl
    with open('/srv/nexgrid/cd/lock', 'r+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        env = server_context(args.expected_backend_sha)
        require(env.get('NEXION_HDPAY_SERVER_IP') == '18.142.169.24', 'CORRECTED_SERVER_IP_NOT_LOADED')
        command = ['docker', 'exec', '-i', 'nexgrid-mysql', 'sh', '-c',
                   'read -r MYSQL_PWD; export MYSQL_PWD; exec mysql -u "$1" --batch --raw --skip-column-names --unbuffered nexion',
                   'sh', env['NEXION_DB_USERNAME']]
        db = Mysql(command, env['NEXION_DB_PASSWORD'])
        try:
            db.execute('SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED')
            db.execute('SET SESSION innodb_lock_wait_timeout=10')
            db.execute('START TRANSACTION' if args.apply else 'START TRANSACTION READ ONLY')
            done = db.execute('SELECT id FROM nx_audit_log WHERE action=' + encoded(ACTION) + ' AND biz_no=' + encoded(ORDER))
            if done:
                require(len(done) == 1, 'DUPLICATE_REARM_AUDIT'); db.execute('ROLLBACK')
                print(json.dumps({'status': 'ALREADY_APPLIED', 'auditId': done[0]})); return
            before = snapshot(db, args.apply); validate(before)
            evidence = provider_absence(env)
            if not args.apply:
                db.execute('ROLLBACK')
                print(json.dumps({'status': 'READY_FOR_REARM', 'order': ORDER, 'sourceDigest': digest(before), 'providerEvidence': evidence})); return
            root = pathlib.Path('/srv/nexgrid/backups') / ('bank-ip-rearm-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:8])
            root.mkdir(mode=0o700)
            file = root / 'before.json'; file.write_text(json.dumps({'before': before, 'providerEvidence': evidence})); file.chmod(0o600)
            after = apply(db, before, evidence, str(root))
            server_context(args.expected_backend_sha)
            audit = db.execute('SELECT id FROM nx_audit_log WHERE action=' + encoded(ACTION) + ' AND biz_no=' + encoded(ORDER))
            require(len(audit) == 1, 'AUDIT_MISSING'); db.execute('COMMIT')
            result = {'status': 'APPLIED', 'order': ORDER, 'auditId': audit[0], 'backup': str(root), 'orderVersion': after['order'][0]['version'], 'payoutVersion': after['payout'][0]['version']}
            (root / 'result.json').write_text(json.dumps(result)); print(json.dumps(result))
        finally:
            db.close()


if __name__ == '__main__':
    main()

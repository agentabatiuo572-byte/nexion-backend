"""Reverse only the premature D3 OUT of one never-dispatched public-test order.

Preserves the original OUT, wallet, approval history and current REVIEW_PENDING
state. Uses the same reversal voucher as runtime settlement, so later callbacks
cannot credit it twice. Does not approve, dispatch or contact HDPay.
"""
import argparse
import datetime
import json
import pathlib
import uuid
from backfill_hdpay_reserve import Mysql, digest, encoded, require, server_context

ORDER = 'WD-E68CF7F89B9444B0806A01E2A7437E0C'
USER = 60723153007
ACTION = 'TEST_BANK_PREMATURE_RESERVE_REVERSED'
OLD_RESERVE, OLD_VOUCHER = 'RSV-WD-' + ORDER, 'WD-' + ORDER
REV_RESERVE, REV_VOUCHER = 'RSV-WD-REV-' + ORDER, 'WD-REV-' + ORDER
NOW = 'DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR)'


def snapshot(db, lock=False):
    suffix = ' FOR UPDATE' if lock else ''
    q = encoded(ORDER)
    queries = {
        'user': f"SELECT JSON_OBJECT('id',id,'status',status,'sandbox',sandbox,'deleted',is_deleted) FROM nx_user WHERE id={USER}",
        'wallet': f"SELECT JSON_OBJECT('user',user_id,'available',CAST(usdt_available AS CHAR),'pending',CAST(pending_withdraw AS CHAR),'version',version,'deleted',is_deleted) FROM nx_user_wallet WHERE user_id={USER}",
        'payout': "SELECT JSON_OBJECT('state',state,'provider',provider_order_id,'provider_status',provider_status,'risk',approved_risk_hash,'version',version,'next_query',CAST(next_query_at AS CHAR)) FROM nx_hdpay_payout WHERE withdrawal_no=" + q,
        'order': "SELECT JSON_OBJECT('user',user_id,'status',status,'chain',chain,'amount',CAST(amount AS CHAR),'net',CAST(d2_net_receive AS CHAR),'hold',CAST(d2_hold_until AS CHAR),'attempts',chain_broadcast_attempts,'version',d2_version,'deleted',is_deleted,'owner',d2_lifecycle_owner,'previous',d2_previous_status) FROM nx_withdrawal_order WHERE withdrawal_no=" + q,
        'approval': "SELECT JSON_OBJECT('id',id,'actor',actor_username,'result',result) FROM nx_audit_log WHERE biz_no=" + q + " AND action='D2_WITHDRAWAL_REVIEW_APPROVE' ORDER BY id",
        'unfreeze': "SELECT JSON_OBJECT('id',id,'actor',actor_username,'result',result) FROM nx_audit_log WHERE biz_no=" + q + " AND action='D2_WITHDRAWAL_REVIEW_UNFREEZE' ORDER BY id",
        'dispatch': "SELECT JSON_OBJECT('id',id) FROM nx_audit_log WHERE biz_no=" + q + " AND action='BANK_PAYOUT_DISPATCH_INTENT' ORDER BY id",
        'callback': "SELECT JSON_OBJECT('event',event_hash) FROM nx_hdpay_payout_callback WHERE withdrawal_no=" + q + ' ORDER BY event_hash',
        'settlement': "SELECT JSON_OBJECT('event',event_no) FROM nx_withdrawal_payout_ledger WHERE withdrawal_no=" + q + ' ORDER BY event_no',
        'repair': "SELECT JSON_OBJECT('id',id,'result',result) FROM nx_audit_log WHERE biz_no=" + q + ' AND action=' + encoded(ACTION) + ' ORDER BY id',
        'reserves': "SELECT JSON_OBJECT('id',id,'reserve',reserve_no,'voucher',voucher_no,'direction',direction,'amount',CAST(amount_usd AS CHAR),'status',status,'deleted',is_deleted,'evidence',idempotency_key) FROM nx_treasury_reserve_ledger WHERE reserve_no IN (" + ','.join(map(encoded, (OLD_RESERVE, REV_RESERVE, 'RSV-WD-PAID-' + ORDER))) + ') OR voucher_no IN (' + ','.join(map(encoded, (OLD_VOUCHER, REV_VOUCHER, 'WD-PAID-' + ORDER))) + ') ORDER BY id',
    }
    return {key: [json.loads(row) for row in db.execute(query + suffix)] for key, query in queries.items()}


def check_entry(row, reserve, voucher, direction, evidence=None):
    require(row['reserve'] == reserve and row['voucher'] == voucher and row['direction'] == direction
            and row['amount'] == '30.000000' and row['status'] == 'CONFIRMED' and row['deleted'] == 0
            and (evidence is None or row['evidence'] == evidence), 'RESERVE_EVIDENCE_CONFLICT')


def plan(state):
    require(len(state['repair']) <= 1, 'DUPLICATE_REPAIR_AUDIT')
    old = [r for r in state['reserves'] if r['reserve'] == OLD_RESERVE or r['voucher'] == OLD_VOUCHER]
    rev = [r for r in state['reserves'] if r['reserve'] == REV_RESERVE or r['voucher'] == REV_VOUCHER]
    require(len(old) == 1 and old[0]['id'] == 134, 'ORIGINAL_OUT_CHANGED')
    check_entry(old[0], OLD_RESERVE, OLD_VOUCHER, 'OUT')
    if state['repair']:
        require(state['repair'][0]['result'] == 'SUCCESS' and len(rev) == 1, 'REPAIR_EVIDENCE_INCOMPLETE')
        check_entry(rev[0], REV_RESERVE, REV_VOUCHER, 'IN', 'reverse:' + OLD_VOUCHER)
        return {'already_applied': True, 'order': ORDER}
    require(not rev and state['reserves'] == old, 'UNEXPECTED_RESERVE_ENTRY')
    require(state['user'] == [{'id': USER, 'status': 'ACTIVE', 'sandbox': 0, 'deleted': 0}], 'USER_CHANGED')
    require(state['wallet'] == [{'user': USER, 'available': '151.000000', 'pending': '30.000000', 'version': 6, 'deleted': 0}], 'WALLET_CHANGED')
    require(state['order'] == [{'user': USER, 'status': 'REVIEW_PENDING', 'chain': 'BANK-VND', 'amount': '30.000000', 'net': '29.000000',
        'hold': None, 'attempts': 0, 'version': 5, 'deleted': 0, 'owner': None, 'previous': None}], 'ORDER_CHANGED')
    require(len(state['payout']) == 1, 'PAYOUT_NOT_UNIQUE')
    payout = state['payout'][0]
    require(payout['state'] == 'READY' and payout['provider'] is None and payout['provider_status'] is None
            and payout['version'] == 0 and payout['next_query'] is None
            and isinstance(payout['risk'], str) and len(payout['risk']) == 64, 'PAYOUT_ALREADY_ACTED_ON')
    require(state['approval'] == [{'id': 48715, 'actor': 'superadmin', 'result': 'SUCCESS'}], 'APPROVAL_CHANGED')
    require(state['unfreeze'] == [{'id': 48726, 'actor': 'superadmin', 'result': 'SUCCESS'}], 'UNFREEZE_CHANGED')
    require(not state['dispatch'] and not state['callback'] and not state['settlement'], 'PROVIDER_ACTIVITY_EXISTS')
    return {'already_applied': False, 'order': ORDER, 'source_sha256': digest(state), 'amount': '30.000000',
            'original_reserve_id': 134, 'voucher': REV_VOUCHER, 'scheduler_may_dispatch': False}


def apply(db, before, change):
    # Re-read with row locks even when called outside main; a stale plan can never
    # undo a just-dispatched order. Caller owns the transaction and rollback.
    require(not change['already_applied'] and snapshot(db, True) == before and plan(before) == change, 'PLAN_CHANGED')
    values = (REV_RESERVE, REV_VOUCHER, 'IN', '30.000000',
              'Correction of bank reserve posted before provider success: ' + OLD_VOUCHER,
              'ops:test-bank-reserve-correction', 'reverse:' + OLD_VOUCHER, 'CONFIRMED')
    db.execute('INSERT INTO nx_treasury_reserve_ledger(reserve_no,voucher_no,direction,amount_usd,reason,operator,idempotency_key,status,created_at,updated_at) VALUES ('
               + ','.join(map(encoded, values)) + ',' + NOW + ',' + NOW + ')')
    detail = json.dumps({'plan': change, 'original': before['reserves'][0], 'walletModified': False,
                         'withdrawalModified': False, 'providerContacted': False}, sort_keys=True)
    db.execute('INSERT INTO nx_audit_log(service_name,action,resource_type,resource_id,biz_no,user_id,actor_type,actor_username,result,risk_level,detail_json,retention_policy_months,created_at,expire_at) VALUES ('
               + ','.join(map(encoded, ('nexion-backend', ACTION, 'WITHDRAWAL', ORDER, ORDER, USER, 'SYSTEM',
                                       'ops:test-bank-reserve-correction', 'SUCCESS', 'CRITICAL', detail)))
               + ',120,' + NOW + ',DATE_ADD(' + NOW + ',INTERVAL 120 MONTH))')
    after = snapshot(db, True)
    require(all(after[key] == before[key] for key in before if key not in ('reserves', 'repair')), 'UNRELATED_STATE_CHANGED')
    require(len(after['reserves']) == 2 and after['reserves'][0] == before['reserves'][0]
            and len(after['repair']) == 1 and plan(after)['already_applied'], 'POSTCHECK_FAILED')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--plan', action='store_true')
    mode.add_argument('--apply', action='store_true')
    parser.add_argument('--expected-backend-sha', required=True)
    parser.add_argument('--expected-source-sha')
    args = parser.parse_args()
    import fcntl
    with open('/srv/nexgrid/cd/lock', 'r+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        env = server_context(args.expected_backend_sha)
        command = ['docker', 'exec', '-i', 'nexgrid-mysql', 'sh', '-c',
                   'read -r MYSQL_PWD; export MYSQL_PWD; exec mysql -u "$1" --batch --raw --skip-column-names --unbuffered nexion',
                   'sh', env['NEXION_DB_USERNAME']]
        db = Mysql(command, env['NEXION_DB_PASSWORD'])
        try:
            db.execute('SET SESSION innodb_lock_wait_timeout=10')
            db.execute('START TRANSACTION' if args.apply else 'START TRANSACTION READ ONLY')
            before = snapshot(db, args.apply)
            change = plan(before)
            if not args.apply or change['already_applied']:
                db.execute('ROLLBACK'); print(json.dumps(change, sort_keys=True)); return
            require(args.expected_source_sha == change['source_sha256'], 'SOURCE_CHANGED_REPLAN_REQUIRED')
            root = pathlib.Path('/srv/nexgrid/backups') / ('bank-reserve-correction-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:8])
            root.mkdir(mode=0o700)
            backup = root / 'before.json'
            backup.write_text(json.dumps({'backend_sha': args.expected_backend_sha, 'state': before, 'plan': change}, sort_keys=True))
            backup.chmod(0o600)
            apply(db, before, change)
            server_context(args.expected_backend_sha)
            db.execute('COMMIT')
            print(json.dumps({'status': 'APPLIED', 'order': ORDER, 'amount': change['amount'], 'backup': str(root),
                              'scheduler_may_dispatch': False}, sort_keys=True))
        finally:
            db.close()


if __name__ == '__main__':
    main()

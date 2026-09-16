"""Repair one already-approved, never-dispatched public-test bank withdrawal.

Restores the missing deadline from its real approval audit. The normal scheduler
may dispatch after commit; this tool never creates a provider order, approves an
order, changes risk evidence, resets a dispatch, or writes a funds ledger.
"""
import argparse
import datetime
import json
import pathlib
import uuid
from backfill_hdpay_reserve import Mysql, digest, encoded, require, server_context

ORDER = "WD-E68CF7F89B9444B0806A01E2A7437E0C"
USER = 60723153007
ORDER_VERSION = 2
ACTION = "TEST_BANK_DISPATCH_DEADLINE_REPAIRED"


def snapshot(db, lock=False):
    suffix = " FOR UPDATE" if lock else ""
    queries = {
        "user": f"SELECT JSON_OBJECT('id',id,'status',status,'sandbox',sandbox,'deleted',is_deleted) FROM nx_user WHERE id={USER}",
        "payout": "SELECT JSON_OBJECT('state',state,'provider',provider_order_id,'provider_status',provider_status,'risk',approved_risk_hash,'version',version,'next_query',CAST(next_query_at AS CHAR)) FROM nx_hdpay_payout WHERE withdrawal_no=" + encoded(ORDER),
        "order": "SELECT JSON_OBJECT('user',user_id,'status',status,'chain',chain,'amount',CAST(amount AS CHAR),'net',CAST(d2_net_receive AS CHAR),'hold',CAST(d2_hold_until AS CHAR),'attempts',chain_broadcast_attempts,'version',d2_version,'deleted',is_deleted,'owner',d2_lifecycle_owner,'previous',d2_previous_status) FROM nx_withdrawal_order WHERE withdrawal_no=" + encoded(ORDER),
        "approval": "SELECT JSON_OBJECT('id',id,'actor',actor_username,'result',result,'at',CAST(created_at AS CHAR)) FROM nx_audit_log WHERE biz_no=" + encoded(ORDER) + " AND action='D2_WITHDRAWAL_REVIEW_APPROVE' ORDER BY id",
        "repair": "SELECT JSON_OBJECT('id',id) FROM nx_audit_log WHERE biz_no=" + encoded(ORDER) + " AND action=" + encoded(ACTION) + " AND result='SUCCESS'",
        "config": "SELECT JSON_OBJECT('key',config_key,'value',config_value) FROM nx_config_item WHERE config_key IN ('H1.rhythm.currentMonth','growth.phase.current_month','growth.phase.month.3.withdrawCooldownDays') AND status=1 AND is_deleted=0 ORDER BY config_key",
    }
    return {key: [json.loads(row) for row in db.execute(query + suffix)]
            for key, query in queries.items()}


def plan(state):
    require(len(state['repair']) <= 1, 'DUPLICATE_REPAIR_AUDIT')
    if state['repair']:
        return {'already_applied': True, 'order': ORDER}
    require(state['user'] == [{'id':USER,'status':'ACTIVE','sandbox':0,'deleted':0}], 'USER_CHANGED')
    require(len(state['order']) == len(state['payout']) == len(state['approval']) == 1, 'SOURCE_NOT_UNIQUE')
    order, payout, approval = (state[key][0] for key in ('order','payout','approval'))
    require(order == {'user':USER,'status':'REVIEW_PASSED','chain':'BANK-VND','amount':'30.000000','net':'29.000000',
                     'hold':None,'attempts':0,'version':ORDER_VERSION,'deleted':0,'owner':None,'previous':None}, 'ORDER_CHANGED')
    require(payout['state'] == 'READY' and payout['provider'] is None and payout['provider_status'] is None
            and payout['version'] == 0 and payout['next_query'] is None
            and isinstance(payout['risk'],str) and len(payout['risk']) == 64, 'PAYOUT_ALREADY_ACTED_ON')
    require(approval == {'id':48715,'actor':'superadmin','result':'SUCCESS','at':'2026-09-16 23:30:24'}, 'APPROVAL_CHANGED')
    require({row['key']:row['value'] for row in state['config']} == {
        'H1.rhythm.currentMonth':'3','growth.phase.current_month':'3','growth.phase.month.3.withdrawCooldownDays':'0'}, 'H1_POLICY_CHANGED')
    return {'already_applied':False,'order':ORDER,'source_sha256':digest(state),'deadline':approval['at'],
            'scheduler_may_dispatch':True,'approval_audit_id':approval['id']}


def apply(db, before, change):
    require(not change['already_applied'] and plan(before) == change, 'PLAN_CHANGED')
    changed = db.execute("UPDATE nx_withdrawal_order SET d2_hold_until=" + encoded(change['deadline'])
               + ",d2_version=d2_version+1,updated_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR) WHERE withdrawal_no="
               + encoded(ORDER) + f" AND user_id={USER} AND status='REVIEW_PASSED' AND chain='BANK-VND' AND d2_version={ORDER_VERSION} AND d2_hold_until IS NULL AND chain_broadcast_attempts=0 AND is_deleted=0; SELECT ROW_COUNT()")
    require(changed == ['1'], 'ORDER_CAS_FAILED')
    detail = json.dumps({'plan':change,'before':before['order'][0],'approvalPreserved':True,'fundsLedgerModified':False},sort_keys=True)
    db.execute("INSERT INTO nx_audit_log(service_name,action,resource_type,resource_id,biz_no,user_id,actor_type,actor_username,result,risk_level,detail_json,retention_policy_months,expire_at) VALUES ("
               + ','.join(map(encoded,('nexion-backend',ACTION,'WITHDRAWAL',ORDER,ORDER,USER,'SYSTEM','ops:test-bank-deadline-repair','SUCCESS','CRITICAL',detail)))
               + ",120,DATE_ADD(NOW(),INTERVAL 120 MONTH))")


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    mode=parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--plan',action='store_true')
    mode.add_argument('--apply',action='store_true')
    parser.add_argument('--expected-backend-sha',required=True)
    parser.add_argument('--expected-source-sha')
    args=parser.parse_args()
    import fcntl
    with open('/srv/nexgrid/cd/lock','r+') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        env=server_context(args.expected_backend_sha)
        command=['docker','exec','-i','nexgrid-mysql','sh','-c','read -r MYSQL_PWD; export MYSQL_PWD; exec mysql -u "$1" --batch --raw --skip-column-names --unbuffered nexion','sh',env['NEXION_DB_USERNAME']]
        db=Mysql(command,env['NEXION_DB_PASSWORD'])
        try:
            db.execute('SET SESSION innodb_lock_wait_timeout=10')
            db.execute('START TRANSACTION' if args.apply else 'START TRANSACTION READ ONLY')
            before=snapshot(db,args.apply)
            change=plan(before)
            if not args.apply or change['already_applied']:
                db.execute('ROLLBACK')
                print(json.dumps(change,sort_keys=True))
                return
            require(args.expected_source_sha == change['source_sha256'],'SOURCE_CHANGED_REPLAN_REQUIRED')
            root=pathlib.Path('/srv/nexgrid/backups')/('bank-deadline-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:8])
            root.mkdir(mode=0o700)
            backup=root/'before.json'
            backup.write_text(json.dumps({'state':before,'plan':change},sort_keys=True)); backup.chmod(0o600)
            apply(db,before,change)
            after=snapshot(db,True)
            expected=before['order'][0] | {'hold':change['deadline'],'version':ORDER_VERSION+1}
            require(after['order'] == [expected] and after['payout'] == before['payout']
                    and after['user'] == before['user'] and after['approval'] == before['approval']
                    and after['config'] == before['config'] and len(after['repair']) == 1,'POSTCHECK_FAILED')
            server_context(args.expected_backend_sha)
            db.execute('COMMIT')
            print(json.dumps({'status':'APPLIED','order':ORDER,'backup':str(root),'scheduler_may_dispatch':True},sort_keys=True))
        finally:
            db.close()


if __name__ == '__main__':
    main()

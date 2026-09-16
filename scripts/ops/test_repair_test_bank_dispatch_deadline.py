import os
import pathlib
import re
import unittest
import uuid
import repair_test_bank_dispatch_deadline as repair


def fixture():
    return {
        'user':[{'id':repair.USER,'status':'ACTIVE','sandbox':0,'deleted':0}],
        'order':[{'user':repair.USER,'status':'REVIEW_PASSED','chain':'BANK-VND','amount':'30.000000','net':'29.000000','hold':None,'attempts':0,'version':repair.ORDER_VERSION,'deleted':0,'owner':None,'previous':None}],
        'payout':[{'state':'READY','provider':None,'provider_status':None,'risk':'a'*64,'version':0,'next_query':None}],
        'approval':[{'id':48715,'actor':'superadmin','result':'SUCCESS','at':'2026-09-16 23:30:24'}],
        'repair':[],
        'config':[{'key':key,'value':value} for key,value in sorted({'H1.rhythm.currentMonth':'3','growth.phase.current_month':'3','growth.phase.month.3.withdrawCooldownDays':'0'}.items())],
    }


class PlanTest(unittest.TestCase):
    def test_only_original_approval_is_restored(self):
        state=fixture(); change=repair.plan(state)
        self.assertEqual(change['deadline'],state['approval'][0]['at'])
        self.assertTrue(change['scheduler_may_dispatch'])
        self.assertEqual(change['source_sha256'],repair.digest(state))

    def test_changes_to_status_money_identity_dispatch_or_approval_block(self):
        for table,changes in [('order',{'status':'FROZEN'}),('order',{'status':'REVIEW_PENDING'}),('order',{'user':2}),
            ('order',{'attempts':1}),('order',{'version':23}),('order',{'hold':'2026-10-17'}),('order',{'amount':'31.000000'}),
            ('payout',{'state':'DISPATCHING'}),('payout',{'provider':123}),('payout',{'provider_status':3}),
            ('payout',{'risk':None}),('payout',{'version':1}),('approval',{'result':'FAILED'}),('approval',{'id':1}),
            ('user',{'status':'SUSPENDED'}),('user',{'sandbox':1})]:
            with self.subTest(table=table,changes=changes):
                state=fixture(); state[table][0].update(changes)
                with self.assertRaises(ValueError): repair.plan(state)

    def test_h1_missing_evidence_or_changed_risk_cannot_reuse_plan(self):
        for key in ('user','order','payout','approval','config'):
            state=fixture();state[key]=[]
            with self.assertRaises(ValueError):repair.plan(state)
        state=fixture();state['config'][-1]['value']='1'
        with self.assertRaises(ValueError):repair.plan(state)
        state=fixture();old=repair.plan(state);state['payout'][0]['risk']='b'*64
        self.assertNotEqual(old['source_sha256'],repair.plan(state)['source_sha256'])

    def test_replay_never_resets_dispatched_order(self):
        state=fixture();state['repair']=[{'id':9}];state['payout'][0].update(state='PAID',provider=123)
        self.assertEqual(repair.plan(state),{'already_applied':True,'order':repair.ORDER})


@unittest.skipUnless(os.environ.get('NEXION_DEADLINE_REPAIR_IT')=='true','isolated MySQL opt-in required')
class MySqlTest(unittest.TestCase):
    def connect(self,schema):
        if schema!='mysql' and not re.fullmatch('nexion_deadline_it_[a-f0-9]{32}',schema):raise ValueError('isolated schema required')
        return repair.Mysql([os.environ['NEXION_ISOLATED_MYSQL_CLIENT'],'--no-defaults','--host=127.0.0.1','--port=13306','--user=root','--batch','--raw','--skip-column-names','--unbuffered',schema])

    def setUp(self):
        self.schema='nexion_deadline_it_'+uuid.uuid4().hex
        self.admin=self.connect('mysql');self.assertEqual(self.admin.execute('SELECT @@port'),['13306'])
        self.admin.execute('CREATE DATABASE '+self.schema);self.db=self.connect(self.schema)
        self.db.execute('''CREATE TABLE nx_user(id BIGINT PRIMARY KEY,status VARCHAR(32),sandbox INT,is_deleted INT);
CREATE TABLE nx_withdrawal_order(withdrawal_no VARCHAR(96) PRIMARY KEY,user_id BIGINT,status VARCHAR(32),chain VARCHAR(32),amount DECIMAL(18,6),d2_net_receive DECIMAL(18,6),d2_hold_until DATETIME,chain_broadcast_attempts INT,d2_version BIGINT,is_deleted INT,d2_lifecycle_owner VARCHAR(64),d2_previous_status VARCHAR(32),updated_at DATETIME);
CREATE TABLE nx_hdpay_payout(withdrawal_no VARCHAR(96) PRIMARY KEY,state VARCHAR(32),provider_order_id BIGINT,provider_status INT,approved_risk_hash CHAR(64),version BIGINT,next_query_at DATETIME);
CREATE TABLE nx_config_item(config_key VARCHAR(128),config_value VARCHAR(64),status INT,is_deleted INT)''')
        schema=(pathlib.Path(__file__).resolve().parents[1]/'schema.sql').read_text(encoding='utf-8')
        self.db.execute(re.search(r'CREATE TABLE IF NOT EXISTS nx_audit_log\s*\([\s\S]*?;',schema).group(0))
        q=repair.encoded
        self.db.execute(f"INSERT INTO nx_user VALUES({repair.USER},'ACTIVE',0,0)")
        self.db.execute(f"INSERT INTO nx_withdrawal_order VALUES({q(repair.ORDER)},{repair.USER},'REVIEW_PASSED','BANK-VND',30,29,NULL,0,{repair.ORDER_VERSION},0,NULL,NULL,NOW())")
        self.db.execute(f"INSERT INTO nx_hdpay_payout VALUES({q(repair.ORDER)},'READY',NULL,NULL,{q('a'*64)},0,NULL)")
        self.db.execute(f"INSERT INTO nx_audit_log(id,service_name,resource_type,action,biz_no,actor_type,actor_username,result,risk_level,created_at) VALUES(48715,'nexion-backend','WITHDRAWAL','D2_WITHDRAWAL_REVIEW_APPROVE',{q(repair.ORDER)},'ADMIN','superadmin','SUCCESS','HIGH','2026-09-16 23:30:24')")
        for row in fixture()['config']:self.db.execute(f"INSERT INTO nx_config_item VALUES({q(row['key'])},{q(row['value'])},1,0)")

    def tearDown(self):
        self.db.close();self.admin.execute('DROP DATABASE '+self.schema);self.admin.close()

    def test_apply_is_atomic_and_replay_safe(self):
        before=repair.snapshot(self.db);change=repair.plan(before)
        self.db.execute('START TRANSACTION');repair.apply(self.db,before,change);self.db.execute('ROLLBACK')
        self.assertEqual(repair.snapshot(self.db),before)
        self.db.execute('START TRANSACTION');before=repair.snapshot(self.db,True);repair.apply(self.db,before,change);self.db.execute('COMMIT')
        after=repair.snapshot(self.db)
        self.assertEqual(after['order'][0]['hold'],change['deadline']);self.assertEqual(after['order'][0]['version'],repair.ORDER_VERSION+1)
        self.assertEqual(after['payout'],before['payout']);self.assertTrue(repair.plan(after)['already_applied'])
        self.assertEqual(len(after['repair']),1)

    def test_stale_apply_rolls_back_without_audit(self):
        before=repair.snapshot(self.db);change=repair.plan(before)
        self.db.execute("UPDATE nx_withdrawal_order SET status='FROZEN'")
        self.db.execute('START TRANSACTION')
        with self.assertRaisesRegex(ValueError,'ORDER_CAS_FAILED'):repair.apply(self.db,before,change)
        self.db.execute('ROLLBACK');self.assertEqual(repair.snapshot(self.db)['repair'],[])

    def test_locked_plan_prevents_concurrent_h1_change(self):
        self.db.execute('START TRANSACTION');repair.snapshot(self.db,True)
        contender=self.connect(self.schema)
        try:
            contender.execute('SET SESSION innodb_lock_wait_timeout=1')
            with self.assertRaises(RuntimeError):
                contender.execute("UPDATE nx_config_item SET config_value='1' WHERE config_key='growth.phase.month.3.withdrawCooldownDays'")
            contender.errors.seek(0)
            self.assertIn('Lock wait timeout',contender.errors.read())
        finally:
            contender.close();self.db.execute('ROLLBACK')
        self.assertEqual(sorted(repair.snapshot(self.db)['config'],key=lambda row:row['key']),fixture()['config'])


if __name__=='__main__':unittest.main()

import json
import hashlib
import os
import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import rearm_test_bank_ip_rejection as repair
import test_reverse_test_bank_premature_reserve as reserve_tests


def fixture():
    state = reserve_tests.fixture()
    state['reserves'].append(reserve_tests.reversal())
    state['order'][0].update(status='PROCESSING', version=7, attempts=1, hold='2026-09-17 00:55:26')
    state['payout'][0].update(state='DISPATCHING', version=300, next_query='2026-09-17 04:00:00')
    state['approval'].append({'id': 48733, 'actor': 'superadmin', 'result': 'SUCCESS'})
    state['dispatch'] = [{'id': 48734}]
    return state


class PlanTest(unittest.TestCase):
    def test_only_original_rejected_unsettled_order_is_eligible(self):
        repair.validate(fixture())
        for key, changes in [('wallet', {'pending': '0.000000'}), ('user', {'sandbox': 1}),
            ('order', {'status': 'REVIEW_PASSED'}), ('order', {'version': 8}), ('order', {'attempts': 2}),
            ('order', {'amount': '31.000000'}), ('payout', {'provider': 123}), ('payout', {'state': 'PAID'}),
            ('approval', {'id': 123}), ('reserves', {'voucher': 'other'})]:
            state = fixture(); state[key][-1].update(changes)
            with self.subTest(key=key, changes=changes), self.assertRaises(ValueError): repair.validate(state)
        for key in ('callback', 'settlement'):
            state = fixture(); state[key] = [{'event': 'x'}]
            with self.subTest(key=key), self.assertRaises(ValueError): repair.validate(state)
        for key in ('user', 'wallet', 'order', 'payout', 'approval', 'reserves', 'dispatch'):
            state = fixture(); state[key] = []
            with self.subTest(key=key), self.assertRaises(ValueError): repair.validate(state)

    def test_current_provider_absence_and_exact_prior_rejection_are_both_required(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(repair, 'DIAG', pathlib.Path(folder)):
            env = {'NEXION_HDPAY_MERCHANT_ID': '2094724651524763649'}
            hashes = {name: hashlib.sha256(name.encode()).hexdigest() for name in repair.SOURCE_HASHES}
            for name in hashes: (pathlib.Path(folder) / name).write_bytes(name.encode())
            source_patch = patch.object(repair, 'SOURCE_HASHES', hashes); source_patch.start(); self.addCleanup(source_patch.stop)
            receipt = pathlib.Path(folder) / 'user-requested-create-20260917-01.response.json'
            receipt.write_text(json.dumps({'exitCode': 0, 'output': 'DIRECT_CREATE_HTTP 200\nDIRECT_CREATE_RESPONSE {"code":408,"msg":"该ip禁止访问"}\n'}))
            def result(body, rc=0):
                return subprocess.CompletedProcess([], rc, 'JAVA_QUERY_HTTP 200\nJAVA_QUERY_RESULT ' + json.dumps(body) + '\n', '')
            absent = {'code': '500', 'msg': '代付订单不存在'}
            with patch.object(repair.subprocess, 'run', return_value=result(absent)) as run:
                self.assertEqual(repair.provider_absence(env)['previousCreateCode'], 408)
                self.assertNotIn('publicWithdrawal', str(run.call_args))
            for body in ({'code': '200', 'data': {'id': 1}}, {'code': '500', 'msg': 'unknown'}, {'code': 500, 'msg': '代付订单不存在'}):
                with patch.object(repair.subprocess, 'run', return_value=result(body)), self.assertRaises(ValueError):
                    repair.provider_absence(env)
            with patch.object(repair.subprocess, 'run', return_value=result(absent, 1)), self.assertRaises(ValueError):
                repair.provider_absence(env)
            with patch.object(repair.subprocess, 'run') as run, self.assertRaisesRegex(ValueError, 'MERCHANT_CHANGED'):
                repair.provider_absence({'NEXION_HDPAY_MERCHANT_ID': 'other'})
            run.assert_not_called()
            source = pathlib.Path(folder) / 'QueryHdPayReadOnly.java'; source.write_text('drift')
            with patch.object(repair.subprocess, 'run') as run, self.assertRaisesRegex(ValueError, 'SOURCE_CHANGED'):
                repair.provider_absence(env)
            run.assert_not_called(); source.write_bytes(b'QueryHdPayReadOnly.java')
            receipt.write_text(json.dumps({'exitCode': 0, 'output': 'DIRECT_CREATE_HTTP 200\nDIRECT_CREATE_RESPONSE {"code":200}'}))
            with patch.object(repair.subprocess, 'run') as run, self.assertRaises(ValueError): repair.provider_absence(env)
            run.assert_not_called()


@unittest.skipUnless(os.environ.get('NEXION_BANK_REARM_IT') == 'true', 'isolated MySQL opt-in required')
class MySqlTest(unittest.TestCase):
    def setUp(self):
        self.base = reserve_tests.MySqlTest(); self.base.setUp(); self.db = self.base.db
        self.db.execute('ALTER TABLE nx_withdrawal_order ADD d2_freeze_period VARCHAR(32), ADD failure_reason VARCHAR(512), ADD d5_provider_cid VARCHAR(96)')
        self.db.execute('ALTER TABLE nx_hdpay_payout ADD last_error VARCHAR(512), ADD updated_at DATETIME')
        self.db.execute("UPDATE nx_withdrawal_order SET status='PROCESSING',d2_version=7,chain_broadcast_attempts=1,d2_hold_until='2026-09-17 00:55:26'")
        self.db.execute("UPDATE nx_hdpay_payout SET state='DISPATCHING',version=300,next_query_at='2026-09-17 04:00:00',last_error='BANK_PAYOUT_QUERY_UNAVAILABLE'")
        q = repair.encoded; rev = reserve_tests.reversal()
        self.db.execute('INSERT INTO nx_treasury_reserve_ledger(id,reserve_no,voucher_no,direction,amount_usd,idempotency_key) VALUES(135,'
                        + ','.join(map(q, (rev['reserve'], rev['voucher'], 'IN', '30', rev['evidence']))) + ')')
        for aid, action in [(48733, 'D2_WITHDRAWAL_REVIEW_APPROVE'), (48734, 'BANK_PAYOUT_DISPATCH_INTENT')]:
            self.db.execute(f"INSERT INTO nx_audit_log(id,service_name,resource_type,action,biz_no,actor_type,actor_username,result,risk_level) VALUES({aid},'nexion-backend','WITHDRAWAL',{q(action)},{q(repair.ORDER)},'ADMIN','superadmin','SUCCESS','HIGH')")

    def tearDown(self): self.base.tearDown()

    def test_atomic_rearm_rollback_commit_preserves_money_and_requires_manual_approval(self):
        before = repair.snapshot(self.db); self.assertEqual(before, fixture())
        self.db.execute('START TRANSACTION'); repair.apply(self.db, before, {}, '/private'); self.db.execute('ROLLBACK')
        self.assertEqual(repair.snapshot(self.db), before)
        self.db.execute('START TRANSACTION'); after = repair.apply(self.db, before, {}, '/private'); self.db.execute('COMMIT')
        self.assertEqual(after['order'][0]['status'], 'REVIEW_PENDING'); self.assertIsNone(after['order'][0]['hold'])
        self.assertEqual(after['payout'][0]['state'], 'READY'); self.assertIsNone(after['payout'][0]['risk'])
        self.assertEqual(after['wallet'], before['wallet']); self.assertEqual(after['reserves'], before['reserves'])
        self.assertEqual(after['dispatch'], before['dispatch']); self.assertEqual(after['approval'], before['approval'])
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM nx_audit_log WHERE action=' + repair.encoded(repair.ACTION)), ['1'])
        self.db.execute('START TRANSACTION')
        with self.assertRaises(ValueError): repair.apply(self.db, before, {}, '/private')
        self.db.execute('ROLLBACK'); self.assertEqual(repair.snapshot(self.db), after)

    def test_stale_callback_or_provider_cid_prevents_rearm(self):
        before = repair.snapshot(self.db)
        self.db.execute("UPDATE nx_withdrawal_order SET d5_provider_cid='provider-order'")
        self.db.execute('START TRANSACTION')
        with self.assertRaisesRegex(ValueError, 'ORDER_CAS_FAILED'): repair.apply(self.db, before, {}, '/private')
        self.db.execute('ROLLBACK'); self.assertEqual(repair.snapshot(self.db), before)
        self.db.execute('UPDATE nx_withdrawal_order SET d5_provider_cid=NULL')
        self.db.execute("INSERT INTO nx_hdpay_payout_callback VALUES('event'," + repair.encoded(repair.ORDER) + ')')
        self.db.execute('START TRANSACTION')
        with self.assertRaisesRegex(ValueError, 'LOCKED_STATE_CHANGED'): repair.apply(self.db, before, {}, '/private')
        self.db.execute('ROLLBACK')
        self.assertEqual(repair.snapshot(self.db)['wallet'], before['wallet'])

    def assert_failure_rolls_back(self, table):
        before = repair.snapshot(self.db)
        operation = 'INSERT' if table == 'nx_audit_log' else 'UPDATE'
        self.db.execute(f"CREATE TRIGGER forced_failure BEFORE {operation} ON {table} FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test write failure'")
        self.db.execute('START TRANSACTION')
        with self.assertRaises(RuntimeError): repair.apply(self.db, before, {}, '/private')
        self.db.close(); self.db = self.base.db = self.base.base.db = self.base.base.connect(self.base.base.schema)
        self.assertEqual(repair.snapshot(self.db), before)

    def test_audit_failure_rolls_back_both_state_updates(self): self.assert_failure_rolls_back('nx_audit_log')

    def test_payout_update_failure_rolls_back_first_update(self): self.assert_failure_rolls_back('nx_hdpay_payout')

    def test_row_lock_prevents_concurrent_dispatch_or_callback_state_update(self):
        self.db.execute('START TRANSACTION'); repair.snapshot(self.db, True)
        contender = self.base.base.connect(self.base.base.schema)
        try:
            contender.execute('SET SESSION innodb_lock_wait_timeout=1')
            with self.assertRaises(RuntimeError): contender.execute("UPDATE nx_hdpay_payout SET provider_order_id=123")
            contender.errors.seek(0); self.assertIn('Lock wait timeout', contender.errors.read())
        finally: contender.close(); self.db.execute('ROLLBACK')


if __name__ == '__main__': unittest.main()

"""Contained real-filesystem recovery tests: no HOST, MySQL, network or service calls."""
import contextlib
import gzip
import json
import os
from pathlib import Path
import stat
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import database_migrations as m
import recover_earnings_trigger_1419 as r


@contextlib.contextmanager
def fixture():
    with tempfile.TemporaryDirectory() as temp, contextlib.ExitStack() as stack:
        base = Path(temp).resolve()
        cd, install, folder = base/'cd', base/'install', base/'backup'
        root, staged = cd/'migrations', cd/'backend/240-7efbe92a339e'
        old_target = cd/'backend/239-2aabbc9fce55/app'
        for path in (root, install, folder, staged/'app', old_target, base/'updates'):
            path.mkdir(parents=True, exist_ok=True)
        paths = dict(CD=cd, ROOT=root, INSTALL=install, FOLDER=folder, ATTEMPT=root/'attempt',
            STAGED=staged, OLD_TARGET=old_target, RUNNER_STAGE=base/'updates/database_migrations.py',
            NGINX=base/'nginx', DROPIN=base/'dropin', PERMIT=base/'permit', GUARD=base/'guard')
        for name, value in paths.items():
            stack.enter_context(patch.object(r, name, value))
        # Windows lacks POSIX uid/mode and unprivileged symlink semantics; these are mocked. Reads,
        # hashes, backup inflate, atomic writes, journaling and renames are actual.
        stack.enter_context(patch.object(r, 'trusted'))
        stack.enter_context(patch.object(r, 'read', side_effect=lambda path, private=False: path.read_bytes()))
        stack.enter_context(patch.object(r, 'command', return_value=b'inactive'))
        current = cd/'backend/current'
        actual_resolve, actual_symlink = Path.resolve, Path.is_symlink
        stack.enter_context(patch.object(Path, 'resolve', lambda p, *a, **kw:
            old_target if p == current else actual_resolve(p, *a, **kw)))
        stack.enter_context(patch.object(Path, 'is_symlink', lambda p:
            True if p == current else actual_symlink(p)))
        (root/'START_BLOCKED').write_bytes(r.encoded({'reason':'DATABASE_MIGRATION_HOLD'}))
        r.GUARD.write_bytes(r.GUARD_BYTES)
        r.NGINX.write_bytes(b'fixture nginx')
        r.DROPIN.write_bytes(b'fixture dropin')
        jar = b'qualified backend240 jar fixture'
        (staged/'backend.jar').write_bytes(jar)
        (staged/'app/nexion-backend.jar').write_bytes(jar)
        stack.enter_context(patch.object(r, 'JAR_HASH', r.digest(jar)))
        stack.enter_context(patch.object(r, 'JAR_BYTES', len(jar)))
        directory = Path(__file__).resolve().parents[2]/'scripts/migrations'
        data = {name:(directory/name).read_bytes().replace(b'\r\n',b'\n') for name in r.PENDING}
        for name in (r.FIRST,r.FAILED):
            (folder/name).write_bytes(data[name])
        error=b'ERROR 1419 (HY000) at line 37 fixture only\n'
        (folder/(r.FAILED+'.stderr')).write_bytes(error)
        (folder/(r.FAILED+'.stdout')).write_bytes(b'fixture prefix selects\n')
        stack.enter_context(patch.object(r,'ERROR_HASH',r.digest(error)))
        expanded=b'-- fixture backup\n'+b'x'*20000+b'\n-- Dump completed on fixture\n'
        (folder/'database.sql.gz').write_bytes(gzip.compress(expanded))
        backup=m.verify_backup(folder/'database.sql.gz')
        stack.enter_context(patch.object(r,'BACKUP_HASH',backup['sha256']))
        stack.enter_context(patch.object(r,'BACKUP_EXPANDED',len(expanded)))
        before={'version':1,'database':'nexion','scripts':{}}
        state={'version':1,'database':'nexion','scripts':{r.FIRST:{'sha256':r.FIRST_HASH,
            'status':'APPLIED','sha':r.SOURCE,'backup':str(folder/'database.sql.gz')}}}
        (folder/'state-before.json').write_bytes(r.encoded(before))
        (root/'state.json').write_bytes(r.encoded(state))
        active={'version':1,'database':'nexion','sha':r.SOURCE,'phase':'SQL_FAILED',
            'current':r.FAILED,'error':'MIGRATION_SQL_FAILED: '+r.FAILED,'folder':str(folder),
            'pending':r.PENDING,'completed':[r.FIRST],'backup':backup,'previous_sql_applied':None}
        for path in (root/'active.json',folder/'receipt.json'):
            path.write_bytes(r.encoded(active))
        (cd/'state.json').write_bytes(r.encoded({'backend':{'build':239,'port':8110,'sha':r.OLD_SOURCE},
            'nginx_sha256':r.digest(r.NGINX.read_bytes()),'unrelated':{'preserve':True}}))
        names=json.loads((Path(__file__).parent/'runtime-lock.json').read_text())['files']
        lock={'version':1,'files':{}}
        for name in names:
            raw=('fixture '+name).encode()
            (install/name).write_bytes(raw)
            lock['files'][name]=r.digest(raw)
        stack.enter_context(patch.object(r,'OLD_RUNNER',lock['files']['database_migrations.py']))
        (install/'runtime-lock.json').write_bytes(r.encoded(lock))
        config={'trusted_files':dict(lock['files']),'trusted_external_files':{},'unrelated':{'keep':'exact'}}
        (install/'config.json').write_bytes(r.encoded(config))
        stack.enter_context(patch.object(r,'OLD_LOCK',r.digest(r.encoded(lock))))
        stack.enter_context(patch.object(r,'OLD_CONFIG',r.digest(r.encoded(config))))
        # Actual approved runner bytes are independently pinned and compiled.
        r.RUNNER_STAGE.write_bytes((Path(__file__).parent/'database_migrations.py').read_bytes().replace(b'\r\n',b'\n'))
        original_runner=r.runner()
        original_runner.GUARD=r.GUARD
        context={'version':'8.4.11','database':'nexion','principal':'app@%','log_bin':1,'trust':0,
                 'mode':'STRICT_TRANS_TABLES','charset':'utf8mb4','collation':'utf8mb4_0900_ai_ci'}
        columns=[{'table':'nx_user_wallet','name':name,'type':'decimal(30,6)','nullable':'NO','default':'0.000000'}
                 for name in ('earnings_usdt_debited','earnings_nex_debited')]
        columns += [{'table':'nx_earnings_release_entry','name':'source_debit_baseline','type':'decimal(30,6)',
                     'nullable':'YES','default':None},
                    {'table':'nx_earnings_release_entry','name':'source_wallet_id','type':'bigint','nullable':'YES','default':None},
                    {'table':'nx_earnings_release_entry','name':'recovered_amount','type':'decimal(24,6)',
                     'nullable':'NO','default':'0.000000'}]
        def probe(account,password,sql,**kw):
            if sql=='SHOW GRANTS FOR CURRENT_USER();':
                rows=['GRANT ALL PRIVILEGES ON '+('*.*' if account=='root' else '`nexion`.*')+' TO fixture']
                return rows+(['GRANT SET_ANY_DEFINER ON *.* TO fixture'] if account=='root' else [])
            if 'information_schema.COLUMNS' in sql: return columns
            if 'CHECK_CONSTRAINTS' in sql:
                return [{'name':'chk_earnings_source_recovery','enforced':'YES','hash':r.CHECK_HASH}]
            if sql==original_runner.TRIGGER_SQL: return []
            if 'information_schema.TABLES' in sql: return 0
            raise AssertionError('unexpected SQL')
        original_runner.credentials=lambda:('app','fixture','fixture-root')
        original_runner.scoped_account=lambda *_:None
        original_runner.server_context=lambda account,*_:dict(context,principal='root@localhost' if account=='root' else 'app@%')
        original_runner.probe=probe
        stack.enter_context(patch.object(r,'runner',return_value=original_runner))
        # POSIX private gzip mode is separately enforced in production.
        original_stat=Path.stat
        def path_stat(path,*a,**kw):
            value=original_stat(path,*a,**kw)
            if path==folder/'database.sql.gz': return SimpleNamespace(st_mode=stat.S_IFREG|0o600)
            return value
        stack.enter_context(patch.object(Path,'stat',path_stat))
        yield SimpleNamespace(base=base,root=root,folder=folder,cd=cd,install=install,
            staged=staged,active=active,state=state,runner=original_runner,columns=columns,data=data)


def hashes(base):
    return {str(p.relative_to(base)):r.digest(p.read_bytes()) for p in base.rglob('*') if p.is_file() and not p.is_symlink()}


class RecoveryTests(unittest.TestCase):
    def test_new_file_is_private_at_initial_exclusive_creation_before_any_write(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'private-config.1419-new'
            actual=os.open
            captured=[]
            def opened(p,flags,mode=0o777,*a,**kw):
                descriptor=actual(p,flags,mode,*a,**kw)
                captured.append((mode,flags,os.fstat(descriptor).st_size))
                return descriptor
            with patch.object(r.os,'open',side_effect=opened):r.new_file(path,b'fixture private config',0o640)
            self.assertTrue(captured)
            self.assertEqual(captured[0][0],0o600)
            self.assertEqual(captured[0][2],0)
            self.assertEqual(captured[0][1] & (os.O_CREAT|os.O_EXCL|os.O_WRONLY),os.O_CREAT|os.O_EXCL|os.O_WRONLY)
            self.assertEqual(path.read_bytes(),b'fixture private config')

    def test_missing_action_time_permission_is_zero_write(self):
        with fixture() as f:
            before=hashes(f.base)
            with self.assertRaisesRegex(r.RecoveryError,'APPROVAL_REQUIRED'): r.arm(False)
            self.assertEqual(hashes(f.base),before)

    def test_arm_release_preserves_original_and_defers_every_sql_and_jar(self):
        with fixture() as f:
            original=hashes(f.folder)
            before_state=(f.root/'state.json').read_bytes()
            r.arm(True)
            r.action_receipt('arm',0)
            self.assertEqual(hashes(f.folder),original)
            self.assertFalse((f.root/'active.json').exists())
            self.assertTrue((r.ATTEMPT/'staging240').exists())
            self.assertFalse(f.staged.exists())
            self.assertEqual(json.loads((f.install/'config.json').read_bytes())[r.FLAG],True)
            self.assertTrue((f.cd/'HALTED.json').exists())
            r.release()
            self.assertFalse((f.cd/'HALTED.json').exists())
            self.assertTrue((f.root/'START_BLOCKED').exists())
            self.assertFalse(r.PERMIT.exists())
            self.assertEqual((f.root/'state.json').read_bytes(),before_state)
            self.assertEqual(hashes(f.folder),original)
            pending=f.runner.pending_scripts(f.state,f.data)
            self.assertEqual([name for name,_ in pending],r.PENDING[1:])
            self.assertEqual(pending[0][0],r.FAILED)

    def test_original_backup_or_receipt_drift_rejects_before_any_write(self):
        for name in ('database.sql.gz','receipt.json','state-before.json',r.FAILED+'.stderr',r.FAILED):
            with self.subTest(name=name),fixture() as f:
                (f.folder/name).write_bytes(b'corrupt fixture')
                before=hashes(f.base)
                with self.assertRaises(Exception): r.arm(True)
                self.assertEqual(hashes(f.base),before)

    def test_invalid_partial_columns_reject_before_any_write(self):
        for key,value in (('type','decimal(18,6)'),('nullable','YES'),('default','1.000000')):
            with self.subTest(key=key),fixture() as f:
                f.columns[0][key]=value
                before=hashes(f.base)
                with self.assertRaisesRegex(r.RecoveryError,'PARTIAL_COLUMNS'): r.arm(True)
                self.assertEqual(hashes(f.base),before)

    def test_each_durable_phase_failure_keeps_both_holds_and_original_then_file_only_rollback(self):
        for target in ('ARMING','SNAPSHOT_VERIFIED','RUNTIME_VERIFIED','STAGING_QUARANTINED','ARMED'):
            with self.subTest(target=target),fixture() as f:
                before=hashes(f.folder)
                real=r.phase
                def phase(value,**fields):
                    if value==target: raise OSError('fixture private detail must not leak')
                    return real(value,**fields)
                with patch.object(r,'phase',side_effect=phase):
                    with self.assertRaises(OSError): r.arm(True)
                self.assertTrue((f.cd/'HALTED.json').exists())
                self.assertTrue((f.root/'START_BLOCKED').exists())
                self.assertFalse(r.PERMIT.exists())
                self.assertEqual(hashes(f.folder),before)
                r.rollback()
                self.assertTrue((f.root/'active.json').exists())
                self.assertTrue(f.staged.exists())
                r.closure()
                self.assertTrue((f.cd/'HALTED.json').exists())

    def test_each_runtime_partial_write_failure_can_restore_exact_bytes_while_held(self):
        for name in r.MUTABLE:
            for after in (False,True):
                with self.subTest(name=name,after=after),fixture() as f:
                    originals={n:(f.install/n).read_bytes() for n in r.MUTABLE}
                    actual=r.atomic
                    def atomic(path,data,*a,**kw):
                        if path==f.install/name:
                            if after: actual(path,data,*a,**kw)
                            raise OSError('fixture')
                        return actual(path,data,*a,**kw)
                    with patch.object(r,'atomic',side_effect=atomic):
                        with self.assertRaises(OSError):r.arm(True)
                    r.rollback()
                    self.assertEqual({n:(f.install/n).read_bytes() for n in r.MUTABLE},originals)
                    self.assertTrue((f.cd/'HALTED.json').exists())
                    self.assertTrue((f.root/'START_BLOCKED').exists())

    def test_repeat_arm_or_foreign_halt_cannot_overwrite_or_release_evidence(self):
        with fixture() as f:
            r.arm(True)
            r.action_receipt('arm',0)
            before=hashes(f.base)
            with self.assertRaises(r.RecoveryError):r.arm(True)
            r.action_receipt('arm',1,RuntimeError('fixture'))
            self.assertEqual(hashes(f.base),before)
            (f.cd/'HALTED.json').write_bytes(r.encoded({'reason':'OTHER_INCIDENT'}))
            before=hashes(f.base)
            with self.assertRaisesRegex(r.RecoveryError,'FOREIGN_HALT'):r.release()
            self.assertEqual(hashes(f.base),before)

    def test_release_rechecks_every_archived_pin_and_state_before_clearing_its_halt(self):
        for name in ('failed-active.json','migration-state.json','release-state.json','config.json.before'):
            with self.subTest(name=name),fixture() as f:
                r.arm(True)
                (r.ATTEMPT/name).write_bytes(b'corrupt fixture')
                with self.assertRaises(Exception):r.release()
                self.assertTrue((f.cd/'HALTED.json').exists())
                self.assertTrue((f.root/'START_BLOCKED').exists())

    def test_read_trust_check_happens_before_bytes_and_invalid_file_is_never_read(self):
        with patch.object(r,'trusted',side_effect=r.RecoveryError('UNTRUSTED_PATH')):
            with patch.object(Path,'read_bytes') as read:
                with self.assertRaisesRegex(r.RecoveryError,'UNTRUSTED_PATH'):r.read(Path('/fixture'))
                read.assert_not_called()

    def test_unknown_exception_receipt_exposes_only_type_and_keeps_first_result(self):
        with fixture() as f:
            r.arm(True)
            r.action_receipt('arm',1,RuntimeError('fixture private data'))
            raw=(r.ATTEMPT/'arm-result.json').read_text()
            self.assertNotIn('fixture private data',raw)
            self.assertIn('RuntimeError',raw)
            before=(r.ATTEMPT/'arm-result.json').read_bytes()
            r.action_receipt('arm',0)
            self.assertEqual((r.ATTEMPT/'arm-result.json').read_bytes(),before)

    def test_interrupted_receipt_completes_missing_logs_without_replacing_first_failure(self):
        with fixture():
            r.arm(True)
            actual=r.new_file
            def write(path,data,*a,**kw):
                if path.name=='arm.stderr': raise OSError('fixture disk failure')
                return actual(path,data,*a,**kw)
            with patch.object(r,'new_file',side_effect=write):
                with self.assertRaises(OSError):r.action_receipt('arm',1,RuntimeError('fixture'))
            first=(r.ATTEMPT/'arm-result.json').read_bytes()
            r.action_receipt('arm',0)
            self.assertEqual((r.ATTEMPT/'arm-result.json').read_bytes(),first)
            self.assertEqual((r.ATTEMPT/'arm.stderr').read_bytes(),b'RuntimeError\n')
            self.assertEqual((r.ATTEMPT/'arm.nativeexit').read_bytes(),b'1\n')

    def test_check_trigger_suffix_context_and_grant_drift_reject_before_write(self):
        for kind in ('check','trigger','suffix','context','grant','definer'):
            with self.subTest(kind=kind),fixture() as f:
                actual=f.runner.probe
                def probe(account,password,sql,**kw):
                    if kind=='check' and 'CHECK_CONSTRAINTS' in sql:return []
                    if kind=='trigger' and sql==f.runner.TRIGGER_SQL:return [{'fixture':'unexpected'}]
                    if kind=='suffix' and 'information_schema.TABLES' in sql:return 1
                    if kind=='grant' and sql=='SHOW GRANTS FOR CURRENT_USER();':return []
                    if kind=='definer' and account=='root' and sql=='SHOW GRANTS FOR CURRENT_USER();':
                        return ['GRANT ALL PRIVILEGES ON *.* TO fixture']
                    return actual(account,password,sql,**kw)
                f.runner.probe=probe
                if kind=='context':
                    actual_context=f.runner.server_context
                    f.runner.server_context=lambda *a:dict(actual_context(*a),trust=1)
                before=hashes(f.base)
                with self.assertRaises((r.RecoveryError,f.runner.MigrationError)):r.arm(True)
                self.assertEqual(hashes(f.base),before)

    def test_inactive_guard_live_state_and_staging_are_required_before_write(self):
        for kind in ('service','guard','state','jar','extra','permit'):
            with self.subTest(kind=kind),fixture() as f:
                if kind=='service':r.command.return_value=b'active'
                if kind=='guard':r.GUARD.write_bytes(b'fixture wrong guard')
                if kind=='state':(f.cd/'state.json').write_bytes(r.encoded({'backend':{}}))
                if kind=='jar':(f.staged/'backend.jar').write_bytes(b'fixture wrong jar')
                if kind=='extra':(f.staged/'extra').write_bytes(b'fixture extra')
                if kind=='permit':r.PERMIT.write_bytes(b'fixture unexpected permit')
                before=hashes(f.base)
                with self.assertRaises(r.RecoveryError):r.arm(True)
                self.assertEqual(hashes(f.base),before)

    def test_normal_runner_backup_failure_preserves_preexisting_hold_and_no_hold_behavior(self):
        for held in (True,False):
            with self.subTest(preexistingHold=held),tempfile.TemporaryDirectory() as temp,contextlib.ExitStack() as stack:
                root=Path(temp)/'migrations'
                root.mkdir()
                backups=Path(temp)/'backups'
                backups.mkdir()
                guard=Path(temp)/'guard'
                guard.write_text(m.GUARD_TEXT)
                m_state={'version':1,'database':'nexion','scripts':{}}
                (root/'state.json').write_bytes(r.encoded(m_state))
                if held:(root/'START_BLOCKED').write_bytes(r.encoded({'reason':'DATABASE_MIGRATION_HOLD'}))
                stack.enter_context(patch.object(m,'ROOT',root))
                stack.enter_context(patch.object(m,'BACKUPS',backups))
                stack.enter_context(patch.object(m,'GUARD',guard))
                stack.enter_context(patch.object(m,'trusted'))
                stack.enter_context(patch.object(m,'download_catalog',return_value={'fixture.sql':b'SELECT 1;\n'}))
                stack.enter_context(patch.object(m,'validate_sql'))
                stack.enter_context(patch.object(m,'credentials',return_value=('app','fixture','root-fixture')))
                stack.enter_context(patch.object(m,'scoped_account'))
                stack.enter_context(patch.object(m,'migration_preflight',return_value={'root_install':False}))
                stack.enter_context(patch.object(m,'stop_backend'))
                stack.enter_context(patch.object(m,'backup_database',side_effect=m.MigrationError('BACKUP_FIXTURE_FAILED')))
                start=stack.enter_context(patch.object(m,'start_previous'))
                remove=stack.enter_context(patch.object(m,'remove_journal'))
                with self.assertRaisesRegex(m.MigrationError,'BACKUP_FIXTURE_FAILED'):m.apply(r.SOURCE)
                if held:
                    start.assert_not_called()
                    remove.assert_not_called()
                    self.assertTrue((root/'START_BLOCKED').exists())
                    self.assertEqual(json.loads((root/'active.json').read_bytes())['phase'],'BACKUP_FAILED')
                else:
                    start.assert_called_once_with()
                    remove.assert_called_once_with()

    def test_release_unlink_then_sync_failure_restores_broker_hold(self):
        with fixture() as f:
            r.arm(True)
            actual=r.sync
            def sync(path):
                if path==f.cd and not (f.cd/'HALTED.json').exists():raise OSError('fixture sync failed after unlink')
                return actual(path)
            with patch.object(r,'sync',side_effect=sync):
                with self.assertRaises(OSError):r.release()
            self.assertEqual(json.loads((f.cd/'HALTED.json').read_bytes()),r.HALT)
            self.assertTrue((f.root/'START_BLOCKED').exists())

    def test_rollback_rejects_third_runtime_bytes_before_any_restore(self):
        for name in r.MUTABLE:
            with self.subTest(name=name),fixture() as f:
                r.arm(True)
                (f.install/name).write_bytes(b'fixture independent drift')
                before=hashes(f.base)
                with self.assertRaisesRegex(r.RecoveryError,'RUNTIME_DRIFT'):r.rollback()
                self.assertEqual(hashes(f.base),before)

    def test_rollback_rejects_runtime_mode_drift_before_any_restore(self):
        with fixture() as f:
            r.arm(True)
            before=hashes(f.base)
            actual=Path.stat
            def changed(path,*a,**kw):
                value=actual(path,*a,**kw)
                if path==f.install/'config.json':return SimpleNamespace(st_mode=stat.S_IFREG|0o400)
                return value
            with patch.object(Path,'stat',changed):
                with self.assertRaisesRegex(r.RecoveryError,'RUNTIME_DRIFT'):r.rollback()
            self.assertEqual(hashes(f.base),before)

    def test_release_success_receipt_failure_restores_hold_and_records_actual_command_failure(self):
        with fixture() as f:
            r.arm(True)
            actual=r.new_file
            def write(path,data,*a,**kw):
                if path.name=='release.stderr':raise OSError('fixture receipt failure')
                return actual(path,data,*a,**kw)
            with patch.object(r,'new_file',side_effect=write):
                with self.assertRaises(OSError):r.execute_action('release')
            self.assertEqual(json.loads((f.cd/'HALTED.json').read_bytes()),r.HALT)
            self.assertTrue((f.root/'START_BLOCKED').exists())
            failure=json.loads((r.ATTEMPT/'release-command-failed.json').read_bytes())
            self.assertEqual(failure['nativeExit'],1)
            prepared=json.loads((r.ATTEMPT/'release-result.json').read_bytes())
            self.assertEqual(prepared['exitScope'],'ACTION_BEFORE_RECEIPT_COMMIT')
            self.assertNotIn('fixture receipt failure',(r.ATTEMPT/'release-command-failed.json').read_text())


if __name__=='__main__':
    unittest.main()

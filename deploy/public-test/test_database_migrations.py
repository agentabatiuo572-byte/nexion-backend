"""Behavioral migration tests; never connect to the user's database."""
import gzip
import io
import json
from pathlib import Path
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import database_migrations as m
import release_broker as b

SHA = 'a' * 40
OLD = '20260909_existing.sql'
NEW = '20260911_new_feature.sql'


class MigrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.db = self.root / 'migrations'
        self.backups = self.root / 'backups'
        self.db.mkdir()
        self.backups.mkdir()
        self.guard = self.root / 'guard.conf'
        self.guard.write_text(m.GUARD_TEXT)
        self.permit = self.root / 'volatile-permit'
        self.scripts = {OLD: b'SELECT 1;', NEW: b'CREATE TABLE example (id INT);'}
        self.state = {'version': 1, 'database': 'nexion', 'scripts': {
            OLD: {'sha256': m.digest(self.scripts[OLD]), 'status': 'BASELINE_NOT_REPLAYED'}}}
        m.save(self.db / 'state.json', self.state)
        self.calls = []
        self.patches = [patch.object(m, 'ROOT', self.db), patch.object(m, 'BACKUPS', self.backups),
                        patch.object(m, 'GUARD', self.guard),
                        patch.object(m, 'PERMIT', self.permit),
                        patch.object(m, 'trusted'), patch.object(m, 'download_catalog', return_value=self.scripts),
                        patch.object(m, 'credentials', return_value=('app', 'fixture', 'fixture-root')),
                        patch.object(m, 'scoped_account'),
                        patch.object(m, 'stop_backend', side_effect=lambda: self.calls.append('stop')),
                        patch.object(m, 'start_previous', side_effect=lambda: self.calls.append('start-old')),
                        patch.object(m, 'backup_database', side_effect=self.backup),
                        patch.object(m, 'execute_sql', side_effect=lambda name, *_: self.calls.append(name))]
        for p in self.patches:
            p.start()
            self.addCleanup(p.stop)

    def backup(self, folder, _password):
        self.calls.append('backup')
        return {'path': str(folder / 'database.sql.gz'), 'sha256': 'b' * 64, 'uncompressed_bytes': 20000}

    def test_backup_then_once_only_sql_then_candidate_owned_start(self):
        receipt = m.apply(SHA)
        self.assertEqual(self.calls, ['stop', 'backup', NEW])
        self.assertEqual(receipt['phase'], 'SQL_APPLIED')
        self.assertEqual(m.apply(SHA), receipt)
        self.assertEqual(self.calls, ['stop', 'backup', NEW])
        m.complete_release()
        self.assertIsNone(m.active())
        self.assertIsNone(m.apply(SHA))
        self.assertEqual(json.loads((self.db / 'state.json').read_text())['scripts'][NEW]['status'], 'APPLIED')

    def test_backup_failure_does_not_execute_sql_and_restores_old_service(self):
        with patch.object(m, 'backup_database', side_effect=m.MigrationError('BACKUP_FAILURE')):
            with self.assertRaisesRegex(m.MigrationError, 'BACKUP_FAILURE'):
                m.apply(SHA)
        self.assertEqual(self.calls, ['stop', 'start-old'])
        self.assertIsNone(m.active())
        self.assertEqual(json.loads((self.db / 'state.json').read_text()), self.state)

    def test_partial_sql_never_retried_or_followed_by_old_jar_restart(self):
        self.scripts['20260911_second.sql'] = b'ALTER TABLE example ADD name VARCHAR(8);'
        def execute(name, *_):
            self.calls.append(name)
            if name.endswith('second.sql'):
                raise m.MigrationError('SQL_FAILED')
        with patch.object(m, 'execute_sql', side_effect=execute):
            with self.assertRaisesRegex(m.MigrationError, 'SQL_FAILED'):
                m.apply(SHA)
        self.assertEqual(m.active()['phase'], 'SQL_FAILED')
        self.assertNotIn('start-old', self.calls)
        count = len(self.calls)
        with self.assertRaisesRegex(m.MigrationError, 'PARTIAL_OR_INTERRUPTED'):
            m.apply('c' * 40)
        self.assertEqual(len(self.calls), count)
        persisted = json.loads((self.db / 'state.json').read_text())
        self.assertIn(NEW, persisted['scripts'])
        self.assertNotIn('20260911_second.sql', persisted['scripts'])

    def test_crash_journal_before_backup_requires_recovery_not_blind_retry(self):
        m.save(self.db / 'active.json', {'phase': 'PREPARING'})
        with self.assertRaisesRegex(m.MigrationError, 'PARTIAL_OR_INTERRUPTED'):
            m.apply(SHA)
        self.assertEqual(self.calls, [])

    def test_changed_or_deleted_history_rejected_before_stopping(self):
        for files in ({OLD: b'UPDATE account SET balance=0;'}, {NEW: b'SELECT 1;'}):
            with patch.object(m, 'download_catalog', return_value=files):
                with self.assertRaisesRegex(m.MigrationError, 'HISTORY'):
                    m.apply(SHA)
        self.assertEqual(self.calls, [])

    def test_recorded_test_history_keeps_original_hashes(self):
        # These hashes are already recorded in TEST state; forward SQL repairs content.
        expected = {
            '20260720_e2_task_pricing_closure.sql': '35e54c420ab6145b8a91d876c8e6b79ddfd231fb3dd8b25d56dbfbdb83109913',
            '20260722_i2_nova_closure.sql': '85627dddea64cc9fa00404564f59c8b053a4c9f1f251c40e1ac5fc2d691e4e6c',
            '20260722_i3_notification_campaign_closure.sql': '89686ab72fd9f448c36bd58095ecb99ad904e17fabe1d32386c09f12c81aee59',
            '20260920_i5_published_disclosure_provisioning.sql': 'daf68564fabfeb02d3db3bfef25db4546936f13c68e96b11e067dd76d1a94114',
            '20261009_e4_wallet_bill_schema.sql': '3d5fe9ce37d07fdefc1fd64e78a0e65e3f38b1ab386a12be4e37e2c84cee458d',
        }
        directory = Path(__file__).resolve().parents[2] / 'scripts' / 'migrations'
        for name, sha in expected.items():
            # core.autocrlf may expand LF in a Windows checkout; Git's tar archive uses LF.
            data = (directory / name).read_bytes().replace(b'\r\n', b'\n')
            self.assertEqual(m.digest(data), sha, name)

    def test_e4_applied_history_queues_forward_and_fresh_catalog_keeps_order(self):
        directory = Path(__file__).resolve().parents[2] / 'scripts' / 'migrations'
        compat = '20261009_e4_wallet_bill_compat.sql'
        history = '20261009_e4_wallet_bill_schema.sql'
        forward = '20261009_e4_wallet_bill_schema_precision_forward.sql'
        files = {name: (directory / name).read_bytes().replace(b'\r\n', b'\n')
                 for name in (compat, history, forward)}
        state = {'version': 1, 'database': 'nexion', 'scripts': {
            compat: {'sha256': m.digest(files[compat]), 'status': 'APPLIED'},
            history: {'sha256': '3d5fe9ce37d07fdefc1fd64e78a0e65e3f38b1ab386a12be4e37e2c84cee458d',
                      'status': 'APPLIED'}}}
        self.assertEqual(m.pending_scripts(state, files), [(forward, files[forward])])
        fresh = {'version': 1, 'database': 'nexion', 'scripts': {}}
        self.assertEqual([name for name, _ in m.pending_scripts(fresh, files)],
                         [compat, history, forward])
        for sql in files.values():
            m.validate_sql(sql)
        # The forward guard only widens precision; every other guard and CREATE stays intact.
        body = lambda data: b'\n'.join(line for line in data.splitlines()
                                      if not line.startswith(b'--'))
        self.assertEqual(body(files[forward]),
                         body(files[history]).replace(b'numeric_precision=18', b'numeric_precision>=18'))

    def test_rollback_probe_cannot_execute_pending_sql(self):
        with self.assertRaisesRegex(m.MigrationError, 'FORWARD_DEPLOY'):
            m.apply(SHA, rollback_check=True)
        self.assertEqual(self.calls, [])

    def test_rollback_probe_cannot_restart_old_code_after_sql_applied(self):
        m.apply(SHA)
        calls = list(self.calls)
        with self.assertRaisesRegex(m.MigrationError, 'FORWARD_DEPLOY'):
            m.apply(SHA, rollback_check=True)
        self.assertEqual(self.calls, calls)

    def test_persistent_guard_only_allows_forward_candidate_after_all_sql(self):
        for phase in ('PREPARING', 'SQL_APPLYING', 'SQL_FAILED'):
            m.save(self.db / 'active.json', {'phase': phase})
            m.save(self.db / 'START_BLOCKED', {})
            with self.assertRaises(m.MigrationError):
                m.allow_candidate_start()
            self.assertTrue((self.db / 'START_BLOCKED').exists())
        m.save(self.db / 'active.json', {'phase': 'SQL_APPLIED'})
        m.allow_candidate_start()
        self.assertTrue((self.db / 'START_BLOCKED').exists())
        self.assertTrue(self.permit.exists())
        # /run is volatile: a reboot removes permission, persistent hold survives.
        m.revoke_candidate_start()
        self.assertFalse(self.permit.exists())
        self.assertTrue((self.db / 'START_BLOCKED').exists())

    def test_database_and_access_commands_rejected(self):
        for sql in (b'USE mysql;', b'DROP DATABASE nexion;', b'GRANT ALL ON *.* TO x;',
                    b'SET GLOBAL local_infile=1;', b'CREATE USER x;', b'SELECT 1;\0'):
            with self.assertRaises(m.MigrationError):
                m.validate_sql(sql)
        m.validate_sql(b'-- business forward migration\nALTER TABLE example ADD new_column INT;')

    def test_corrupt_or_incomplete_backup_never_verified(self):
        path = self.root / 'backup.gz'
        for data in (b'bad', gzip.compress(b'partial dump')):
            path.write_bytes(data)
            with self.assertRaises((m.MigrationError, OSError, EOFError)):
                m.verify_backup(path)
        path.write_bytes(gzip.compress(b'-' * 12000 + b'\n-- Dump completed on fixture\n'))
        self.assertGreater(m.verify_backup(path)['uncompressed_bytes'], 12000)

    def test_scoped_privileges_enforced_by_server_grants(self):
        allowed = b"GRANT USAGE ON *.* TO `app`@`%`\nGRANT ALL PRIVILEGES ON `nexion`.* TO `app`@`%`\n"
        for grants, ok in ((allowed, True), (allowed + b'GRANT FILE ON *.* TO x\n', False),
                           (allowed + b'GRANT EXECUTE ON `mysql`.* TO x\n', False),
                           (allowed.replace(b'TO `app`@`%`\n', b'TO x WITH GRANT OPTION\n'), False)):
            with patch.object(m.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stdout=grants)):
                # Override setUp's scoped-account stub for this direct unit test.
                original_scoped_account('app', 'fixture') if ok else self.assert_grants_rejected()

    def assert_grants_rejected(self):
        with self.assertRaises(m.MigrationError):
            original_scoped_account('app', 'fixture')

    def test_mysql_client_disables_local_commands_and_infile(self):
        args = m.mysql_args('app')
        for value in ('--binary-mode', '--local-infile=0', '--skip-reconnect', '--batch'):
            self.assertIn(value, args)
        self.assertNotIn('--force', args)

    def test_secret_permissions_reject_readable_by_non_root(self):
        from types import SimpleNamespace
        fake = SimpleNamespace(lstat=lambda: SimpleNamespace(st_mode=0o100644))
        for mode in (0o100644, 0o100640):
            fake.lstat = lambda: SimpleNamespace(st_mode=mode)
            with patch.object(m, 'ENV_FILE', fake):
                with self.assertRaisesRegex(m.MigrationError, 'SECRET_PERMISSIONS'):
                    original_credentials()

    def test_baseline_reentry_verifies_exact_state_without_replaying_history(self):
        import shutil
        shutil.rmtree(self.db)  # Exact isolated TemporaryDirectory child only.
        receipt = self.root / 'receipt.json'
        proof = {'phase': 'APPLIED', 'sha': SHA, 'migration': OLD,
                 'migration_sha256': m.digest(self.scripts[OLD]), 'backup_sha256': 'b'*64}
        receipt.write_text(json.dumps(proof))
        with patch.object(m, 'verify_backup', return_value={'sha256': 'b'*64}):
            m.baseline(SHA, receipt)
            first = (self.db / 'state.json').read_bytes()
            m.baseline(SHA, receipt)
            self.assertEqual((self.db / 'state.json').read_bytes(), first)
            self.scripts[NEW] = b'SELECT changed;'
            with self.assertRaisesRegex(m.MigrationError, 'BASELINE_DIFFERS'):
                m.baseline(SHA, receipt)

    def test_archive_only_reads_exact_commit_flat_sql_files(self):
        def archive(entries):
            stream = io.BytesIO()
            with tarfile.open(fileobj=stream, mode='w:gz') as tar:
                for path, kind in entries:
                    item = tarfile.TarInfo(path)
                    item.type = kind
                    item.size = 9 if kind == tarfile.REGTYPE else 0
                    tar.addfile(item, io.BytesIO(b'SELECT 1;') if item.size else None)
            return stream.getvalue()
        base = 'nexion-backend-' + SHA + '/scripts/migrations/'
        good = archive([(base + NEW, tarfile.REGTYPE), ('nexion-backend-' + SHA + '/scripts/seed.sql', tarfile.REGTYPE)])
        self.assertEqual(m.catalog_from_archive(good, SHA), {NEW: b'SELECT 1;'})
        for entries in ([(base + NEW, tarfile.SYMTYPE)], [(base + NEW, tarfile.REGTYPE)] * 2,
                        [(base + '../bad.sql', tarfile.REGTYPE)], [(base + '20260911_rollback.sql', tarfile.REGTYPE)]):
            with self.assertRaises(m.MigrationError):
                m.catalog_from_archive(archive(entries), SHA)

    def test_schema_applied_code_failure_stops_instead_of_code_rollback(self):
        journal = {'component': 'backend', 'database': {'phase': 'SQL_APPLIED'}}
        with patch.object(b, 'health', side_effect=b.Rejected('UNHEALTHY')), \
                patch.object(b, 'restore') as restore, patch.object(b, 'clear_restored_transaction') as clear:
            with self.assertRaisesRegex(b.ComponentFailed, 'DB_APPLIED_CANDIDATE_FAILED'):
                b.apply_with_rollback(journal, lambda: None, 8110, 8110)
        restore.assert_not_called()
        clear.assert_called_once_with(journal)
        self.assertEqual(self.calls, ['stop'])


class FixedTriggerTests(unittest.TestCase):
    backup = MigrationTests.backup

    def setUp(self):
        MigrationTests.setUp(self)
        self.host_config = self.root / 'host-config.json'
        directory = Path(__file__).resolve().parents[2] / 'scripts' / 'migrations'
        self.earnings = (directory / m.EARNINGS_FILE).read_bytes().replace(b'\r\n', b'\n')
        self.scripts[m.EARNINGS_FILE] = self.earnings
        self.context = {'version': '8.4.11', 'database': 'nexion', 'principal': 'app@%',
                        'log_bin': 1, 'trust': 0, 'mode': 'STRICT_TRANS_TABLES',
                        'charset': 'utf8mb4', 'collation': 'utf8mb4_0900_ai_ci'}
        self.app_grants = ['GRANT ALL PRIVILEGES ON `nexion`.* TO `app`@`%`']
        self.root_grants = ['GRANT SUPER, TRIGGER, SET_ANY_DEFINER ON *.* TO `root`@`localhost`']
        self.trigger_installed = False
        self.root_exit = 0
        self.bad_postcheck = False
        self.fail_segment = None
        self.columns = []
        self.sql_calls = []
        self.query_calls = []
        for p in (patch.object(m, 'HOST_CONFIG', self.host_config),
                  patch.object(m, 'probe', side_effect=self.read_query),
                  patch.object(m, 'execute_sql', side_effect=self.app_sql),
                  patch.object(m, 'stop_backend', side_effect=self.hold),
                  patch.object(m.subprocess, 'run', side_effect=self.root_sql)):
            p.start()
            self.addCleanup(p.stop)

    def enable(self, value=True):
        self.host_config.write_text(json.dumps({'allow_fixed_earnings_trigger_install': value}))

    def hold(self):
        self.calls.append('stop')
        m.save(self.db / 'START_BLOCKED', {'reason': 'DATABASE_MIGRATION_HOLD'})

    def read_query(self, user, _password, sql, *, as_json=True):
        self.query_calls.append((user, sql))
        if sql == m.CONTEXT_SQL:
            return {**self.context, 'principal': 'root@localhost' if user == 'root' else self.context['principal']}
        if sql == 'SHOW GRANTS FOR CURRENT_USER();':
            self.assertFalse(as_json)
            return self.root_grants if user == 'root' else self.app_grants
        if sql == m.TRIGGER_SQL:
            if not self.trigger_installed:
                return []
            return [{'name': m.EARNINGS_TRIGGER, 'table': 'nx_user_wallet', 'timing': 'BEFORE',
                     'event': 'UPDATE', 'body': 'SET NEW.usdt_available=0' if self.bad_postcheck else m.EARNINGS_BODY,
                     'definer': self.context['principal'], 'mode': self.context['mode'],
                     'charset': self.context['charset'], 'collation': self.context['collation']}]
        if 'information_schema.COLUMNS' in sql:
            return self.columns
        self.fail('Unexpected read query: ' + sql)

    def app_sql(self, name, data, _folder, user, _password):
        self.assertEqual(user, 'app')
        self.calls.append(name)
        self.sql_calls.append((user, name, data))
        if name.endswith('.prefix'):
            self.columns = [{'name': n, 'type': 'decimal(30,6)', 'nullable': 'NO', 'default': '0.000000'}
                            for n in ('earnings_usdt_debited', 'earnings_nex_debited')]
        if self.fail_segment and name.endswith('.' + self.fail_segment):
            raise m.MigrationError('FIXTURE_' + self.fail_segment.upper() + '_FAILED')

    def root_sql(self, args, *, input, stdout, stderr, timeout, env):
        self.assertEqual(args, m.mysql_args('root'))
        expected = b'SET SESSION lock_wait_timeout=30;\n' + m.fixed_earnings_template('app@%', 'app')
        self.assertEqual(input, expected)
        self.assertNotIn(b'ALTER TABLE', input)
        self.assertNotIn(b'CREATE TABLE', input)
        self.assertEqual(env['MYSQL_PWD'], 'fixture-root')
        self.sql_calls.append(('root', m.EARNINGS_FILE + '.trigger', input))
        self.calls.append('root-fixed-trigger')
        if self.root_exit == 0:
            self.trigger_installed = True
        return SimpleNamespace(returncode=self.root_exit)

    def assert_before_hold(self):
        self.assertEqual(self.calls, [])
        self.assertEqual(self.sql_calls, [])
        self.assertFalse((self.db / 'START_BLOCKED').exists())
        self.assertIsNone(m.active())
        self.assertEqual(list(self.backups.iterdir()), [])
        self.assertEqual(json.loads((self.db / 'state.json').read_text()), self.state)

    def test_default_disabled_rejects_whole_batch_before_hold_or_ddl(self):
        for value in ('absent', False):
            if value is False:
                self.enable(False)
            with self.assertRaisesRegex(m.MigrationError, 'NOT_APPROVED_BEFORE_HOLD'):
                m.apply(SHA)
            self.assert_before_hold()
        self.assertTrue(all(user != 'root' for user, _ in self.query_calls))

    def test_host_policy_non_boolean_or_untrusted_is_rejected(self):
        for value in ('true', 1, None):
            self.enable(value)
            with self.assertRaisesRegex(m.MigrationError, 'HOST_CONFIG_REJECTED'):
                m.apply(SHA)
            self.assert_before_hold()
        self.enable()
        def trusted(path):
            if path == self.host_config:
                raise m.MigrationError('MIGRATION_PATH_UNTRUSTED')
        with patch.object(m, 'trusted', side_effect=trusted):
            with self.assertRaisesRegex(m.MigrationError, 'PATH_UNTRUSTED'):
                m.apply(SHA)
        self.assert_before_hold()

    def test_full_hash_and_exact_create_block_are_independent_guards(self):
        self.enable()
        self.scripts[m.EARNINGS_FILE] = self.earnings + b'\nSELECT 1;'
        with self.assertRaisesRegex(m.MigrationError, 'FILE_CHANGED'):
            m.apply(SHA)
        self.assert_before_hold()
        changed = self.earnings.replace(b'BEFORE UPDATE ON', b'AFTER UPDATE ON')
        self.scripts[m.EARNINGS_FILE] = changed
        with patch.object(m, 'EARNINGS_SHA256', m.digest(changed)):
            with self.assertRaisesRegex(m.MigrationError, 'BLOCK_CHANGED'):
                m.apply(SHA)
        self.assert_before_hold()

    def test_unknown_trigger_or_function_never_reaches_hold(self):
        self.enable()
        for sql in (m.EARNINGS_CREATE, b'CREATE /* comment */ TRIGGER x BEFORE UPDATE ON t SET NEW.x=1;',
                    b'/*!50003 CREATE*/ /*!50017 DEFINER=`app`@`%`*/ /*!50003 TRIGGER x BEFORE UPDATE ON t SET NEW.x=1*/;',
                    b'CREATE FUNCTION x() RETURNS INT RETURN 1;',
                    b'CREATE -- ;\nTRIGGER x BEFORE UPDATE ON t SET NEW.x=1;',
                    b'CREATE # ;\nFUNCTION x() RETURNS INT RETURN 1;',
                    b'CREATE --\x7f ;\nTRIGGER x BEFORE UPDATE ON t SET NEW.x=1;',
                    b'CREATE --\x7f ;\nFUNCTION x() RETURNS INT RETURN 1;',
                    b'CREATE /*!50003 -- ;\nTRIGGER */ x BEFORE UPDATE ON t SET NEW.x=1;',
                    b"CREATE DEFINER='app#;-- name'@'%' TRIGGER x BEFORE UPDATE ON t SET NEW.x=1;"):
            self.scripts['20261008_unknown_program.sql'] = sql
            with self.assertRaisesRegex(m.MigrationError, 'UNREVIEWED_STORED_PROGRAM'):
                m.apply(SHA)
            self.assert_before_hold()

    def test_comment_scanner_keeps_quoted_markers_and_mysql_hyphen_rules(self):
        ordinary = b"SELECT '-- ; CREATE TRIGGER', '# ; CREATE FUNCTION', '/* CREATE TRIGGER */';"
        self.assertIsNone(m.migration_preflight([('20261009_ordinary.sql', ordinary)],
                                               'app', 'fixture', 'fixture-root'))
        for mode in (False, True):
            self.assertIn('CREATE--x', m.stored_program_text('CREATE--x;', backslash_escapes=mode))
            self.assertEqual(m.stored_program_text('CREATE -- ;\r\nTRIGGER;', backslash_escapes=mode),
                             'CREATE  \r\nTRIGGER;')
        self.assert_before_hold()

    def test_real_definer_is_quoted_and_injection_is_rejected(self):
        for principal in ('app@%', 'app@127.0.0.1', 'app@::1', 'app@host-name'):
            self.assertEqual(m.account_definer(principal, 'app'), '`app`@`' + principal.split('@')[1] + '`')
        for principal in ('root@localhost', 'other@%', 'app@x`; DROP USER root;', "app@x' OR '1'='1",
                          'app@x\\y', 'app@x\ny', 'app@@%', 'app@', 'app@' + 'x'*256):
            with self.assertRaisesRegex(m.MigrationError, 'DEFINER_REJECTED'):
                m.fixed_earnings_template(principal, 'app')
        self.enable()
        self.context['principal'] = 'app@x`; DROP USER root;'
        with self.assertRaisesRegex(m.MigrationError, 'DEFINER_REJECTED'):
            m.apply(SHA)
        self.assert_before_hold()

    def test_each_required_root_or_app_capability_is_required(self):
        self.enable()
        required = {'SUPER', 'TRIGGER', 'SET_ANY_DEFINER'}
        for missing in required:
            self.root_grants = ['GRANT ' + ', '.join(sorted(required - {missing})) + ' ON *.* TO `root`@`localhost`']
            with self.assertRaisesRegex(m.MigrationError, 'ROOT_CAPABILITIES_MISSING'):
                m.apply(SHA)
            self.assert_before_hold()
        self.root_grants = ['GRANT SUPER, TRIGGER, SET_ANY_DEFINER ON *.* TO `root`@`localhost`']
        app_required = {'ALTER', 'CREATE', 'REFERENCES', 'TRIGGER', 'SELECT', 'UPDATE'}
        for missing in sorted(app_required):
            values = app_required - {missing}
            self.app_grants = ['GRANT ' + ', '.join(sorted(values)) + ' ON `nexion`.* TO `app`@`%`']
            with self.assertRaisesRegex(m.MigrationError, 'APP_CAPABILITIES_MISSING'):
                m.apply(SHA)
            self.assert_before_hold()

    def test_capability_query_failure_and_malformed_context_are_fail_closed(self):
        self.enable()
        for response in ({}, None, {**self.context, 'log_bin': '1'}, {**self.context, 'database': 'mysql'}):
            with patch.object(m, 'probe', return_value=response):
                with self.assertRaises(m.MigrationError):
                    m.apply(SHA)
            self.assert_before_hold()
        with patch.object(m, 'probe', side_effect=m.MigrationError('MIGRATION_CAPABILITY_QUERY_FAILED')):
            with self.assertRaisesRegex(m.MigrationError, 'QUERY_FAILED'):
                m.apply(SHA)
        self.assert_before_hold()

    def test_probe_reports_only_stable_errors_and_keeps_password_out_of_arguments(self):
        for result in (SimpleNamespace(returncode=1, stdout=b'ignored', stderr=b'private-failure'),
                       SimpleNamespace(returncode=0, stdout=b'not-json', stderr=b'')):
            with patch.object(m.subprocess, 'run', return_value=result) as run:
                with self.assertRaises(m.MigrationError) as error:
                    original_probe('app', 'fixture-secret', m.CONTEXT_SQL)
                self.assertNotIn('private-failure', str(error.exception))
                self.assertNotIn('fixture-secret', repr(run.call_args.args))
                self.assertEqual(run.call_args.kwargs['env']['MYSQL_PWD'], 'fixture-secret')
                self.assertEqual(run.call_args.kwargs['input'], m.CONTEXT_SQL.encode())

    def test_existing_wrong_trigger_is_rejected_before_hold(self):
        self.enable()
        self.trigger_installed = True
        self.bad_postcheck = True
        with self.assertRaisesRegex(m.MigrationError, 'SHAPE_REJECTED'):
            m.apply(SHA)
        self.assert_before_hold()

    def test_prefix_and_suffix_failure_never_mark_file_applied_or_restart_old(self):
        self.enable()
        self.fail_segment = 'prefix'
        with self.assertRaisesRegex(m.MigrationError, 'PREFIX_FAILED'):
            m.apply(SHA)
        self.assertEqual(m.active()['segment'], 'PREFIX')
        self.assertTrue((self.db / 'START_BLOCKED').exists())
        self.assertNotIn('root-fixed-trigger', self.calls)
        self.assertNotIn('start-old', self.calls)
        self.assertNotIn(m.EARNINGS_FILE, json.loads((self.db / 'state.json').read_text())['scripts'])

    def test_suffix_failure_keeps_verified_trigger_and_hold(self):
        self.enable()
        self.fail_segment = 'suffix'
        with self.assertRaisesRegex(m.MigrationError, 'SUFFIX_FAILED'):
            m.apply(SHA)
        self.assertEqual(m.active()['phase'], 'SQL_FAILED')
        self.assertEqual(m.active()['segment'], 'SUFFIX')
        self.assertTrue(m.active()['fixed_trigger']['verified'])
        self.assertTrue((self.db / 'START_BLOCKED').exists())
        self.assertNotIn('start-old', self.calls)
        self.assertNotIn(m.EARNINGS_FILE, json.loads((self.db / 'state.json').read_text())['scripts'])

    def test_missing_or_wrong_new_columns_refuse_root_write_after_prefix(self):
        self.enable()
        original = self.read_query
        def bad_columns(user, password, sql, *, as_json=True):
            if 'information_schema.COLUMNS' in sql:
                return []
            return original(user, password, sql, as_json=as_json)
        with patch.object(m, 'probe', side_effect=bad_columns):
            with self.assertRaisesRegex(m.MigrationError, 'COLUMNS_REJECTED'):
                m.apply(SHA)
        self.assertEqual(m.active()['phase'], 'SQL_FAILED')
        self.assertTrue((self.db / 'START_BLOCKED').exists())
        self.assertNotIn('root-fixed-trigger', self.calls)
        self.assertNotIn(m.EARNINGS_FILE + '.suffix', self.calls)

    def test_current_ten_pending_sources_share_the_early_default_refusal(self):
        directory = Path(__file__).resolve().parents[2] / 'scripts' / 'migrations'
        names = [m.EARNINGS_FILE, '20261007_e4_wallet_bill_prerequisite.sql',
                 '20261007_growth_promotions.sql', '20261007_growth_promotions_list_snapshot.sql',
                 '20261007_growth_promotions_order_receipt.sql', '20261007_growth_promotions_quota_restore.sql',
                 '20261007_voucher_zero_payment.sql', '20261008_l6_promotion_reward_routes.sql',
                 '20261009_admin_session_idle60.sql', '20261009_e4_wallet_bill_schema_precision_forward.sql']
        for name in names:
            self.scripts[name] = (directory / name).read_bytes().replace(b'\r\n', b'\n')
        with self.assertRaisesRegex(m.MigrationError, 'NOT_APPROVED_BEFORE_HOLD'):
            m.apply(SHA)
        self.assert_before_hold()

    def test_prefix_fixed_root_suffix_and_other_sql_remains_app(self):
        self.enable()
        self.app_grants = ['GRANT ALTER, CREATE, REFERENCES, TRIGGER, SELECT, UPDATE ON `nexion`.* TO `app`@`%`']
        receipt = m.apply(SHA)
        self.assertEqual(self.calls, ['stop', 'backup', NEW, m.EARNINGS_FILE + '.prefix',
                                     'root-fixed-trigger', m.EARNINGS_FILE + '.suffix'])
        root_calls = [call for call in self.sql_calls if call[0] == 'root']
        self.assertEqual(len(root_calls), 1)
        prefix, suffix = m.earnings_parts(self.earnings)
        self.assertEqual(prefix + m.EARNINGS_CREATE + suffix, self.earnings)
        self.assertEqual(self.sql_calls[1][2], prefix)
        self.assertEqual(self.sql_calls[-1][2], suffix)
        self.assertNotIn(m.EARNINGS_CREATE, prefix + suffix)
        self.assertEqual(receipt['phase'], 'SQL_APPLIED')
        self.assertEqual(receipt['fixed_trigger']['definer'], 'app@%')
        self.assertTrue(receipt['fixed_trigger']['verified'])
        persisted = json.loads((self.db / 'state.json').read_text())
        self.assertEqual(persisted['scripts'][m.EARNINGS_FILE]['sha256'], m.EARNINGS_SHA256)
        self.assertTrue((self.db / 'START_BLOCKED').exists())
        before = list(self.sql_calls)
        m.apply(SHA)
        self.assertEqual(self.sql_calls, before)
        self.assertNotIn('fixture-root', json.dumps(receipt))
        self.assertNotIn('fixture', json.dumps(persisted))

    def test_root_failure_or_postcheck_failure_retains_hold_and_unapplied_file(self):
        self.enable()
        for bad_postcheck in (False, True):
            with self.subTest(postcheck=bad_postcheck):
                self.root_exit = 0 if bad_postcheck else 1
                self.bad_postcheck = bad_postcheck
                with self.assertRaisesRegex(m.MigrationError, 'SHAPE_REJECTED|INSTALL_FAILED'):
                    m.apply(SHA)
                self.assertEqual(m.active()['phase'], 'SQL_FAILED')
                self.assertEqual(m.active()['segment'], 'TRIGGER_INSTALLING')
                self.assertTrue((self.db / 'START_BLOCKED').exists())
                self.assertNotIn('start-old', self.calls)
                persisted = json.loads((self.db / 'state.json').read_text())
                self.assertIn(NEW, persisted['scripts'])
                self.assertNotIn(m.EARNINGS_FILE, persisted['scripts'])
                self.assertNotIn(m.EARNINGS_FILE + '.suffix', self.calls)
                calls = list(self.calls)
                with self.assertRaisesRegex(m.MigrationError, 'PARTIAL_OR_INTERRUPTED'):
                    m.apply(SHA)
                self.assertEqual(self.calls, calls)
                # Reset only this isolated fixture to exercise the second failure.
                if not bad_postcheck:
                    (self.db / 'active.json').unlink()
                    (self.db / 'START_BLOCKED').unlink()
                    m.save(self.db / 'state.json', self.state)
                    self.calls.clear()
                    self.sql_calls.clear()
                    self.backups = self.root / 'backups-postcheck'
                    self.backups.mkdir()
                    p = patch.object(m, 'BACKUPS', self.backups)
                    p.start()
                    self.addCleanup(p.stop)

    def test_existing_exact_trigger_skips_root_create_but_validates_and_runs_suffix(self):
        self.enable()
        self.trigger_installed = True
        receipt = m.apply(SHA)
        self.assertEqual(receipt['phase'], 'SQL_APPLIED')
        self.assertFalse(any(user == 'root' for user, _, _ in self.sql_calls))
        self.assertIn(m.EARNINGS_FILE + '.suffix', self.calls)

    def test_ordinary_account_path_when_binlog_restriction_absent(self):
        for log_bin, trust in ((0, 0), (0, 1), (1, 1)):
            self.context.update(log_bin=log_bin, trust=trust)
            plan = m.migration_preflight(list(self.scripts.items()), 'app', 'fixture', 'fixture-root')
            self.assertFalse(plan['root_install'])
        self.context.update(log_bin=0, trust=0)
        receipt = m.apply(SHA)
        self.assertEqual(self.calls, ['stop', 'backup', NEW, m.EARNINGS_FILE])
        self.assertEqual(receipt['phase'], 'SQL_APPLIED')
        self.assertFalse(any(user == 'root' for user, _, _ in self.sql_calls))


original_scoped_account = m.scoped_account
original_credentials = m.credentials
original_probe = m.probe

if __name__ == '__main__':
    unittest.main()

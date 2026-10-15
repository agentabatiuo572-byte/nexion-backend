"""Fixed fund migration integration and failure tests, using local unit fixtures."""
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import re
import tempfile
import types
import unittest
from unittest.mock import Mock, patch
import database_migrations as m

HERE = Path(__file__).parent
REPO = HERE.parent.parent
COMMIT = 'a' * 40
# SQL is transported from Git as LF; Windows checkout EOL conversion is irrelevant.
SOURCES = {name: (REPO / 'scripts/migrations' / name).read_text(encoding='utf-8').encode()
           for name in m.SUPPORT_PINS}
SOURCES.update({'20261010_ordinary.sql': b'SELECT 1;', '20261015_ordinary.sql': b'SELECT 2;'})
REAL_VERIFY = m.support_verify_program
REAL_SCOPED = m.scoped_account


class FundMigrationTests(unittest.TestCase):
    def setUp(self):
        output = redirect_stdout(io.StringIO())
        output.__enter__()
        self.addCleanup(output.__exit__, None, None, None)
        self.temp = tempfile.TemporaryDirectory(dir=HERE)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.db, self.backups = self.root / 'migrations', self.root / 'backups'
        self.db.mkdir(); self.backups.mkdir()
        self.guard = self.root / 'guard'
        self.guard.write_text(m.GUARD_TEXT)
        self.state = {'version': 1, 'database': 'nexion', 'scripts': {}}
        m.save(self.db / 'state.json', self.state)
        self.calls, self.sql, self.sessions = [], [], []
        self.fail_root = self.fail_session = self.fail_close = None
        self.app_grants = ['GRANT ALL PRIVILEGES ON `nexion`.* TO `nexion_app`@`%`']
        app = {'version': '8.4.11', 'database': 'nexion', 'principal': m.SUPPORT_APP,
               'log_bin': 1, 'trust': 0, 'mode': 'STRICT_TRANS_TABLES',
               'charset': 'utf8mb4', 'collation': 'utf8mb4_0900_ai_ci'}
        self.context = {**app, 'root_context': {**app, 'principal': 'root@localhost'},
                        'app_grants_before': self.app_grants, 'create_schema_account': True}
        self.credentials = Mock(return_value=('nexion_app', 'UNIT_APP_NOT_SECRET', 'UNIT_ROOT_NOT_SECRET'))
        self.patchers = [patch.object(m, 'ROOT', self.db), patch.object(m, 'BACKUPS', self.backups),
            patch.object(m, 'GUARD', self.guard), patch.object(m, 'PERMIT', self.root / 'permit'),
            patch.object(m, 'trusted'), patch.object(m, 'download_catalog', return_value=dict(SOURCES)),
            patch.object(m, 'credentials', self.credentials), patch.object(m, 'scoped_account'),
            patch.object(m, 'stop_backend', side_effect=self.hold),
            patch.object(m, 'start_previous', side_effect=lambda: self.calls.append('start-old')),
            patch.object(m, 'backup_database', side_effect=self.backup),
            patch.object(m, 'execute_sql', side_effect=self.execute),
            patch.object(m, 'server_context', side_effect=lambda actor, *_: self.context['root_context'] if actor == 'root' else app),
            patch.object(m, 'probe', side_effect=self.post_grants),
            patch.object(m, 'SupportMysqlSession', side_effect=self.new_session),
            patch.object(m, 'support_verify_program')]
        for p in self.patchers:
            p.start(); self.addCleanup(p.stop)

    def hold(self):
        self.calls.append('hold')
        m.save(self.db / 'START_BLOCKED', {'unit_fixture': True})

    def backup(self, folder, *_):
        self.calls.append('backup')
        return {'path': str(folder / 'UNIT_NOT_A_REAL_DUMP.gz'), 'sha256': '0' * 64, 'uncompressed_bytes': 20000}

    def execute(self, name, data, folder, actor, *_):
        self.sql.append((name, data, actor))
        self.calls.append(name)
        if self.fail_root and self.fail_root in name:
            raise m.MigrationError('UNIT_ROOT_REPLY_LOST_UNCERTAIN')

    def new_session(self, folder, name, *_):
        owner = self
        class UnitSession:
            def __init__(self):
                self.name, self.sent, self.closed = name, [], []
            def send(self, data):
                # Model App metadata visibility: another definer's body is NULL.
                if name.endswith('schema_reader.sql') and b'AND BINARY ROUTINE_DEFINITION=' in data:
                    raise m.MigrationError('APP_ROUTINE_BODY_IS_NULL')
                self.sent.append(data)
                if owner.fail_session == (name, len(self.sent)):
                    raise m.MigrationError('UNIT_APP_SESSION_LOST')
            def close(self, *, success):
                self.closed.append(success)
                if success and owner.fail_close == name:
                    raise m.MigrationError('UNIT_EXIT_LOST')
        result = UnitSession()
        self.sessions.append(result)
        return result

    def post_grants(self, actor, secret, sql, *, as_json=True):
        if sql.startswith('SHOW GRANTS FOR ' + m.SUPPORT_GRANTEE):
            return ['GRANT USAGE ON *.* TO `nexion_support_schema`@`localhost`'] + [
                m.support_show_grant(row) for row in (*m.SUPPORT_METADATA_GRANTS, m.SUPPORT_EXECUTE_GRANTS[0])]
        if sql == 'SHOW GRANTS FOR CURRENT_USER();':
            return self.app_grants + [m.support_show_grant(m.SUPPORT_EXECUTE_GRANTS[1])]
        if 'FROM mysql.user ' in sql:
            return [{'user': 'nexion_support_schema', 'host': 'localhost', 'locked': 'Y'}]
        raise AssertionError('UNEXPECTED_QUERY_IN_UNIT_TEST')

    def unit_apply(self):
        with patch.object(m, 'support_preflight', return_value=self.context):
            return m.apply(COMMIT)

    def test_changed_fixed_source_and_partial_batch_fail_before_credentials(self):
        for name in m.SUPPORT_PROGRAMS:
            bad = dict(SOURCES)
            bad[name] += b'\nSELECT 9;'
            with self.subTest(name=name), patch.object(m, 'download_catalog', return_value=bad):
                with self.assertRaisesRegex(m.MigrationError, 'FIXED_SOURCE_CHANGED'):
                    m.apply(COMMIT)
            del bad[name]
            with patch.object(m, 'download_catalog', return_value=bad):
                with self.assertRaisesRegex(m.MigrationError, 'COMPLETE_FIRST_INSTALL_REQUIRED'):
                    m.apply(COMMIT)
        self.credentials.assert_not_called()
        self.assertEqual(self.calls, [])

    def test_unknown_program_in_other_file_is_still_rejected_before_hold(self):
        bad = {**SOURCES, '20261012_unknown.sql': b'CREATE TRIGGER unknown AFTER INSERT ON t FOR EACH ROW SET @x=1;'}
        with patch.object(m, 'download_catalog', return_value=bad):
            with self.assertRaisesRegex(m.MigrationError, 'UNREVIEWED_STORED_PROGRAM'):
                m.apply(COMMIT)
        self.assertEqual(self.calls, [])
        self.assertFalse((self.db / 'active.json').exists())

    def test_complete_release_does_not_reapply_fund_programs(self):
        receipt = self.unit_apply()
        before = len(self.sql)
        self.assertEqual(m.apply(COMMIT), receipt)
        m.complete_release()
        self.assertIsNone(m.apply(COMMIT))
        self.assertEqual(len(self.sql), before)
        self.assertFalse((self.db / 'START_BLOCKED').exists())

    def test_enumerated_root_privileges_support_the_fixed_installer(self):
        def grants(kind, value):
            if kind == 'root_grants':
                return ['GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, ALTER, SUPER, TRIGGER, REFERENCES, '
                        'CREATE ROUTINE, ALTER ROUTINE, CREATE USER, EXECUTE ON *.* TO `root`@`localhost` WITH GRANT OPTION',
                        'GRANT SET_ANY_DEFINER ON *.* TO `root`@`localhost`']
            return value
        with patch.object(m, 'probe', side_effect=self.live_probe_fixture(grants)), \
                patch.object(m, 'verify_earnings_trigger', return_value=True):
            self.assertEqual(m.support_preflight('nexion_app', 'UNIT', 'UNIT')['principal'], m.SUPPORT_APP)
        for privilege in ('SUPER', 'TRIGGER', 'REFERENCES', 'CREATE ROUTINE', 'CREATE USER', 'EXECUTE', 'SET_ANY_DEFINER'):
            def missing(kind, value):
                rows = grants(kind, value)
                return [row.replace(privilege, 'SELECT') for row in rows] if kind == 'root_grants' else rows
            with self.subTest(privilege=privilege), patch.object(m, 'probe', side_effect=self.live_probe_fixture(missing)):
                with self.assertRaisesRegex(m.MigrationError, 'FIXED_CREATOR_CAPABILITIES_MISSING'):
                    m.support_preflight('nexion_app', 'UNIT', 'UNIT')

    def test_absent_locked_account_is_created_only_after_backup(self):
        with patch.object(m, 'probe', side_effect=self.live_probe_fixture(
                lambda kind, value: [] if kind == 'account' else value)), \
                patch.object(m, 'verify_earnings_trigger', return_value=True):
            context = m.support_preflight('nexion_app', 'UNIT', 'UNIT')
        self.assertTrue(context['create_schema_account'])
        self.unit_apply()
        account = next(name for name, data, actor in self.sql if b'CREATE USER' in data)
        self.assertLess(self.calls.index('backup'), self.calls.index(account))
        self.assertEqual(sum(b'CREATE USER' in data for _, data, _ in self.sql), 1)

    def test_existing_locked_empty_account_is_not_recreated(self):
        self.context['create_schema_account'] = False
        self.unit_apply()
        self.assertFalse(any(b'CREATE USER' in data for _, data, _ in self.sql))

    def test_account_creation_uncertainty_keeps_original_hold(self):
        self.fail_root = '.schema-account'
        with self.assertRaisesRegex(m.MigrationError, 'UNIT_ROOT_REPLY_LOST'):
            self.unit_apply()
        self.assertEqual(m.active()['phase'], 'SQL_FAILED')
        state = json.loads((self.db / 'state.json').read_text())
        self.assertFalse(set(m.SUPPORT_PROGRAMS) & state['scripts'].keys())
        self.assertNotIn('start-old', self.calls)
        self.assertTrue((self.db / 'START_BLOCKED').exists())

    def test_08_exact_reconstruction_and_only_two_reader_binding_changes(self):
        programs = []
        for name in m.SUPPORT_PROGRAMS:
            raw = SOURCES[name]
            parts = m.support_segments(name, raw)
            self.assertEqual(b''.join(p['data'] for p in parts), raw)
            programs.extend(p for p in parts if p['program'])
        self.assertEqual([sum(p['kind'] == kind for p in programs) for kind in ('TRIGGER', 'FUNCTION')], [9, 1])
        raw = SOURCES['20261011_support_fund_schema_reader.sql']
        ordinary = [p['data'] for p in m.support_segments('20261011_support_fund_schema_reader.sql', raw) if not p['program']]
        self.assertEqual(sum(data.count(b'DEFINER=CURRENT_USER()') for data in ordinary), 2)
        self.assertEqual(raw.count(b'DEFINER=CURRENT_USER()'), 2)
        self.assertEqual(raw.count(b'CREATE DEFINER=CURRENT_USER FUNCTION'), 1)

    def test_09_fixed_templates_only_change_one_header_and_use_two_distinct_definers(self):
        for name in m.SUPPORT_PROGRAMS:
            raw = SOURCES[name]
            for part in m.support_segments(name, raw):
                if not part['program']: continue
                actual = m.support_template(name, part['program'], raw)
                if part['kind'] == 'FUNCTION':
                    restored = actual.replace(b'CREATE DEFINER=`nexion_support_schema`@`localhost` FUNCTION', b'CREATE DEFINER=CURRENT_USER FUNCTION', 1)
                else:
                    restored = actual.replace(b'CREATE DEFINER=`nexion_app`@`%` TRIGGER', b'CREATE TRIGGER', 1)
                    self.assertNotIn(b'nexion_support_schema', actual)
                self.assertEqual(restored, part['data'])
                self.assertNotIn(b'CREATE DEFINER=`root`', actual)
                self.assertNotEqual(actual, raw)

    def test_10_integration_orders_once_and_uses_one_session_per_fund_file(self):
        receipt = self.unit_apply()
        self.assertEqual(receipt['phase'], 'SQL_APPLIED')
        self.assertEqual(receipt['pending'], ['20261010_ordinary.sql', *m.SUPPORT_PINS, '20261015_ordinary.sql'])
        self.assertEqual([s.name for s in self.sessions], list(m.SUPPORT_PROGRAMS))
        self.assertEqual([s.closed for s in self.sessions], [[True]] * 4)
        self.assertTrue(all(len(s.sent) > 2 for s in self.sessions))
        self.assertEqual(len([row for row in self.sql if row[2] == 'root']), 18)
        self.assertTrue((self.db / 'START_BLOCKED').exists())
        self.assertNotIn('start-old', self.calls)
        self.assertEqual(set(json.loads((self.db / 'state.json').read_bytes())['scripts']), set(SOURCES))

    def test_11_reader_prefix_and_suffix_share_variables_and_lock_session(self):
        self.unit_apply()
        session = next(s for s in self.sessions if s.name.endswith('schema_reader.sql'))
        sent = b''.join(session.sent)
        self.assertIn(b'GET_LOCK(@fund_schema_reader_lock', sent)
        self.assertIn(b'RELEASE_LOCK(@fund_schema_reader_lock)', sent)
        self.assertEqual(sent.count(b"DEFINER='nexion_support_schema@localhost'"), 2)
        self.assertNotIn(b'DEFINER=CURRENT_USER()', sent)
        self.assertNotIn(b'CREATE DEFINER', sent)
        self.assertNotIn(b'BINARY ROUTINE_DEFINITION=BINARY @fund_schema_reader_expected_body', sent)
        self.assertIn(b'SET @fund_schema_reader_ready=nx_support_fund_schema_ready_v1()', sent)
        self.assertIn(b'IF(@fund_schema_reader_ready=1', sent)

    def test_12_every_privileged_payload_is_fixed_create_or_exact_approved_access(self):
        self.unit_apply()
        root_payloads = [data for _, data, actor in self.sql if actor == 'root']
        settings = m.support_session_settings(self.context)
        creates = [data[len(settings):] for data in root_payloads if b'CREATE DEFINER=' in data]
        expected = [m.support_template(name, p['program'], SOURCES[name]) for name in m.SUPPORT_PROGRAMS
                    for p in m.support_segments(name, SOURCES[name]) if p['program']]
        self.assertEqual(creates, expected)
        access = [data[len(settings):] for data in root_payloads if b'CREATE DEFINER=' not in data]
        self.assertEqual(access, [b"CREATE USER 'nexion_support_schema'@'localhost' ACCOUNT LOCK;\n",
                                 *[sql.encode() for sql in (*m.SUPPORT_METADATA_GRANTS, *m.SUPPORT_EXECUTE_GRANTS)]])
        self.assertFalse(any(b'UPDATE nx_user_wallet' in data for data in root_payloads))

    def test_13_same_app_session_keeps_original_uuid_metadata_write_and_guards(self):
        self.unit_apply()
        session = self.sessions[0]
        sent = b''.join(session.sent)
        self.assertIn(b'UPDATE nx_user_wallet SET fund_incarnation=UUID(),updated_at=updated_at WHERE fund_incarnation IS NULL', sent)
        self.assertIn(b'GET_LOCK(@fund_lock', sent)
        self.assertIn(b'RELEASE_LOCK(@fund_lock)', sent)

    def test_14_root_ack_loss_holds_without_applied_or_automatic_retry(self):
        self.fail_root = 'nx_support_wallet_journal_bu_v1'
        with self.assertRaisesRegex(m.MigrationError, 'UNIT_ROOT_REPLY_LOST'):
            self.unit_apply()
        self.assertEqual(m.active()['phase'], 'SQL_FAILED')
        self.assertEqual(self.sessions[0].closed, [False])
        state = json.loads((self.db / 'state.json').read_bytes())
        self.assertNotIn('20261011_support_fund_journal.sql', state['scripts'])
        self.assertTrue((self.db / 'START_BLOCKED').exists())
        self.assertNotIn('start-old', self.calls)
        before = len(self.sql)
        with self.assertRaisesRegex(m.MigrationError, 'PARTIAL_OR_INTERRUPTED'):
            m.apply(COMMIT)
        self.assertEqual(len(self.sql), before)

    def test_15_app_session_loss_and_exit_loss_never_stamp_file_applied(self):
        for mode in ('send', 'close'):
            # Each subcase gets an independent in-memory/unit-filesystem fixture.
            if mode == 'send': self.fail_session = ('20261011_support_fund_journal.sql', 3)
            else: self.fail_close = '20261011_support_fund_journal.sql'
            journal = {'phase': 'SQL_APPLYING'}
            folder = self.backups / mode; folder.mkdir()
            with self.assertRaises(m.MigrationError):
                m.execute_support_file('20261011_support_fund_journal.sql', SOURCES['20261011_support_fund_journal.sql'], folder,
                    'nexion_app', 'UNIT', 'UNIT', self.context, journal)
            self.assertNotIn('completed', journal)
            self.assertEqual(self.sessions[-1].closed[-1], False)
            self.fail_session = None

    def test_16_grant_failure_after_function_create_is_held_not_revoked_or_replayed(self):
        self.fail_root = '.execute-grant-1'
        with self.assertRaisesRegex(m.MigrationError, 'UNIT_ROOT_REPLY_LOST'):
            self.unit_apply()
        state = json.loads((self.db / 'state.json').read_bytes())
        self.assertIn('20261011_support_fund_operation_party.sql', state['scripts'])
        self.assertNotIn('20261011_support_fund_schema_reader.sql', state['scripts'])
        self.assertEqual(m.active()['phase'], 'SQL_FAILED')
        self.assertFalse(any(b'REVOKE ' in data or b'DROP ' in data for _, data, actor in self.sql if actor == 'root'))

    def test_17_root_context_change_rejects_before_privileged_write(self):
        wrong = {**self.context['root_context'], 'mode': 'OTHER'}
        with patch.object(m, 'server_context', return_value=wrong), self.assertRaisesRegex(m.MigrationError, 'ROOT_CONTEXT_CHANGED'):
            self.unit_apply()
        self.assertFalse(any(actor == 'root' for _, _, actor in self.sql))

    def test_19_program_metadata_mutation_and_wrong_definer_fail_full_postcheck(self):
        # Stop the setUp mock so this test exercises the actual implementation.
        actual = REAL_VERIFY
        name = '20261011_support_fund_schema_reader.sql'
        raw = SOURCES[name]
        part = next(p for p in m.support_segments(name, raw) if p['program'])
        row = {'name': part['program'], 'body': m.support_expected_body(name, part, raw), 'definer': m.SUPPORT_SCHEMA,
               'mode': self.context['mode'], 'charset': self.context['charset'], 'collation': self.context['collation'],
               'type': 'FUNCTION', 'return': 'tinyint unsigned', 'deterministic': 'NO', 'access': 'READS SQL DATA',
               'security': 'DEFINER', 'args': 0}
        with patch.object(m, 'probe', return_value=[row]):
            actual(name, part, raw, 'root', 'UNIT', self.context)
        for key, value in (('body', row['body'] + '\n'), ('definer', 'root@localhost'), ('args', 1),
                           ('access', 'MODIFIES SQL DATA'), ('security', 'INVOKER'), ('return', 'int')):
            with patch.object(m, 'probe', return_value=[{**row, key: value}]), self.subTest(key=key), self.assertRaises(m.MigrationError):
                actual(name, part, raw, 'root', 'UNIT', self.context)

    def live_probe_fixture(self, mutate=None):
        def read(actor, secret, sql, *, as_json=True):
            if sql == 'SELECT @@SESSION.binlog_format;': result = ['ROW']; kind = 'binlog'
            elif sql == 'SHOW GRANTS FOR CURRENT_USER();':
                result = (['GRANT ALL PRIVILEGES ON *.* TO `root`@`localhost` WITH GRANT OPTION',
                           'GRANT SET_ANY_DEFINER ON *.* TO `root`@`localhost`'] if actor == 'root' else self.app_grants)
                kind = 'root_grants' if actor == 'root' else 'app_grants'
            elif sql.startswith('SHOW GRANTS FOR ' + m.SUPPORT_GRANTEE):
                result = ['GRANT USAGE ON *.* TO `nexion_support_schema`@`localhost`']; kind = 'schema_grants'
            elif 'FROM mysql.user ' in sql:
                result = [{'user': 'nexion_support_schema', 'host': 'localhost', 'locked': 'Y'}]; kind = 'account'
            elif sql.startswith("SELECT JSON_OBJECT('fund_tables'"):
                result = {'fund_tables': 0, 'wallet_metadata': 0, 'reader': 0, 'wallet_triggers': 1}; kind = 'inventory'
            else: raise AssertionError('UNKNOWN_UNIT_PREFLIGHT_QUERY')
            return mutate(kind, result) if mutate else result
        return read

    def test_20_live_preflight_requires_locked_local_account_exact_app_and_capabilities(self):
        def run(mutate=None):
            with patch.object(m, 'probe', side_effect=self.live_probe_fixture(mutate)), patch.object(m, 'verify_earnings_trigger', return_value=True):
                return m.support_preflight('nexion_app', 'UNIT', 'UNIT')
        self.assertEqual(run()['principal'], m.SUPPORT_APP)
        for target, value in (('account', [{'user': 'nexion_support_schema', 'host': '%', 'locked': 'Y'}]),
                              ('account', [{'user': 'nexion_support_schema', 'host': 'localhost', 'locked': 'N'}]),
                              ('schema_grants', ['GRANT ALL PRIVILEGES ON `nexion`.* TO `nexion_support_schema`@`localhost`']),
                              ('root_grants', ['GRANT ALL PRIVILEGES ON *.* TO `root`@`localhost` WITH GRANT OPTION']),
                              ('binlog', ['STATEMENT'])):
            with self.subTest(target=target, value=value), self.assertRaises(m.MigrationError):
                run(lambda kind, current: value if kind == target else current)

    def test_21_live_partial_objects_and_unknown_wallet_trigger_reject_before_hold(self):
        for key in ('fund_tables', 'wallet_metadata', 'reader', 'wallet_triggers'):
            def mutate(kind, value):
                return {**value, key: 2} if kind == 'inventory' else value
            with patch.object(m, 'probe', side_effect=self.live_probe_fixture(mutate)), patch.object(m, 'verify_earnings_trigger', return_value=True), self.subTest(key=key), self.assertRaisesRegex(m.MigrationError, 'PARTIAL_OBJECTS_OR_UNKNOWN_TRIGGERS'):
                m.support_preflight('nexion_app', 'UNIT', 'UNIT')
        self.assertEqual(self.calls, [])

    def test_22_failed_live_preflight_cannot_start_hold_backup_or_sql(self):
        with patch.object(m, 'support_preflight', side_effect=m.MigrationError('UNIT_LIVE_DENIED')), self.assertRaisesRegex(m.MigrationError, 'UNIT_LIVE_DENIED'):
            m.apply(COMMIT)
        self.assertEqual(self.calls, [])
        self.assertEqual(self.sql, [])
        self.assertFalse((self.db / 'START_BLOCKED').exists())

    def test_23_backup_failure_never_runs_fixed_program_or_access(self):
        with patch.object(m, 'support_preflight', return_value=self.context), patch.object(m, 'backup_database', side_effect=m.MigrationError('UNIT_BACKUP_FAILED')), self.assertRaisesRegex(m.MigrationError, 'UNIT_BACKUP_FAILED'):
            m.apply(COMMIT)
        self.assertEqual(self.sql, [])
        self.assertEqual(self.sessions, [])
        self.assertEqual(self.calls, ['hold', 'start-old'])

    def test_24_future_migration_allows_only_exact_reader_execute_grant(self):
        allowed = m.support_show_grant(m.SUPPORT_EXECUTE_GRANTS[1])
        base = ['GRANT USAGE ON *.* TO `nexion_app`@`%`', self.app_grants[0]]
        def run(extra, user='nexion_app'):
            result = types.SimpleNamespace(returncode=0, stdout=('\n'.join(base + [extra]) + '\n').encode())
            with patch.object(m.subprocess, 'run', return_value=result):
                REAL_SCOPED(user, 'UNIT_NOT_SECRET')
        run(allowed)
        for row in (allowed + ' WITH GRANT OPTION', allowed.replace('`nexion`.', '`mysql`.'),
                    allowed.replace('schema_ready_v1', 'unknown'), allowed.replace('`%`', '`localhost`')):
            with self.subTest(row=row), self.assertRaisesRegex(m.MigrationError, 'PRIVILEGES_NOT_DATABASE_SCOPED'):
                run(row)
        with self.assertRaisesRegex(m.MigrationError, 'PRIVILEGES_NOT_DATABASE_SCOPED'):
            run(allowed, 'another_app')

    def test_25_trigger_table_event_and_definer_are_full_postcheck_boundaries(self):
        name = '20261011_support_fund_journal.sql'; raw = SOURCES[name]
        part = next(p for p in m.support_segments(name, raw) if p['program'])
        row = {'name': part['program'], 'body': m.support_expected_body(name, part, raw), 'definer': m.SUPPORT_APP,
               'mode': self.context['mode'], 'charset': self.context['charset'], 'collation': self.context['collation'],
               'table': 'nx_user_wallet', 'timing': 'AFTER', 'event': 'INSERT', 'orientation': 'ROW'}
        with patch.object(m, 'probe', return_value=[row]):
            REAL_VERIFY(name, part, raw, 'root', 'UNIT', self.context)
        for key, value in (('definer', m.SUPPORT_SCHEMA), ('table', 'other'), ('event', 'UPDATE'),
                           ('timing', 'BEFORE'), ('orientation', 'STATEMENT'), ('body', row['body'] + ' ')):
            with self.subTest(key=key), patch.object(m, 'probe', return_value=[{**row, key: value}]), self.assertRaises(m.MigrationError):
                REAL_VERIFY(name, part, raw, 'root', 'UNIT', self.context)



class PersistentTransportTests(unittest.TestCase):
    def child(self, args, *, stdin, stdout, stderr, env):
        owner = self
        class UnitChild:
            code = None
            def __init__(self):
                self.messages = []
                child = self
                class Pipe:
                    def write(self, data): child.messages.append(data)
                    def flush(self):
                        if owner.omit_ack: return
                        marker = re.search(rb"SELECT '(SUPPORT_BARRIER_[a-z0-9_]+)';", child.messages[-1])[1]
                        stdout.write(b'1\n' + marker + b'\n'); stdout.flush()
                    def close(self): child.code = 0
                self.stdin = Pipe()
            def poll(self): return self.code
            def wait(self, timeout): return self.code
            def kill(self): self.code = -9
        child = UnitChild(); self.children.append(child)
        return child

    def test_one_process_retains_session_across_multiple_barriers(self):
        self.omit_ack, self.children = False, []
        with tempfile.TemporaryDirectory(dir=HERE) as tmp, patch.object(m.subprocess, 'Popen', side_effect=self.child):
            session = m.SupportMysqlSession(Path(tmp), 'unit.sql', 'UNIT', 'NOT_A_SECRET')
            session.send(b'SET @unit=1; SELECT GET_LOCK(\'UNIT_ONLY\',0);')
            session.send(b'SELECT @unit; SELECT RELEASE_LOCK(\'UNIT_ONLY\');')
            session.close(success=True)
            self.assertEqual(len(self.children), 1)
            self.assertEqual(len(self.children[0].messages), 2)

    def test_timeout_kills_only_owned_session_and_does_not_retry(self):
        self.omit_ack, self.children = True, []
        with tempfile.TemporaryDirectory(dir=HERE) as tmp, patch.object(m.subprocess, 'Popen', side_effect=self.child), patch.object(m.time, 'monotonic', side_effect=[0, 0, 999]), patch.object(m.time, 'sleep'):
            session = m.SupportMysqlSession(Path(tmp), 'unit.sql', 'UNIT', 'NOT_A_SECRET')
            with self.assertRaisesRegex(m.MigrationError, 'SESSION_TIMEOUT_UNCERTAIN'):
                session.send(b'SELECT 1;', timeout=1)
            session.close(success=False)
            self.assertEqual(len(self.children), 1)
            self.assertEqual(self.children[0].code, -9)

if __name__ == "__main__":
    unittest.main()

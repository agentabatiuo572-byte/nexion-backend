"""Fixed Compute V2 lane: hermetic safety/DDL acknowledgement-loss fixtures, no host or network."""
from contextlib import redirect_stdout
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[2]
SOURCE = REPO / 'deploy/public-test/compute-v2-database-migrations.py'
spec = importlib.util.spec_from_file_location('compute_v2_fixed_migrations', SOURCE)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
RAW = (REPO / 'scripts/migrations/20261101_compute_v2_settlement.sql').read_bytes()
COMMIT = 'a' * 40


class ComputeV2FixedMigrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.root, self.backups = self.directory / 'state', self.directory / 'backups'
        self.root.mkdir(); self.backups.mkdir()
        self.config, self.guard, self.permit = self.directory / 'config.json', self.directory / 'guard', self.directory / 'permit'
        self.config.write_text(json.dumps({'allow_fixed_compute_v2_trigger_install': True}))
        self.guard.write_text(m.GUARD_TEXT)
        self.state = {'version': 1, 'database': 'nexion', 'scripts': {}}
        m.save(self.root / 'state.json', self.state)
        self.parts = m.compute_v2_segments(RAW)
        self.scripts = {m.COMPUTE_V2_FILE: RAW}
        self.events, self.writes, self.tables, self.triggers = [], [], [], []
        self.app = {'version': '8.4.7', 'database': 'nexion', 'principal': 'nexion_app@%',
                    'log_bin': 1, 'trust': 0, 'mode': 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION',
                    'charset': 'utf8mb4', 'collation': 'utf8mb4_0900_ai_ci'}
        self.root_context = {**self.app, 'principal': 'root@localhost'}
        self.app_grants = ['GRANT ALL PRIVILEGES ON `nexion`.* TO `nexion_app`@`%`']
        self.root_grants = ['GRANT ALL PRIVILEGES ON *.* TO `root`@`localhost`',
                            'GRANT SET_ANY_DEFINER ON *.* TO `root`@`localhost`']
        self.dependencies = {'tables': 9, 'wallet': 2, 'fund_ready': 1}
        self.binlog = ['ROW']; self.fault = None; self.after_write = None
        values = {'ROOT': self.root, 'BACKUPS': self.backups, 'GUARD': self.guard, 'PERMIT': self.permit, 'HOST_CONFIG': self.config}
        for name, value in values.items(): self.start(patch.object(m, name, value))
        self.start(patch.object(m, 'trusted'))
        self.start(patch.object(m, 'credentials', return_value=('nexion_app', 'fixture', 'fixture-root')))
        self.start(patch.object(m, 'scoped_account'))
        self.start(patch.object(m, 'download_catalog', side_effect=lambda _: self.scripts))
        self.start(patch.object(m, 'server_context', side_effect=lambda actor, _: copy.deepcopy(self.root_context if actor == 'root' else self.app)))
        self.start(patch.object(m, 'probe', side_effect=self.probe))
        self.start(patch.object(m, 'stop_backend', side_effect=self.stop))
        self.start(patch.object(m, 'start_previous', side_effect=lambda: self.events.append('start-old')))
        self.start(patch.object(m, 'backup_database', side_effect=self.backup))
        self.start(patch.object(m, 'execute_sql', side_effect=self.execute))
        self.start(patch.object(m.subprocess, 'run', side_effect=AssertionError('REAL_COMMAND_FORBIDDEN')))
        self.start(patch.object(m.urllib.request, 'urlopen', side_effect=AssertionError('REAL_NETWORK_FORBIDDEN')))
        output = redirect_stdout(io.StringIO()); output.__enter__(); self.addCleanup(output.__exit__, None, None, None)

    def start(self, patcher):
        self.addCleanup(patcher.stop); return patcher.start()

    def stop(self):
        self.events.append('stop'); m.save(self.root / 'START_BLOCKED', {'reason': 'fixture'})

    def backup(self, folder, _):
        self.events.append('backup'); return {'path': str(folder / 'full.sql.gz'), 'sha256': 'b' * 64, 'uncompressed_bytes': 20000}

    def probe(self, actor, _, sql, **kwargs):
        if sql == 'SELECT @@SESSION.binlog_format;': return self.binlog
        if sql == 'SHOW GRANTS FOR CURRENT_USER();': return self.root_grants if actor == 'root' else self.app_grants
        if sql == m.COMPUTE_V2_DEPENDENCY_SQL: return self.dependencies
        if sql == m.COMPUTE_V2_TABLE_SQL: return copy.deepcopy(self.tables)
        if sql == m.COMPUTE_V2_TRIGGER_SQL: return copy.deepcopy(self.triggers)
        raise AssertionError('Unexpected query: ' + sql)

    def row(self, part):
        return {'name': part['program'], 'body': m.COMPUTE_V2_BODY, 'definer': m.COMPUTE_V2_DEFINER,
                'table': part['table'], 'timing': 'BEFORE', 'event': part['event'], 'orientation': 'ROW',
                **{key: self.app[key] for key in ('mode', 'charset', 'collation')}}

    def execute(self, name, data, folder, actor, _):
        self.writes.append((actor, name, data)); self.events.append(name)
        journal = json.loads((self.root / 'active.json').read_text())
        self.assertEqual(journal['phase'], 'SQL_APPLYING')
        self.assertIn('backup', journal)
        if actor == 'nexion_app':
            self.assertEqual(name, m.COMPUTE_V2_FILE + '.app')
            self.assertEqual(data, m.support_session_settings(self.app) + self.parts[0]['data'])
            self.tables = list(m.COMPUTE_V2_TABLES)
        else:
            self.assertEqual(actor, 'root')
            part = next(part for part in self.parts[1:] if name == m.COMPUTE_V2_FILE + '.' + part['program'])
            self.assertEqual(journal['compute_v2_trigger']['name'], part['program'])
            self.assertEqual(data, m.support_session_settings(self.app) + m.compute_v2_template(part))
            self.triggers.append(self.row(part))
        if self.after_write: self.after_write(actor, name)
        if self.fault == name: raise m.MigrationError('ACK_LOST_AFTER_PHYSICAL_DDL')

    def test_actual_baseline_other_functions_classes_and_old_lanes_are_byte_preserved(self):
        raw = SOURCE.read_bytes()
        sections = ((raw[:raw.index(b'# First install only. Exact immutable')], '0db3d7211936dd78dcde4ae8a3978bc8bc9d85143d8e6349f67b038bb7854bdc'),
                    (raw[raw.index(b'def stop_backend():'):raw.index(b'def apply(')], 'f13cc8387064242d1fd0f17e9b9205ddf5a38fb4382836d23202b211c33c9b92'),
                    (raw[raw.index(b'def complete_release():'):], '9d79a7eee3c5cbca88347564d26dbef4f8a64775aa0068ece3d020eef7910af6'))
        for data, expected in sections: self.assertEqual(hashlib.sha256(data).hexdigest(), expected)

    def test_exact_blob_and_eight_offsets_produce_only_qualified_fixed_root_templates(self):
        self.assertEqual(m.compute_v2_prepare(list(self.scripts.items())), self.parts)
        self.assertEqual(len(self.parts), 9)
        for part in self.parts[1:]:
            template = m.compute_v2_template(part)
            self.assertTrue(template.startswith(b'CREATE DEFINER=`nexion_app`@`%` TRIGGER IF NOT EXISTS `nexion`.`'))
            self.assertIn((' ON `nexion`.`' + part['table'] + '` FOR EACH ROW ').encode(), template)
            self.assertTrue(template.endswith((m.COMPUTE_V2_BODY + ';\n').encode()))
            self.assertEqual(template.count(b';'), 1)

    def test_changed_blob_offset_name_tail_or_duplicate_source_cannot_enter_lane(self):
        for changed in (RAW + b'SELECT 1;', RAW.replace(b'BEFORE UPDATE', b'AFTER  UPDATE', 1), RAW[:-1], b' ' + RAW):
            with self.subTest(length=len(changed)), self.assertRaisesRegex(m.MigrationError, 'FIXED_SOURCE_CHANGED'):
                m.compute_v2_prepare([(m.COMPUTE_V2_FILE, changed)])
        with self.assertRaisesRegex(m.MigrationError, 'DUPLICATE_SOURCE'):
            m.compute_v2_prepare([(m.COMPUTE_V2_FILE, RAW)] * 2)
        self.assertIsNone(m.compute_v2_prepare([('other.sql', RAW)]))
        with self.assertRaisesRegex(m.MigrationError, 'UNREVIEWED_STORED_PROGRAM'):
            m.migration_preflight([('other.sql', RAW)], None, None, None)
        with patch.object(m, 'COMPUTE_V2_PROGRAMS', ((m.COMPUTE_V2_PROGRAMS[0][0], 5265, *m.COMPUTE_V2_PROGRAMS[0][2:]), *m.COMPUTE_V2_PROGRAMS[1:])):
            with self.assertRaisesRegex(m.MigrationError, 'FIXED_PROGRAM_CHANGED'): m.compute_v2_segments(RAW)
        self.assertEqual(self.events, [])

    def test_opt_in_is_exact_boolean_and_missing_false_or_string_rejects_before_hold(self):
        for value in ({}, {'allow_fixed_compute_v2_trigger_install': False}, {'allow_fixed_compute_v2_trigger_install': 'true'}):
            self.config.write_text(json.dumps(value))
            with self.assertRaises(m.MigrationError): m.apply(COMMIT)
            self.assertEqual(self.events, []); self.assertIsNone(m.active())

    def test_context_definer_version_binlog_grants_and_fund_dependencies_are_required(self):
        cases = [('app', 'principal', 'other@%'), ('app', 'version', '8.0.45'), ('app', 'trust', 1),
                 ('root_context', 'principal', 'root@foreign'), ('root_context', 'log_bin', 0),
                 ('root_context', 'collation', 'utf8mb4_bin')]
        for target, key, value in cases:
            values = getattr(self, target); old = values[key]; values[key] = value
            with self.subTest(target=target, key=key), self.assertRaisesRegex(m.MigrationError, 'LIVE_CONTEXT_REJECTED'):
                m.apply(COMMIT)
            values[key] = old
        self.binlog = ['STATEMENT']
        with self.assertRaisesRegex(m.MigrationError, 'BINLOG_FORMAT'): m.apply(COMMIT)
        self.binlog = ['ROW']; old = self.root_grants; self.root_grants = old[:1]
        with self.assertRaisesRegex(m.MigrationError, 'CREATOR_CAPABILITIES'): m.apply(COMMIT)
        self.root_grants = old; self.app_grants = ['GRANT SELECT ON `nexion`.* TO `nexion_app`@`%`']
        with self.assertRaisesRegex(m.MigrationError, 'APP_CAPABILITIES'): m.apply(COMMIT)
        self.app_grants = ['GRANT ALL PRIVILEGES ON `nexion`.* TO `nexion_app`@`%`']; self.dependencies['fund_ready'] = 0
        with self.assertRaisesRegex(m.MigrationError, 'DEPENDENCIES_NOT_READY'): m.apply(COMMIT)
        self.assertEqual(self.events, []); self.assertIsNone(m.active())

    def test_existing_table_or_same_named_trigger_cannot_be_swallowed_by_if_not_exists(self):
        self.tables = [m.COMPUTE_V2_TABLES[0]]
        with self.assertRaisesRegex(m.MigrationError, 'PARTIAL_TABLES'): m.apply(COMMIT)
        self.tables = []; self.triggers = [self.row(self.parts[1])]
        with self.assertRaisesRegex(m.MigrationError, 'TRIGGER_INVENTORY'): m.apply(COMMIT)
        self.assertEqual(self.events, []); self.assertIsNone(m.active())

    def test_every_metadata_field_and_unknown_inventory_is_verified_exactly(self):
        context = {**self.app, 'root_context': self.root_context}
        for key in ('name', 'body', 'definer', 'table', 'timing', 'event', 'orientation', 'mode', 'charset', 'collation'):
            row = self.row(self.parts[1]); row[key] = 'changed'; self.triggers = [row]
            with self.subTest(field=key), self.assertRaises(m.MigrationError):
                m.compute_v2_verify_triggers(self.parts, [self.parts[1]['program']], 'fixture-root', context)
        self.triggers = [self.row(self.parts[1])] * 2
        with self.assertRaisesRegex(m.MigrationError, 'INVENTORY'): m.compute_v2_verify_triggers(self.parts, [self.parts[1]['program']], 'fixture-root', context)

    def test_full_backup_app_tables_then_eight_fixed_root_creates_and_once_only_candidate_start(self):
        journal = m.apply(COMMIT)
        self.assertEqual(self.events[:2], ['stop', 'backup'])
        self.assertEqual([actor for actor, _, _ in self.writes], ['nexion_app'] + ['root'] * 8)
        self.assertEqual(len(self.triggers), 8); self.assertEqual(journal['phase'], 'SQL_APPLIED')
        self.assertTrue((self.root / 'START_BLOCKED').exists()); self.assertFalse(self.permit.exists())
        m.allow_candidate_start(); self.assertTrue(self.permit.exists()); m.revoke_candidate_start()
        before = list(self.events); self.assertEqual(m.apply(COMMIT), journal); self.assertEqual(self.events, before)
        m.complete_release(); self.assertIsNone(m.active()); self.assertFalse((self.root / 'START_BLOCKED').exists())
        self.assertEqual(json.loads((self.root / 'state.json').read_text())['scripts'][m.COMPUTE_V2_FILE]['sha256'], m.COMPUTE_V2_SHA256)

    def test_backup_failure_restores_old_service_before_any_ddl(self):
        with patch.object(m, 'backup_database', side_effect=m.MigrationError('BACKUP_FAILURE')):
            with self.assertRaisesRegex(m.MigrationError, 'BACKUP_FAILURE'): m.apply(COMMIT)
        self.assertEqual(self.events, ['stop', 'start-old']); self.assertEqual(self.writes, []); self.assertIsNone(m.active())

    def test_each_unknown_ddl_ack_keeps_sql_failed_hold_without_old_start_or_blind_replay(self):
        targets = [m.COMPUTE_V2_FILE + '.app', *(m.COMPUTE_V2_FILE + '.' + part['program'] for part in self.parts[1:])]
        for index, target in enumerate(targets, 1):
            with self.subTest(target=target):
                commit = f'{index:x}' * 40
                self.tables, self.triggers, self.events, self.writes = [], [], [], []
                (self.root / 'active.json').unlink(missing_ok=True)
                (self.root / 'START_BLOCKED').unlink(missing_ok=True)
                self.fault = target
                with self.assertRaisesRegex(m.MigrationError, 'ACK_LOST'): m.apply(commit)
                self.assertEqual(m.active()['phase'], 'SQL_FAILED'); self.assertTrue((self.root / 'START_BLOCKED').exists())
                self.assertNotIn('start-old', self.events)
                self.assertEqual(json.loads((self.root / 'state.json').read_text()), self.state)
                before = list(self.events)
                with self.assertRaisesRegex(m.MigrationError, 'PARTIAL_OR_INTERRUPTED'): m.apply(commit)
                self.assertEqual(self.events, before)

    def test_context_drift_after_table_write_stops_before_any_root_create(self):
        self.after_write = lambda actor, _: self.app.update(mode='ANSI') if actor == 'nexion_app' else None
        with self.assertRaisesRegex(m.MigrationError, 'APP_CONTEXT_CHANGED'): m.apply(COMMIT)
        self.assertEqual(len(self.writes), 1); self.assertEqual(m.active()['phase'], 'SQL_FAILED')

    def test_postcheck_drift_is_not_marked_applied_and_does_not_restart_old_code(self):
        self.after_write = lambda actor, _: self.triggers[-1].update(body='changed') if actor == 'root' else None
        with self.assertRaisesRegex(m.MigrationError, 'POSTCHECK_CHANGED'): m.apply(COMMIT)
        self.assertEqual(len(self.writes), 2); self.assertEqual(m.active()['phase'], 'SQL_FAILED')
        self.assertNotIn('start-old', self.events)
        self.assertNotIn(m.COMPUTE_V2_FILE, json.loads((self.root / 'state.json').read_text())['scripts'])

    def test_unreviewed_other_stored_program_still_rejects_before_backup(self):
        self.scripts['20261102_unreviewed.sql'] = b'CREATE TRIGGER arbitrary BEFORE DELETE ON nx_user FOR EACH ROW SET @x=1;'
        with self.assertRaisesRegex(m.MigrationError, 'UNREVIEWED_STORED_PROGRAM'): m.apply(COMMIT)
        self.assertEqual(self.events, []); self.assertIsNone(m.active())


if __name__ == '__main__': unittest.main()

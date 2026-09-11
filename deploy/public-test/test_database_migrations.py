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


original_scoped_account = m.scoped_account
original_credentials = m.credentials

if __name__ == '__main__':
    unittest.main()

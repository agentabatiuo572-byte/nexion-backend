"""Isolated upgrade failure injection; no real host commands or credentials."""
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import update_database_migrations as u


class UpgradeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.install, self.stage = base / 'install', base / 'stages/new'
        self.root, self.backups, self.guard = base / 'state', base / 'backups', base / 'guard.conf'
        for path in (self.install, self.stage, self.root, self.backups):
            path.mkdir(parents=True)
        files = {name: b'# old\n' for name in ('trusted_entry.py', 'release_broker.py')}
        files.update({f'unchanged{n}': b'fixed\n' for n in range(10)})
        for directory in (self.install, self.stage):
            for name, data in files.items():
                (directory / name).write_bytes(data)
        for name in u.CHANGED:
            (self.stage / name).write_bytes(b'# new\n')
        (self.stage / 'database_migrations.py').write_bytes(b'def baseline(*args): pass\n')
        for directory in (self.install, self.stage):
            manifest = {'version': 1, 'files': {p.name: u.digest(p.read_bytes()) for p in directory.iterdir()}}
            (directory / 'runtime-lock.json').write_text(json.dumps(manifest))
        self.old_pin = u.digest((self.install / 'runtime-lock.json').read_bytes())
        self.pin = u.digest((self.stage / 'runtime-lock.json').read_bytes())
        (self.install / 'config.json').write_text(json.dumps({'trusted_files': {
            'release_broker.py': u.digest(files['release_broker.py'])}, 'job_hashes': {'unchanged': 'unchanged'}}))
        (self.root / 'lock').touch()
        self.old = {p.name: p.read_bytes() for p in self.install.iterdir()}
        values = {'INSTALL': self.install, 'ROOT': self.root, 'BACKUPS': self.backups,
                  'STAGES': self.stage.parent, 'GUARD': self.guard, 'OLD_LOCK': self.old_pin,
                  'sys': SimpleNamespace(flags=SimpleNamespace(isolated=1))}
        for name, value in values.items():
            p = patch.object(u, name, value); p.start(); self.addCleanup(p.stop)
        for name in ('trusted', 'sync'):
            p = patch.object(u, name); p.start(); self.addCleanup(p.stop)
        p = patch.object(u.os, 'geteuid', return_value=0, create=True); p.start(); self.addCleanup(p.stop)
        p = patch.dict('sys.modules', {'fcntl': SimpleNamespace(flock=lambda *_: None, LOCK_EX=1, LOCK_NB=2)})
        p.start(); self.addCleanup(p.stop)
        p = patch.object(u, 'command', return_value=b'active')
        self.command = p.start(); self.addCleanup(p.stop)

    def test_success_installs_only_reviewed_runtime_and_guard(self):
        u.update(self.stage, self.pin)
        self.assertEqual(self.guard.read_text(), u.GUARD_TEXT)
        u.closure(self.install, self.pin, 13)
        cfg = json.loads((self.install / 'config.json').read_text())
        self.assertEqual(cfg['job_hashes'], {'unchanged': 'unchanged'})
        self.assertIn('database_migrations.py', cfg['trusted_files'])
        self.command.assert_called_with('systemctl', 'start', 'nexgrid-release.timer')

    def test_partial_install_restores_exact_old_files_and_removes_own_guard(self):
        real = u.atomic
        count = 0
        def fail_once(*args, **kwargs):
            nonlocal count
            count += 1
            if count == 3:
                raise RuntimeError('INJECTED_WRITE_FAILURE')
            return real(*args, **kwargs)
        with patch.object(u, 'atomic', side_effect=fail_once):
            with self.assertRaisesRegex(RuntimeError, 'INJECTED'):
                u.update(self.stage, self.pin)
        self.assertEqual({p.name: p.read_bytes() for p in self.install.iterdir()}, self.old)
        self.assertFalse(self.guard.exists())
        self.command.assert_called_with('systemctl', 'start', 'nexgrid-release.timer')

    def test_wrong_pin_or_shared_release_blocks_before_writes(self):
        with self.assertRaisesRegex(RuntimeError, 'PIN_MISMATCH'):
            u.update(self.stage, '0' * 64)
        self.command.assert_not_called()
        (self.root / 'transaction.json').write_text('{}')
        with self.assertRaisesRegex(RuntimeError, 'RELEASE_BUSY'):
            u.update(self.stage, self.pin)
        self.assertEqual({p.name: p.read_bytes() for p in self.install.iterdir()}, self.old)
        self.assertFalse(self.guard.exists())

    def test_rollback_failure_does_not_resume_timer(self):
        with patch.object(u, 'atomic', side_effect=RuntimeError('PERSISTENT_WRITE_FAILURE')):
            with self.assertRaises(RuntimeError):
                u.update(self.stage, self.pin)
        self.assertNotIn(('systemctl', 'start', 'nexgrid-release.timer'),
                         [call.args for call in self.command.call_args_list])


if __name__ == '__main__':
    unittest.main()

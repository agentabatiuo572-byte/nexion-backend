"""One-time maintenance validation, never operate on the actual host."""
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import update_independent_releases as u


class PolicyUpdateTests(unittest.TestCase):
    def test_only_two_reviewed_runtime_changes_allowed(self):
        old = {'files': {name: 'old' for name in ['ci-build.sh', 'release_broker.py', 'policy']}}
        new = {'files': {**old['files'], 'ci-build.sh': 'new', 'release_broker.py': 'new'}}
        u.validate_change(old, new)
        for changed in [{**new['files'], 'policy': 'new'}, {**new['files'], 'new-file': 'new'}, old['files']]:
            with self.assertRaises(RuntimeError):
                u.validate_change(old, {'files': changed})

    def test_bind_script_update_keeps_inode_and_truncates_old_tail(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'script.sh'
            path.write_bytes(b'old script with longer tail')
            inode = path.stat().st_ino
            with patch.object(u, 'BUILD_SCRIPT', path), patch.object(u, 'trusted'):
                u.replace_bound_script(b'new script\n')
            self.assertEqual(path.read_bytes(), b'new script\n')
            self.assertEqual(path.stat().st_ino, inode)

    def test_active_build_is_rejected_before_any_command(self):
        with tempfile.TemporaryDirectory() as temp:
            jobs = Path(temp)
            for kind in ['backend', 'pc', 'uniapp']:
                (jobs / f'nexgrid-{kind}-main/builds/1').mkdir(parents=True)
            path = jobs / 'nexgrid-backend-main/builds/1'
            (path / 'build.xml').write_bytes(b'<flow-build><completed>false</completed></flow-build>')
            with patch.object(u, 'JOBS', jobs), patch.object(u, 'command') as command:
                with self.assertRaisesRegex(RuntimeError, 'WAIT_FOR_IDLE_CI'):
                    u.assert_idle()
                command.assert_not_called()

    def test_runtime_closure_requires_external_lock_pin(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            (path / 'runtime-lock.json').write_text('{}')
            with patch.object(u, 'trusted'):
                with self.assertRaisesRegex(RuntimeError, 'LOCK_PIN_MISMATCH'):
                    u.closure(path, '0'*64)


class PolicyUpdateRecoveryTests(unittest.TestCase):
    def setUp(self):
        import io
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.install, self.stage = self.root / 'runtime', self.root / 'staged/new'
        self.backups, self.state = self.root / 'backups', self.root / 'state'
        for path in [self.install, self.stage, self.backups, self.state]:
            path.mkdir(parents=True)
        files = {'ci-build.sh': b'#!/bin/bash\ntrue\n', 'release_broker.py': b'# old\n'}
        files.update({f'file-{n}': b'unchanged' for n in range(10)})
        for directory in [self.install, self.stage]:
            for name, data in files.items():
                (directory / name).write_bytes(data)
        (self.stage / 'ci-build.sh').write_bytes(b'#!/bin/bash\necho new\n')
        (self.stage / 'release_broker.py').write_bytes(b'# new\n')
        for directory in [self.install, self.stage]:
            data = {'version': 1, 'files': {n: u.sha((directory / n).read_bytes()) for n in files}}
            (directory / 'runtime-lock.json').write_bytes(json.dumps(data).encode())
        self.old_lock = u.sha((self.install / 'runtime-lock.json').read_bytes())
        self.new_lock = u.sha((self.stage / 'runtime-lock.json').read_bytes())
        self.script = self.root / 'bind.sh'
        self.script.write_bytes(files['ci-build.sh'])
        config = {'trusted_files': {'release_broker.py': u.sha(files['release_broker.py'])}, 'job_hashes': {}}
        (self.install / 'config.json').write_text(json.dumps(config))
        (self.state / 'lock').touch()
        self.old = {p.name: p.read_bytes() for p in self.install.iterdir()}
        for name, value in [('INSTALL', self.install), ('ROOT', self.state), ('BACKUPS', self.backups),
                            ('STAGE_PARENT', self.stage.parent), ('BUILD_SCRIPT', self.script),
                            ('OLD_LOCK', self.old_lock), ('sys', SimpleNamespace(flags=SimpleNamespace(isolated=1)))]:
            p = patch.object(u, name, value); p.start(); self.addCleanup(p.stop)
        for name in ['trusted', 'sync', 'assert_idle']:
            p = patch.object(u, name); obj = p.start(); self.addCleanup(p.stop)
            setattr(self, name, obj)
        p = patch.object(u.os, 'geteuid', return_value=0, create=True); p.start(); self.addCleanup(p.stop)
        p = patch.dict('sys.modules', {'fcntl': SimpleNamespace(flock=lambda *_: None, LOCK_EX=1, LOCK_NB=2)})
        p.start(); self.addCleanup(p.stop)
        self.commands = []
        def command(*args):
            self.commands.append(args)
            return 'active' if args == ('systemctl', 'is-active', 'nexgrid-release.timer') else ''
        p = patch.object(u, 'command', side_effect=command)
        self.command = p.start(); self.addCleanup(p.stop)
        self.output = io.StringIO()
        p = patch('sys.stdout', self.output); p.start(); self.addCleanup(p.stop)

    def test_success_preserves_inode_and_resumes_agent_timer(self):
        inode = self.script.stat().st_ino
        u.update(self.stage, self.new_lock)
        self.assertEqual(self.script.stat().st_ino, inode)
        self.assertEqual(self.script.read_bytes(), (self.stage / 'ci-build.sh').read_bytes())
        self.assertEqual(self.commands[-2:], [('docker', 'unpause', 'nexgrid-ci-agent'),
                                             ('systemctl', 'start', 'nexgrid-release.timer')])

    def test_post_pause_busy_check_restores_without_any_file_changes(self):
        self.assert_idle.side_effect = [None, RuntimeError('WAIT_FOR_IDLE_CI')]
        with self.assertRaisesRegex(RuntimeError, 'WAIT_FOR_IDLE_CI'):
            u.update(self.stage, self.new_lock)
        self.assertEqual({p.name: p.read_bytes() for p in self.install.iterdir()}, self.old)
        self.assertIn(('docker', 'unpause', 'nexgrid-ci-agent'), self.commands)
        self.assertEqual(list(self.backups.iterdir()), [])

    def test_partial_runtime_replacement_restores_old_closure_and_bound_script(self):
        atomic = u.atomic
        count = 0
        def fail_once(*args, **kwargs):
            nonlocal count
            count += 1
            if count == 2:
                raise RuntimeError('FIXTURE_WRITE_FAILED')
            return atomic(*args, **kwargs)
        with patch.object(u, 'atomic', side_effect=fail_once):
            with self.assertRaisesRegex(RuntimeError, 'FIXTURE_WRITE_FAILED'):
                u.update(self.stage, self.new_lock)
        self.assertEqual({p.name: p.read_bytes() for p in self.install.iterdir()}, self.old)
        self.assertEqual(self.script.read_bytes(), self.old['ci-build.sh'])
        self.assertIn(('systemctl', 'start', 'nexgrid-release.timer'), self.commands)

    def test_unpause_or_timer_start_failure_has_explicit_recovery_message(self):
        original = self.command.side_effect
        def fail_unpause(*args):
            if args == ('docker', 'unpause', 'nexgrid-ci-agent'):
                raise RuntimeError('FIXTURE_UNPAUSE_FAILURE')
            return original(*args)
        self.command.side_effect = fail_unpause
        with self.assertRaisesRegex(RuntimeError, 'FIXTURE_UNPAUSE_FAILURE'):
            u.update(self.stage, self.new_lock)
        self.assertIn('OPERATOR_RECOVERY_REQUIRED', self.output.getvalue())
        self.assertNotIn(('systemctl', 'start', 'nexgrid-release.timer'), self.commands)


if __name__ == '__main__':
    unittest.main()

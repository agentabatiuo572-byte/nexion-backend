"""Hermetic operator fixtures: no host commands, runtime imports, DB or network."""
import importlib.util
import builtins
import json
from pathlib import Path
import stat
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import update_fixed_earnings_trigger as u


class FixedTriggerUpdateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.install, self.stage = base / 'install', base / 'stages/new'
        self.root, self.backups = base / 'state', base / 'backups'
        self.jobs, self.guard, self.permit = base / 'jobs', base / 'guard.conf', base / 'permit'
        for path in (self.install, self.stage, self.root / 'migrations', self.backups, self.jobs):
            path.mkdir(parents=True)
        self.old_sources = {name: ('# host ' + name + '\n').encode() for name in u.FILES}
        self.new_source = b'raise AssertionError("MIGRATION_SOURCE_MUST_NEVER_BE_IMPORTED")\n'
        for directory in (self.install, self.stage):
            for name, data in self.old_sources.items():
                (directory / name).write_bytes(data)
                (directory / name).chmod(0o640 if name == u.CHANGED else 0o644)
        (self.stage / u.CHANGED).write_bytes(self.new_source)
        self.make_lock(self.install)
        self.make_lock(self.stage)
        self.old_pin = u.digest((self.install / 'runtime-lock.json').read_bytes())
        self.new_pin = u.digest((self.stage / 'runtime-lock.json').read_bytes())
        self.external = base / 'external.conf'
        self.external.write_bytes(b'host policy\n')
        jobs = {}
        for kind in ('backend', 'pc', 'uniapp'):
            job = self.jobs / ('nexgrid-' + kind + '-test') / 'config.xml'
            job.parent.mkdir()
            job.write_bytes(('<job>' + kind + '</job>\n').encode())
            jobs[kind] = u.digest(job.read_bytes())
        self.config = {'trusted_files': {name: u.digest(data) for name, data in self.old_sources.items()},
                       'trusted_external_files': {str(self.external): u.digest(self.external.read_bytes())},
                       'job_hashes': jobs,
                       'policy_sha256': u.digest(self.old_sources['public-test-policy.properties']),
                       'pc_image': 'retained', 'schema': {'backend': 'retained'}}
        (self.install / 'config.json').write_bytes(json.dumps(self.config).encode())
        (self.install / 'config.json').chmod(0o600)
        (self.root / 'lock').touch()
        (self.root / 'state.json').write_bytes(b'{"release":"retained"}\n')
        (self.root / 'migrations/state.json').write_bytes(b'{"scripts":{"retained":"APPLIED"}}\n')
        self.guard.write_bytes(u.GUARD_TEXT)
        self.old = self.install_snapshot()
        self.retained = self.retained_snapshot()
        values = {'INSTALL': self.install, 'ROOT': self.root, 'STAGES': self.stage.parent,
                  'BACKUPS': self.backups, 'JOBS': self.jobs, 'GUARD': self.guard, 'PERMIT': self.permit,
                  'OLD_LOCK': self.old_pin, 'NEW_MIGRATIONS_SHA': u.digest(self.new_source),
                  'sys': SimpleNamespace(flags=SimpleNamespace(isolated=1))}
        for name, value in values.items():
            self.start_patch(patch.object(u, name, value))
        self.start_patch(patch.object(u, 'trusted'))
        self.start_patch(patch.object(u, 'sync'))
        self.start_patch(patch.object(u.os, 'geteuid', return_value=0, create=True))
        self.flock = self.start_patch(patch.dict(sys.modules, {'fcntl': SimpleNamespace(
            flock=lambda *_: None, LOCK_EX=1, LOCK_NB=2)}))
        self.timer = 'active'
        self.command = self.start_patch(patch.object(u, 'command', side_effect=self.fake_command))
        self.start_patch(patch.object(u.subprocess, 'run', side_effect=AssertionError('REAL_COMMAND_FORBIDDEN')))

    def start_patch(self, patcher):
        value = patcher.start()
        self.addCleanup(patcher.stop)
        return value

    def make_lock(self, directory):
        manifest = {'version': 1, 'files': {name: u.digest((directory / name).read_bytes())
                                          for name in sorted(u.FILES)}}
        (directory / 'runtime-lock.json').write_bytes((json.dumps(manifest, indent=2) + '\n').encode())

    def install_snapshot(self):
        return {p.name: (p.read_bytes(), stat.S_IMODE(p.stat().st_mode)) for p in self.install.iterdir()}

    def retained_snapshot(self):
        paths = [self.guard, self.external, self.root / 'state.json', self.root / 'migrations/state.json']
        paths.extend(self.jobs.rglob('config.xml'))
        return {str(p): p.read_bytes() for p in paths}

    def fake_command(self, *args):
        if args == (u.SYSTEMCTL, 'is-active', 'nexgrid-release.timer'):
            return self.timer.encode()
        if args == (u.SYSTEMCTL, 'show', '--property=ActiveState', '--value', 'nexgrid-release.service'):
            return b'inactive'
        if args == (u.SYSTEMCTL, 'show', '--property=ActiveState', '--value', 'nexgrid-release.timer'):
            return self.timer.encode()
        if args == (u.SYSTEMCTL, 'stop', 'nexgrid-release.timer'):
            self.timer = 'inactive'
            return b''
        if args == (u.SYSTEMCTL, 'start', 'nexgrid-release.timer'):
            self.timer = 'active'
            return b''
        raise AssertionError('UNEXPECTED_COMMAND_' + args[0])

    def assert_no_timer_start(self):
        self.assertNotIn((u.SYSTEMCTL, 'start', 'nexgrid-release.timer'),
                         [call.args for call in self.command.call_args_list])

    def test_success_defaults_flag_off_and_preserves_other_twelve_files_and_state(self):
        u.update(self.stage, self.new_pin)
        u.closure(self.install, self.new_pin)
        config = json.loads((self.install / 'config.json').read_bytes())
        expected = {**self.config, u.FLAG: False,
                    'trusted_files': {**self.config['trusted_files'], u.CHANGED: u.digest(self.new_source)}}
        self.assertEqual(config, expected)
        for name in u.FILES - {u.CHANGED}:
            self.assertEqual(self.install_snapshot()[name], self.old[name])
        self.assertEqual(self.retained_snapshot(), self.retained)
        self.assertEqual(stat.S_IMODE((self.install / u.CHANGED).stat().st_mode), self.old[u.CHANGED][1])
        backup = next(self.backups.iterdir())
        receipt = json.loads((backup / 'backup-manifest.json').read_bytes())
        self.assertEqual(set(receipt['files']), set(u.MUTABLE))
        for name in u.MUTABLE:
            self.assertEqual((backup / name).read_bytes(), self.old[name][0])
            self.assertEqual(receipt['files'][name]['mode'], self.old[name][1])
        self.assertEqual(self.timer, 'active')

    def test_explicit_enable_is_the_only_enabled_path(self):
        u.update(self.stage, self.new_pin, enable_fixed_earnings_trigger=True)
        self.assertIs(json.loads((self.install / 'config.json').read_bytes())[u.FLAG], True)
        self.assertEqual(self.retained_snapshot(), self.retained)

    def test_each_partial_write_or_validation_failure_restores_bytes_modes_and_absent_flag(self):
        for failure_at in (1, 2, 3, 4):
            with self.subTest(failure_at=failure_at):
                # Give each iteration fresh backup names; installed baseline is restored.
                for path in self.backups.iterdir():
                    for child in path.iterdir():
                        child.unlink()
                    path.rmdir()
                self.command.reset_mock()
                real_atomic = u.atomic
                count = 0
                def fail_once(*args):
                    nonlocal count
                    count += 1
                    if failure_at <= 3 and count == failure_at:
                        raise RuntimeError('INJECTED_FAILURE')
                    return real_atomic(*args)
                real_preflight = u.preflight
                def fail_validation(*args, **kwargs):
                    if failure_at == 4 and kwargs.get('expected_flag') is True:
                        raise RuntimeError('INJECTED_FAILURE')
                    return real_preflight(*args, **kwargs)
                with patch.object(u, 'atomic', side_effect=fail_once), \
                        patch.object(u, 'preflight', side_effect=fail_validation):
                    with self.assertRaisesRegex(RuntimeError, 'INJECTED_FAILURE'):
                        u.update(self.stage, self.new_pin, enable_fixed_earnings_trigger=True)
                self.assertEqual(self.install_snapshot(), self.old)
                self.assertEqual(self.retained_snapshot(), self.retained)
                self.assertNotIn(u.FLAG, json.loads((self.install / 'config.json').read_bytes()))
                self.assertEqual(self.timer, 'active')

    def test_verified_poison_source_is_compiled_without_top_level_execution_or_runtime_command(self):
        marker = self.install.parent / 'MIGRATION_TOP_LEVEL_EXECUTED'
        poison = ("from pathlib import Path\nPath(" + repr(str(marker)) +
                  ").write_bytes(b'EXECUTED')\nraise AssertionError('MIGRATION_EXECUTED')\n").encode()
        (self.stage / u.CHANGED).write_bytes(poison)
        self.make_lock(self.stage)
        pin = u.digest((self.stage / 'runtime-lock.json').read_bytes())
        with patch.object(u, 'NEW_MIGRATIONS_SHA', u.digest(poison)), \
                patch.object(builtins, 'exec', side_effect=AssertionError('RUNTIME_EXEC_FORBIDDEN')), \
                patch.object(builtins, 'compile', wraps=compile) as syntax_check:
            u.update(self.stage, pin)
            syntax_check.assert_called_once_with(poison, str(self.stage / u.CHANGED), 'exec')
        self.assertFalse(marker.exists())
        self.assertEqual((self.install / u.CHANGED).read_bytes(), poison)
        self.assertTrue(all(call.args[0] == u.SYSTEMCTL for call in self.command.call_args_list))

    def test_invalid_verified_source_syntax_rejects_before_host_commands_or_runtime_writes(self):
        invalid = b'def invalid(:\n'
        (self.stage / u.CHANGED).write_bytes(invalid)
        self.make_lock(self.stage)
        pin = u.digest((self.stage / 'runtime-lock.json').read_bytes())
        with patch.object(u, 'NEW_MIGRATIONS_SHA', u.digest(invalid)):
            with self.assertRaises(SyntaxError):
                u.update(self.stage, pin)
        self.command.assert_not_called()
        self.assertEqual(self.install_snapshot(), self.old)
        self.assertFalse(list(self.backups.iterdir()))

    def test_wrong_pin_and_other_runtime_changes_reject_before_timer_stop(self):
        with self.assertRaisesRegex(RuntimeError, 'LOCK_PIN_MISMATCH'):
            u.update(self.stage, '0' * 64)
        self.command.assert_not_called()
        (self.stage / 'public-test-policy.properties').write_bytes(b'forbidden policy change')
        self.make_lock(self.stage)
        pin = u.digest((self.stage / 'runtime-lock.json').read_bytes())
        with self.assertRaisesRegex(RuntimeError, 'UNEXPECTED_RUNTIME_CHANGE'):
            u.update(self.stage, pin)
        self.command.assert_not_called()
        self.assertEqual(self.install_snapshot(), self.old)

    def test_wrong_candidate_sha_is_rejected_before_any_command(self):
        with patch.object(u, 'NEW_MIGRATIONS_SHA', '0' * 64):
            with self.assertRaisesRegex(RuntimeError, 'MIGRATIONS_CANDIDATE_PIN_MISMATCH'):
                u.update(self.stage, self.new_pin)
        self.command.assert_not_called()

    def test_config_internal_external_job_and_flag_drift_reject_before_stop(self):
        variants = [({**self.config, 'trusted_files': {u.CHANGED: '0' * 64}}, 'CONFIG_PIN_DRIFT'),
                    ({**self.config, u.FLAG: True}, 'FIXED_TRIGGER_FLAG_REJECTED'),
                    ({**self.config, u.FLAG: 'false'}, 'FIXED_TRIGGER_FLAG_REJECTED'),
                    ({**self.config, 'trusted_external_files': {str(self.external): '0' * 64}}, 'EXTERNAL_PIN_DRIFT'),
                    ({**self.config, 'job_hashes': {**self.config['job_hashes'], 'backend': '0' * 64}}, 'JOB_PIN_DRIFT')]
        for config, reason in variants:
            with self.subTest(reason=reason):
                (self.install / 'config.json').write_bytes(json.dumps(config).encode())
                self.command.reset_mock()
                with self.assertRaisesRegex(RuntimeError, reason):
                    u.update(self.stage, self.new_pin)
                self.command.assert_not_called()

    def test_migration_hold_active_permit_or_release_busy_blocks_before_stop(self):
        for path in (self.root / 'migrations/START_BLOCKED', self.root / 'migrations/active.json',
                     self.permit, self.root / 'transaction.json', self.root / 'HALTED.json'):
            with self.subTest(path=path.name):
                path.write_bytes(b'{}')
                with self.assertRaises(RuntimeError):
                    u.update(self.stage, self.new_pin)
                self.command.assert_not_called()
                path.unlink()

    def test_second_preflight_catches_racing_hold_and_keeps_timer_stopped(self):
        def race(*args):
            result = self.fake_command(*args)
            if args == (u.SYSTEMCTL, 'stop', 'nexgrid-release.timer'):
                (self.root / 'migrations/START_BLOCKED').write_bytes(b'{}')
            return result
        with patch.object(u, 'command', side_effect=race) as commands:
            with self.assertRaisesRegex(RuntimeError, 'MIGRATION_RECOVERY_REQUIRED'):
                u.update(self.stage, self.new_pin)
            self.assertNotIn((u.SYSTEMCTL, 'start', 'nexgrid-release.timer'), [c.args for c in commands.call_args_list])
        self.assertEqual(self.install_snapshot(), self.old)
        self.assertFalse(list(self.backups.iterdir()))
        self.assertEqual(self.timer, 'inactive')

    def test_rollback_failure_leaves_timer_stopped(self):
        with patch.object(u, 'atomic', side_effect=RuntimeError('PERSISTENT_WRITE_FAILURE')):
            with self.assertRaisesRegex(RuntimeError, 'PERSISTENT_WRITE_FAILURE'):
                u.update(self.stage, self.new_pin)
        self.assert_no_timer_start()
        self.assertEqual(self.timer, 'inactive')

    def test_release_lock_contention_and_config_race_cannot_write_or_resume(self):
        for race in ('lock', 'config'):
            with self.subTest(race=race):
                self.timer = 'active'
                (self.install / 'config.json').write_bytes(self.old['config.json'][0])
                def stop_and_race(*args):
                    result = self.fake_command(*args)
                    if race == 'config' and args == (u.SYSTEMCTL, 'stop', 'nexgrid-release.timer'):
                        config = {**self.config, 'pc_image': 'racing-change'}
                        (self.install / 'config.json').write_bytes(json.dumps(config).encode())
                    return result
                lock = SimpleNamespace(flock=lambda *_: None, LOCK_EX=1, LOCK_NB=2)
                if race == 'lock':
                    def blocked(*_):
                        raise BlockingIOError('LOCK_BUSY')
                    lock.flock = blocked
                with patch.dict(sys.modules, {'fcntl': lock}), \
                        patch.object(u, 'command', side_effect=stop_and_race) as commands:
                    with self.assertRaises((BlockingIOError, RuntimeError)):
                        u.update(self.stage, self.new_pin)
                    self.assertNotIn((u.SYSTEMCTL, 'start', 'nexgrid-release.timer'),
                                     [c.args for c in commands.call_args_list])
                self.assertEqual((self.install / u.CHANGED).read_bytes(), self.old[u.CHANGED][0])
                self.assertFalse(list(self.backups.iterdir()))
                self.assertEqual(self.timer, 'inactive')

    def test_release_service_active_and_timer_stop_failure_never_write_runtime(self):
        for failure in ('active', 'stop'):
            with self.subTest(failure=failure):
                def fail_command(*args):
                    if failure == 'active' and args[-1] == 'nexgrid-release.service':
                        return b'activating'
                    if failure == 'stop' and args == (u.SYSTEMCTL, 'stop', 'nexgrid-release.timer'):
                        raise RuntimeError('STOP_FAILED')
                    return self.fake_command(*args)
                with patch.object(u, 'command', side_effect=fail_command) as commands:
                    with self.assertRaisesRegex(RuntimeError, 'RELEASE_SERVICE_BUSY|STOP_FAILED'):
                        u.update(self.stage, self.new_pin)
                    self.assertNotIn((u.SYSTEMCTL, 'start', 'nexgrid-release.timer'),
                                     [c.args for c in commands.call_args_list])
                self.assertEqual(self.install_snapshot(), self.old)
                self.assertFalse(list(self.backups.iterdir()))

    def test_backup_failure_never_writes_runtime_or_resumes_timer(self):
        with patch.object(u, 'backup_files', side_effect=OSError('BACKUP_FAILURE')):
            with self.assertRaisesRegex(OSError, 'BACKUP_FAILURE'):
                u.update(self.stage, self.new_pin)
        self.assertEqual(self.install_snapshot(), self.old)
        self.assert_no_timer_start()

    def test_nonroot_or_nonisolated_rejects_before_commands(self):
        with patch.object(u.os, 'geteuid', return_value=1):
            with self.assertRaisesRegex(RuntimeError, 'ISOLATED_ROOT_REQUIRED'):
                u.update(self.stage, self.new_pin)
        with patch.object(u, 'sys', SimpleNamespace(flags=SimpleNamespace(isolated=0))):
            with self.assertRaisesRegex(RuntimeError, 'ISOLATED_ROOT_REQUIRED'):
                u.update(self.stage, self.new_pin)
        self.command.assert_not_called()

    def test_cli_enable_default_and_explicit_switch(self):
        with patch.object(u, 'update') as update:
            with patch.object(sys, 'argv', ['operator', str(self.stage), self.new_pin]):
                u.main()
            update.assert_called_once_with(self.stage, self.new_pin, enable_fixed_earnings_trigger=False)
            update.reset_mock()
            with patch.object(sys, 'argv', ['operator', str(self.stage), self.new_pin,
                                           '--enable-fixed-earnings-trigger']):
                u.main()
            update.assert_called_once_with(self.stage, self.new_pin, enable_fixed_earnings_trigger=True)

    def test_trusted_path_rejects_symlink_nonroot_and_writable_files(self):
        # Exercise the actual validator through fresh source loaded under a test name.
        spec = importlib.util.spec_from_file_location('isolated_validator_fixture', u.__file__)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        path = Path('/fixture/root/file')
        for mode, owner in ((stat.S_IFLNK | 0o777, 0), (stat.S_IFREG | 0o644, 1),
                            (stat.S_IFREG | 0o666, 0)):
            with self.subTest(mode=mode, owner=owner), \
                    patch.object(Path, 'resolve', return_value=path), \
                    patch.object(Path, 'lstat', return_value=SimpleNamespace(st_mode=mode, st_uid=owner)):
                with self.assertRaisesRegex(RuntimeError, 'UNTRUSTED_PATH'):
                    module.trusted(path)


if __name__ == '__main__':
    unittest.main()

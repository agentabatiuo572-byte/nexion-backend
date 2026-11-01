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

import update_compute_v2_migrations as u


class ComputeV2MigrationUpdateTests(unittest.TestCase):
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
            build = job.parent / 'builds/1/build.xml'
            build.parent.mkdir(parents=True)
            build.write_bytes(b'<build><completed>true</completed></build>')
        self.config = {'trusted_files': {name: u.digest(data) for name, data in self.old_sources.items()},
                       'trusted_external_files': {str(self.external): u.digest(self.external.read_bytes())},
                       'job_hashes': jobs,
                       'policy_sha256': u.digest(self.old_sources['public-test-policy.properties']),
                       'pc_image': 'retained', 'schema': {'backend': 'retained'}, 'allow_fixed_earnings_trigger_install': True}
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
                  'OLD_MIGRATIONS_SHA': u.digest(self.old_sources[u.CHANGED]),
                  'EXPECTED_JAR_POLICY_SHA': self.config['policy_sha256'],
                  'sys': SimpleNamespace(flags=SimpleNamespace(isolated=1))}
        for name, value in values.items():
            self.start_patch(patch.object(u, name, value))
        self.start_patch(patch.object(u, 'trusted'))
        self.start_patch(patch.object(u, 'sync'))
        self.start_patch(patch.object(u.os, 'fchmod', create=True))
        self.start_patch(patch.object(u.os, 'geteuid', return_value=0, create=True))
        self.flock = self.start_patch(patch.dict(sys.modules, {'fcntl': SimpleNamespace(
            flock=lambda *_: None, LOCK_EX=1, LOCK_NB=2)}))
        self.timer = 'active'
        self.paused = False
        self.ci = {'id':'a'*64,'name':'/'+u.CI,'image':'sha256:'+'b'*64,'running':True,'paused':False,'pid':42}
        self.identities = {name:u.identity(self.install / name) for name in u.MUTABLE}
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
        if args[0:2] == (u.DOCKER, 'inspect'):
            return json.dumps({**self.ci,'paused':self.paused}).encode()
        if args == (u.DOCKER, 'top', self.ci['id'], '-eo', 'pid,comm'):
            return b'PID COMMAND\n42 tini\n43 java'
        if args == (u.DOCKER, 'pause', self.ci['id']):
            self.paused = True
            return b''
        if args == (u.DOCKER, 'unpause', self.ci['id']):
            self.paused = False
            return b''
        if args == (u.SYSTEMCTL, 'show', '--property=MainPID', '--value', 'nexgrid-release.service'):
            return b'0'
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

    def test_success_preserves_flags_other_twelve_files_and_state(self):
        u.update(self.stage, self.new_pin)
        u.closure(self.install, self.new_pin)
        config = json.loads((self.install / 'config.json').read_bytes())
        expected = {**self.config,
                    'trusted_files': {**self.config['trusted_files'], u.CHANGED: u.digest(self.new_source)},u.FLAG:True}
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
        self.assertFalse(self.paused)
        self.assertEqual({name:u.identity(self.install / name) for name in u.MUTABLE},self.identities)

    def test_each_partial_write_or_validation_failure_restores_bytes_modes_and_flags(self):
        for failure_at in (1, 2, 3, 4):
            with self.subTest(failure_at=failure_at):
                # Give each iteration fresh backup names; installed baseline is restored.
                for path in self.backups.iterdir():
                    for child in path.iterdir():
                        child.unlink()
                    path.rmdir()
                self.command.reset_mock()
                real_atomic = u.replace_bound
                count = 0
                def fail_once(*args):
                    nonlocal count
                    count += 1
                    if failure_at <= 3 and count == failure_at:
                        raise RuntimeError('INJECTED_FAILURE')
                    return real_atomic(*args)
                real_preflight = u.preflight
                def fail_validation(*args, **kwargs):
                    if failure_at == 4 and args[0]['files'][u.CHANGED] == u.digest(self.new_source):
                        raise RuntimeError('INJECTED_FAILURE')
                    return real_preflight(*args, **kwargs)
                with patch.object(u, 'replace_bound', side_effect=fail_once), \
                        patch.object(u, 'preflight', side_effect=fail_validation):
                    with self.assertRaisesRegex(RuntimeError, 'INJECTED_FAILURE'):
                        u.update(self.stage, self.new_pin)
                self.assertEqual(self.install_snapshot(), self.old)
                self.assertEqual(self.retained_snapshot(), self.retained)
                self.assertIs(json.loads((self.install / 'config.json').read_bytes())['allow_fixed_earnings_trigger_install'], True)
                self.assertEqual(self.timer, 'active')
                self.assertFalse(self.paused)
                self.assertEqual({name:u.identity(self.install / name) for name in u.MUTABLE},self.identities)

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
        self.assertTrue(all(call.args[0] in {u.SYSTEMCTL,u.DOCKER} for call in self.command.call_args_list))

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

    def test_config_internal_external_and_job_drift_reject_before_stop(self):
        variants = [({**self.config, 'policy_sha256': '0' * 64}, 'POLICY_PIN_DRIFT'),
                    ({**self.config, 'trusted_files': {u.CHANGED: '0' * 64}}, 'CONFIG_PIN_DRIFT'),
                    ({**self.config, 'trusted_external_files': {str(self.external): '0' * 64}}, 'EXTERNAL_PIN_DRIFT'),
                    ({**self.config, 'job_hashes': {**self.config['job_hashes'], 'backend': '0' * 64}}, 'JOB_PIN_DRIFT')]
        for config, reason in variants:
            with self.subTest(reason=reason):
                (self.install / 'config.json').write_bytes(json.dumps(config).encode())
                self.command.reset_mock()
                with self.assertRaisesRegex(RuntimeError, reason):
                    u.update(self.stage, self.new_pin)
                self.assertTrue(all(c.args == (u.SYSTEMCTL, 'show', '--property=ActiveState', '--value', 'nexgrid-release.timer') for c in self.command.call_args_list))

    def test_migration_hold_active_permit_or_release_busy_blocks_before_stop(self):
        for path in (self.root / 'migrations/START_BLOCKED', self.root / 'migrations/active.json',
                     self.permit, self.root / 'transaction.json', self.root / 'HALTED.json'):
            with self.subTest(path=path.name):
                path.write_bytes(b'{}')
                with self.assertRaises(RuntimeError):
                    u.update(self.stage, self.new_pin)
                self.assertTrue(all(c.args == (u.SYSTEMCTL, 'show', '--property=ActiveState', '--value', 'nexgrid-release.timer') for c in self.command.call_args_list))
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
        with patch.object(u, 'replace_bound', side_effect=RuntimeError('PERSISTENT_WRITE_FAILURE')):
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

    def test_cli_has_no_flag_mutation_switch(self):
        with patch.object(u, 'update') as update:
            with patch.object(sys, 'argv', ['operator', str(self.stage), self.new_pin]):
                u.main()
            update.assert_called_once_with(self.stage, self.new_pin)

    def test_previously_inactive_timer_stays_inactive_after_success(self):
        self.timer = 'inactive'
        u.update(self.stage, self.new_pin)
        self.assertEqual(self.timer, 'inactive')
        self.assert_no_timer_start()
        self.assertEqual(self.retained_snapshot(), self.retained)

    def test_previously_inactive_timer_stays_inactive_after_rollback(self):
        self.timer = 'inactive'
        real_atomic = u.replace_bound
        count = 0
        def fail_once(*args):
            nonlocal count
            count += 1
            if count == 2:
                raise RuntimeError('INJECTED_FAILURE')
            return real_atomic(*args)
        with patch.object(u, 'replace_bound', side_effect=fail_once):
            with self.assertRaisesRegex(RuntimeError, 'INJECTED_FAILURE'):
                u.update(self.stage, self.new_pin)
        self.assertEqual(self.timer, 'inactive')
        self.assert_no_timer_start()
        self.assertEqual(self.install_snapshot(), self.old)

    def test_uncertain_initial_timer_state_rejects_before_write(self):
        self.timer = 'activating'
        with self.assertRaisesRegex(RuntimeError, 'TIMER_STATE_REJECTED'):
            u.update(self.stage, self.new_pin)
        self.assertEqual(self.install_snapshot(), self.old)
        self.assertFalse(list(self.backups.iterdir()))

    def test_jar_policy_pin_is_distinct_and_preserved_from_bundled_policy(self):
        actual_jar_pin = u.digest(b'approved jar policy')
        self.config['policy_sha256'] = actual_jar_pin
        (self.install / 'config.json').write_bytes(json.dumps(self.config).encode())
        with patch.object(u, 'EXPECTED_JAR_POLICY_SHA', actual_jar_pin):
            u.update(self.stage, self.new_pin)
        self.assertEqual(json.loads((self.install / 'config.json').read_bytes())['policy_sha256'], actual_jar_pin)
        self.assertEqual((self.install / 'public-test-policy.properties').read_bytes(), self.old_sources['public-test-policy.properties'])

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

    def test_unapproved_baseline_and_existing_opt_in_cannot_be_repinned(self):
        with patch.object(u,'OLD_MIGRATIONS_SHA','0'*64):
            with self.assertRaisesRegex(RuntimeError,'MIGRATIONS_BASELINE_PIN_MISMATCH'):
                u.update(self.stage,self.new_pin)
        self.command.assert_not_called()
        for value in (False,True,'true',None):
            with self.subTest(flag=value):
                (self.install / 'config.json').write_bytes(json.dumps({**self.config,u.FLAG:value}).encode())
                self.command.reset_mock()
                with self.assertRaisesRegex(RuntimeError,'COMPUTE_V2_APPROVAL_FLAG_DRIFT'):
                    u.update(self.stage,self.new_pin)
                self.assert_no_timer_start()
                self.assertEqual(self.timer,'active')

    def test_stage_cannot_expand_the_thirteen_file_set(self):
        (self.stage / 'unreviewed.py').write_bytes(b'pass')
        with self.assertRaisesRegex(RuntimeError,'STAGE_INVENTORY_REJECTED'):
            u.update(self.stage,self.new_pin)
        self.command.assert_not_called()
        self.assertEqual(self.install_snapshot(),self.old)

    def test_partial_inplace_config_write_restores_original_bytes_and_inodes(self):
        real=u.replace_bound
        calls=0
        def partial(path,data,mode,identity):
            nonlocal calls
            calls+=1
            if calls==2:
                with path.open('r+b') as stream:
                    stream.write(b'{"partial":')
                    stream.truncate()
                raise OSError('INJECTED_PARTIAL_WRITE')
            return real(path,data,mode,identity)
        with patch.object(u,'replace_bound',side_effect=partial):
            with self.assertRaisesRegex(OSError,'INJECTED_PARTIAL_WRITE'):
                u.update(self.stage,self.new_pin)
        self.assertEqual(self.install_snapshot(),self.old)
        self.assertEqual({name:u.identity(self.install/name) for name in u.MUTABLE},self.identities)
        self.assertFalse(self.paused)
        self.assertEqual(self.timer,'active')
        self.assertNotIn(u.FLAG,json.loads((self.install/'config.json').read_bytes()))

    def test_replaced_inode_is_never_overwritten_or_resumed(self):
        real=u.replace_bound
        calls=0
        def replacement(path,data,mode,identity):
            nonlocal calls
            calls+=1
            if calls==2:
                other=path.with_suffix('.foreign')
                other.write_bytes(b'foreign inode')
                other.replace(path)
            return real(path,data,mode,identity)
        with patch.object(u,'replace_bound',side_effect=replacement):
            with self.assertRaisesRegex(RuntimeError,'BOUND_FILE_IDENTITY_DRIFT'):
                u.update(self.stage,self.new_pin)
        self.assertEqual((self.install/'config.json').read_bytes(),b'foreign inode')
        self.assertTrue(self.paused)
        self.assertEqual(self.timer,'inactive')
        self.assert_no_timer_start()

    def test_post_pause_stage_config_state_or_hold_drift_never_writes_runtime(self):
        for drift in ('stage','config','state','hold','extra'):
            with self.subTest(drift=drift):
                self.timer='active';self.paused=False
                (self.stage/u.CHANGED).write_bytes(self.new_source)
                (self.install/'config.json').write_bytes(self.old['config.json'][0])
                (self.root/'state.json').write_bytes(self.retained[str(self.root/'state.json')])
                hold=self.root/'HALTED.json'
                if hold.exists():hold.unlink()
                extra=self.stage/'extra.py'
                if extra.exists():extra.unlink()
                def race(*args):
                    result=self.fake_command(*args)
                    if args==(u.DOCKER,'pause',self.ci['id']):
                        if drift=='stage':(self.stage/u.CHANGED).write_bytes(b'pass # drift')
                        elif drift=='config':(self.install/'config.json').write_bytes(b'{}')
                        elif drift=='state':(self.root/'state.json').write_bytes(b'{}')
                        elif drift=='hold':hold.write_bytes(b'{}')
                        else:extra.write_bytes(b'pass')
                    return result
                with patch.object(u,'command',side_effect=race):
                    with self.assertRaisesRegex(RuntimeError,'FILE_PIN_MISMATCH|UPDATE_PREFLIGHT_DRIFT|RELEASE_BUSY'):
                        u.update(self.stage,self.new_pin)
                self.assertEqual((self.install/u.CHANGED).read_bytes(),self.old[u.CHANGED][0])
                self.assertEqual((self.install/'runtime-lock.json').read_bytes(),self.old['runtime-lock.json'][0])
                self.assertFalse(list(self.backups.iterdir()))
                self.assertTrue(self.paused);self.assertEqual(self.timer,'inactive')

    def test_active_build_or_agent_process_is_rejected_before_timer_stop(self):
        build=self.jobs/'nexgrid-backend-test/builds/1/build.xml'
        build.write_bytes(b'<build><completed>false</completed></build>')
        with self.assertRaisesRegex(RuntimeError,'WAIT_FOR_IDLE_CI'):
            u.update(self.stage,self.new_pin)
        build.write_bytes(b'<build><completed>true</completed></build>')
        def busy(*args):
            if args[0:2]==(u.DOCKER,'top'):return b'PID COMMAND\n42 java\n43 mvn'
            return self.fake_command(*args)
        with patch.object(u,'command',side_effect=busy):
            with self.assertRaisesRegex(RuntimeError,'WAIT_FOR_IDLE_AGENT'):
                u.update(self.stage,self.new_pin)
        self.assertEqual(self.timer,'active');self.assertFalse(self.paused)
        self.assertEqual(self.install_snapshot(),self.old)

    def test_pause_and_resume_ack_uncertainty_never_starts_timer(self):
        for operation in ('pause','unpause'):
            with self.subTest(operation=operation):
                self.timer='active';self.paused=False
                for name,(data,mode) in self.old.items():
                    (self.install/name).write_bytes(data)
                for backup in self.backups.iterdir():
                    for file in backup.iterdir():file.unlink()
                    backup.rmdir()
                def unknown(*args):
                    result=self.fake_command(*args)
                    if args==(u.DOCKER,operation,self.ci['id']):raise RuntimeError('COMMAND_ACK_UNKNOWN')
                    return result
                with patch.object(u,'command',side_effect=unknown) as commands:
                    with self.assertRaisesRegex(RuntimeError,'COMMAND_ACK_UNKNOWN'):
                        u.update(self.stage,self.new_pin)
                    self.assertNotIn((u.SYSTEMCTL,'start','nexgrid-release.timer'),[call.args for call in commands.call_args_list])
                self.assertEqual(self.timer,'inactive')

    def test_archived_jobs_are_read_only_disabled_and_all_terminal(self):
        archived=self.jobs/'nexgrid-backend-main'
        (archived/'builds/1').mkdir(parents=True)
        (archived/'config.xml').write_bytes(b'<flow-definition><disabled>true</disabled></flow-definition>')
        (archived/'builds/1/build.xml').write_bytes(b'<build><completed>true</completed></build>')
        u.idle(self.ci)
        (archived/'config.xml').write_bytes(b'<flow-definition><disabled>false</disabled></flow-definition>')
        with self.assertRaisesRegex(RuntimeError,'ARCHIVED_JOB_ENABLED'):u.idle(self.ci)

    def test_live_service_pid_with_inactive_label_is_still_busy(self):
        def pid(*args):
            if args==(u.SYSTEMCTL,'show','--property=MainPID','--value','nexgrid-release.service'):return b'42'
            return self.fake_command(*args)
        with patch.object(u,'command',side_effect=pid):
            with self.assertRaisesRegex(RuntimeError,'RELEASE_SERVICE_BUSY'):
                u.update(self.stage,self.new_pin)
        self.assertEqual(self.timer,'active');self.assertEqual(self.install_snapshot(),self.old)

    def test_release_lock_stays_open_through_all_bound_writes_and_agent_timer_resume(self):
        held={}
        def flock(stream,*_):held['stream']=stream
        real=u.replace_bound
        def write(*args):
            self.assertFalse(held['stream'].closed)
            self.assertTrue(self.paused)
            self.assertEqual(self.timer,'inactive')
            return real(*args)
        def command(*args):
            if args in ((u.DOCKER,'unpause',self.ci['id']),(u.SYSTEMCTL,'start','nexgrid-release.timer')):
                self.assertFalse(held['stream'].closed)
            return self.fake_command(*args)
        with patch.dict(sys.modules,{'fcntl':SimpleNamespace(flock=flock,LOCK_EX=1,LOCK_NB=2)}), \
                patch.object(u,'replace_bound',side_effect=write),patch.object(u,'command',side_effect=command):
            u.update(self.stage,self.new_pin)
        self.assertTrue(held['stream'].closed)


if __name__ == '__main__':
    unittest.main()

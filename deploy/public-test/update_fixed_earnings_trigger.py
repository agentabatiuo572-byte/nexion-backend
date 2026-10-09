#!/usr/bin/env python3
"""Operator-only TEST 13-to-13 update, independently SHA-approved before use.

Run as root with /usr/bin/python3 -I. This updater changes runtime source and
its pins only; it never imports migration code, runs SQL, changes grants, or
resets release/migration state. Enabling the fixed trigger lane is an explicit
CLI choice requiring the operator's action-time approval.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import time

INSTALL = Path('/srv/jenkins/release')
ROOT = Path('/srv/nexgrid/cd')
STAGES = Path('/srv/jenkins/updates')
BACKUPS = Path('/srv/jenkins/backups')
JOBS = Path('/var/lib/docker/volumes/nexgrid_jenkins_home/_data/jobs')
GUARD = Path('/etc/systemd/system/nexgrid-backend.service.d/60-database-migration-hold.conf')
PERMIT = Path('/run/nexgrid-backend-migration-start')
GUARD_TEXT = ('[Unit]\nConditionPathExists=|!/srv/nexgrid/cd/migrations/START_BLOCKED\n'
              'ConditionPathExists=|/run/nexgrid-backend-migration-start\n').encode()
OLD_LOCK = 'f9120a6a7063999dc5f45c7d2bfe372e692b2976826e3612b9bf7e207ab1e35b'
NEW_MIGRATIONS_SHA = '1c3491ce0195b6cc46d6ac04d942210b5f711452c4460f717507f8aba4b01bd8'
CHANGED = 'database_migrations.py'
FLAG = 'allow_fixed_earnings_trigger_install'
FILES = frozenset({'40-release-jobs.groovy', 'ci-build.sh', 'database_migrations.py',
                   'h5-nginx.conf', 'install_release.py', 'main.pipeline.groovy',
                   'nexgrid-release.service', 'nexgrid-release.timer',
                   'public-test-policy.properties', 'release_broker.py',
                   'schema_fingerprint.py', 'test-server.yml', 'trusted_entry.py'})
MUTABLE = (CHANGED, 'config.json', 'runtime-lock.json')
SYSTEMCTL = '/usr/bin/systemctl'


def require(ok, reason):
    if not ok:
        raise RuntimeError(reason)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def valid_sha(value):
    return isinstance(value, str) and re.fullmatch(r'[0-9a-f]{64}', value)


def trusted(path, *, directory=False):
    require(path.is_absolute() and path.resolve() == path, 'UNTRUSTED_PATH')
    for index, item in enumerate((path, *path.parents)):
        info = item.lstat()
        kind = stat.S_ISDIR(info.st_mode) if directory or index else stat.S_ISREG(info.st_mode)
        require(kind and info.st_uid == 0 and not info.st_mode & 0o022, 'UNTRUSTED_PATH')


def absent(path, reason):
    require(not path.exists() and not path.is_symlink(), reason)


def command(*args):
    result = subprocess.run(args, capture_output=True, timeout=90,
                            env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C'})
    require(result.returncode == 0, 'UPDATE_COMMAND_FAILED_' + Path(args[0]).name.upper())
    return result.stdout.strip()


def closure(directory, pin):
    require(valid_sha(pin), 'LOCK_ARGUMENT_REJECTED')
    trusted(directory, directory=True)
    trusted(directory / 'runtime-lock.json')
    raw = (directory / 'runtime-lock.json').read_bytes()
    require(digest(raw) == pin, 'LOCK_PIN_MISMATCH')
    manifest = json.loads(raw)
    require(isinstance(manifest, dict) and set(manifest) == {'version', 'files'}
            and manifest['version'] == 1 and isinstance(manifest['files'], dict)
            and set(manifest['files']) == FILES, 'CLOSURE_REJECTED')
    sources = {}
    for name, expected in manifest['files'].items():
        require(valid_sha(expected), 'MANIFEST_REJECTED')
        trusted(directory / name)
        sources[name] = (directory / name).read_bytes()
        require(digest(sources[name]) == expected, 'FILE_PIN_MISMATCH')
    return manifest, raw, sources


def validate_change(old, new):
    require({name for name in FILES if old['files'][name] != new['files'][name]} == {CHANGED},
            'UNEXPECTED_RUNTIME_CHANGE')
    require(new['files'][CHANGED] == NEW_MIGRATIONS_SHA, 'MIGRATIONS_CANDIDATE_PIN_MISMATCH')


def preflight(old, *, timer_active, expected_flag=False):
    trusted(INSTALL / 'config.json')
    raw = (INSTALL / 'config.json').read_bytes()
    config = json.loads(raw)
    require(isinstance(config, dict), 'CONFIG_REJECTED')
    internal = config.get('trusted_files')
    external = config.get('trusted_external_files')
    jobs = config.get('job_hashes')
    require(isinstance(internal, dict) and CHANGED in internal
            and isinstance(external, dict) and external
            and isinstance(jobs, dict) and set(jobs) == {'backend', 'pc', 'uniapp'},
            'CONFIG_PINS_REJECTED')
    require(config.get(FLAG, False) is expected_flag, 'FIXED_TRIGGER_FLAG_REJECTED')
    require(config.get('policy_sha256') == old['files']['public-test-policy.properties'],
            'POLICY_PIN_DRIFT')
    for name, expected in internal.items():
        require(name in FILES and valid_sha(expected) and old['files'][name] == expected,
                'CONFIG_PIN_DRIFT')
    for name, expected in external.items():
        require(isinstance(name, str) and valid_sha(expected), 'EXTERNAL_PIN_REJECTED')
        path = Path(name)
        require(path.is_absolute() and not path.is_relative_to(INSTALL), 'EXTERNAL_PATH_REJECTED')
        trusted(path)
        require(digest(path.read_bytes()) == expected, 'EXTERNAL_PIN_DRIFT')
    for kind, expected in jobs.items():
        require(valid_sha(expected), 'JOB_PIN_REJECTED')
        path = JOBS / ('nexgrid-' + kind + '-test') / 'config.xml'
        # Jenkins owns these data files. Hash them without treating them as code.
        require(all(not p.is_symlink() for p in (path, *path.parents))
                and stat.S_ISREG(path.lstat().st_mode), 'JOB_PATH_REJECTED')
        require(digest(path.read_bytes()) == expected, 'JOB_PIN_DRIFT')
    trusted(ROOT, directory=True)
    trusted(ROOT / 'lock')
    trusted(ROOT / 'state.json')
    trusted(ROOT / 'migrations', directory=True)
    trusted(ROOT / 'migrations/state.json')
    trusted(GUARD)
    require(GUARD.read_bytes() == GUARD_TEXT, 'MIGRATION_GUARD_DRIFT')
    for name in ('transaction.json', 'HALTED.json'):
        absent(ROOT / name, 'RELEASE_BUSY')
    for name in ('active.json', 'START_BLOCKED'):
        absent(ROOT / 'migrations' / name, 'MIGRATION_RECOVERY_REQUIRED')
    absent(PERMIT, 'MIGRATION_START_PERMIT_PRESENT')
    require(command(SYSTEMCTL, 'show', '--property=ActiveState', '--value',
                    'nexgrid-release.service') == b'inactive', 'RELEASE_SERVICE_BUSY')
    if timer_active:
        require(command(SYSTEMCTL, 'is-active', 'nexgrid-release.timer') == b'active',
                'TIMER_NOT_ACTIVE')
    else:
        require(command(SYSTEMCTL, 'show', '--property=ActiveState', '--value',
                        'nexgrid-release.timer') == b'inactive', 'TIMER_NOT_STOPPED')
    state = ((ROOT / 'state.json').read_bytes(), (ROOT / 'migrations/state.json').read_bytes())
    return config, raw, state


def sync(directory):
    descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def atomic(path, data, mode):
    trusted(path)
    temporary = path.with_name(path.name + '.fixed-trigger-update-new')
    absent(temporary, 'UPDATE_TEMP_EXISTS')
    created = False
    try:
        with temporary.open('xb') as stream:
            created = True
            stream.write(data)
            stream.flush()
            temporary.chmod(mode)
            os.fsync(stream.fileno())
        temporary.replace(path)
        sync(path.parent)
    finally:
        if created and temporary.exists():
            temporary.unlink()
            sync(path.parent)


def snapshot():
    return {name: ((INSTALL / name).read_bytes(), stat.S_IMODE((INSTALL / name).lstat().st_mode))
            for name in MUTABLE}


def backup_files(backup, previous, new_pin, enable):
    absent(backup, 'BACKUP_EXISTS')
    backup.mkdir(mode=0o700)
    metadata = {'old_lock_sha256': OLD_LOCK, 'new_lock_sha256': new_pin,
                'enable_fixed_earnings_trigger': enable, 'files': {}}
    for name, (data, mode) in previous.items():
        with (backup / name).open('xb') as stream:
            stream.write(data)
            stream.flush()
            (backup / name).chmod(0o600)
            os.fsync(stream.fileno())
        require((backup / name).read_bytes() == data, 'BACKUP_VERIFY_FAILED')
        metadata['files'][name] = {'sha256': digest(data), 'mode': mode}
    with (backup / 'backup-manifest.json').open('xb') as stream:
        stream.write((json.dumps(metadata, indent=2, sort_keys=True) + '\n').encode())
        stream.flush()
        (backup / 'backup-manifest.json').chmod(0o600)
        os.fsync(stream.fileno())
    sync(backup)
    sync(backup.parent)


def update(stage, pin, *, enable_fixed_earnings_trigger=False):
    require(os.geteuid() == 0 and sys.flags.isolated, 'ISOLATED_ROOT_REQUIRED')
    require(isinstance(enable_fixed_earnings_trigger, bool), 'FLAG_ARGUMENT_REJECTED')
    trusted(Path(__file__).absolute())
    require(stage.parent == STAGES and stage.resolve() == stage, 'STAGE_REJECTED')
    new, new_raw, sources = closure(stage, pin)
    old, _, _ = closure(INSTALL, OLD_LOCK)
    validate_change(old, new)
    # Syntax-check the sole changed, SHA-verified source; never execute it.
    compile(sources[CHANGED], str(stage / CHANGED), 'exec')
    _, config_raw, state = preflight(old, timer_active=True)
    trusted(BACKUPS, directory=True)
    backup = BACKUPS / ('fixed-earnings-trigger-' + time.strftime('%Y%m%d-%H%M%S', time.gmtime()))
    absent(backup, 'BACKUP_EXISTS')
    previous = snapshot()
    command(SYSTEMCTL, 'stop', 'nexgrid-release.timer')
    safe_to_resume = False
    try:
        import fcntl
        with (ROOT / 'lock').open('r+b') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            old_again, _, _ = closure(INSTALL, OLD_LOCK)
            new_again, raw_again, sources_again = closure(stage, pin)
            validate_change(old_again, new_again)
            config_again, config_raw_again, state_again = preflight(old_again, timer_active=False)
            require(config_raw_again == config_raw and state_again == state
                    and raw_again == new_raw and sources_again == sources and snapshot() == previous,
                    'UPDATE_PREFLIGHT_DRIFT')
            backup_files(backup, previous, pin, enable_fixed_earnings_trigger)
            print('FIXED_TRIGGER_RUNTIME_BACKUP_READY ' + str(backup), flush=True)
            config_again['trusted_files'][CHANGED] = new['files'][CHANGED]
            config_again[FLAG] = enable_fixed_earnings_trigger
            try:
                atomic(INSTALL / CHANGED, sources[CHANGED], previous[CHANGED][1])
                atomic(INSTALL / 'config.json',
                       (json.dumps(config_again, indent=2, sort_keys=True) + '\n').encode(),
                       previous['config.json'][1])
                atomic(INSTALL / 'runtime-lock.json', new_raw, previous['runtime-lock.json'][1])
                closure(INSTALL, pin)
                installed, _, installed_state = preflight(new, timer_active=False,
                                                          expected_flag=enable_fixed_earnings_trigger)
                require(installed == config_again and installed_state == state, 'INSTALLED_CONFIG_OR_STATE_DRIFT')
                require((ROOT / 'state.json').read_bytes() == state[0]
                        and (ROOT / 'migrations/state.json').read_bytes() == state[1], 'STATE_DRIFT')
                safe_to_resume = True
                print('FIXED_TRIGGER_RUNTIME_INSTALLED ' + pin + ' FLAG_' +
                      ('ENABLED' if enable_fixed_earnings_trigger else 'DISABLED'), flush=True)
            except BaseException:
                for name, (data, mode) in previous.items():
                    atomic(INSTALL / name, data, mode)
                closure(INSTALL, OLD_LOCK)
                require(snapshot() == previous, 'ROLLBACK_BYTES_OR_MODE_DRIFT')
                _, restored_raw, restored_state = preflight(old, timer_active=False)
                require(restored_raw == config_raw and restored_state == state, 'ROLLBACK_STATE_DRIFT')
                safe_to_resume = True
                print('FIXED_TRIGGER_RUNTIME_RESTORED ' + str(backup), flush=True)
                raise
    finally:
        if safe_to_resume:
            try:
                command(SYSTEMCTL, 'start', 'nexgrid-release.timer')
            except BaseException:
                print('OPERATOR_RECOVERY_REQUIRED_TIMER_STATE_UNCERTAIN ' + str(backup), flush=True)
                raise
        else:
            print('OPERATOR_RECOVERY_REQUIRED_TIMER_STOPPED ' + str(backup), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', type=Path)
    parser.add_argument('lock_sha256')
    parser.add_argument('--enable-fixed-earnings-trigger', action='store_true',
                        help='explicitly opt in after action-time operator approval; default is disabled')
    args = parser.parse_args()
    update(args.stage, args.lock_sha256, enable_fixed_earnings_trigger=args.enable_fixed_earnings_trigger)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        reason = str(error)
        print(reason if re.fullmatch(r'[A-Z0-9_]+', reason) else type(error).__name__, flush=True)
        raise SystemExit(1)

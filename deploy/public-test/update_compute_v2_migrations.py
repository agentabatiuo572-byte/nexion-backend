#!/usr/bin/env python3
"""Operator-only TEST 13-to-13 update, independently SHA-approved before use.

Run as root with /usr/bin/python3 -I. This updater changes runtime source and
its pins and the Compute V2 / retired H4 lane flags; it never
imports migration code, runs SQL, changes grants, or resets release/migration
state. The old twelve runtime files remain unchanged; the retired H4 opt-in is removed.
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
import xml.etree.ElementTree as ET

INSTALL = Path('/srv/jenkins/release')
ROOT = Path('/srv/nexgrid/cd')
STAGES = Path('/srv/jenkins/updates')
BACKUPS = Path('/srv/jenkins/backups')
JOBS = Path('/var/lib/docker/volumes/nexgrid_jenkins_home/_data/jobs')
GUARD = Path('/etc/systemd/system/nexgrid-backend.service.d/60-database-migration-hold.conf')
PERMIT = Path('/run/nexgrid-backend-migration-start')
GUARD_TEXT = ('[Unit]\nConditionPathExists=|!/srv/nexgrid/cd/migrations/START_BLOCKED\n'
              'ConditionPathExists=|/run/nexgrid-backend-migration-start\n').encode()
OLD_LOCK = '907fb594f70782151ba12cdb168c324af47ef81c9ca961c9ce1f4a5e803d5cbc'
OLD_MIGRATIONS_SHA = '0794fa15fb5d0aa2470bb6486dc56fad18eb6732337e471e0a15e4961b1a7221'
NEW_MIGRATIONS_SHA = '80ea3667306ef99f7d3af423de20cfc5c197b0c85369625daf01f766f11820c2'
FLAG = 'allow_fixed_compute_v2_trigger_install'
# JAR policy is independently pinned; the installed closure includes an older
# bundled policy file that is not used to validate current release artifacts.
EXPECTED_JAR_POLICY_SHA = '6ed34f3ea7e78ea8de6211e62de9d3ebda09243cd29f0a65d0d2284c48209f5e'
CHANGED = 'database_migrations.py'
FILES = frozenset({'40-release-jobs.groovy', 'ci-build.sh', 'database_migrations.py',
                   'h5-nginx.conf', 'install_release.py', 'main.pipeline.groovy',
                   'nexgrid-release.service', 'nexgrid-release.timer',
                   'public-test-policy.properties', 'release_broker.py',
                   'schema_fingerprint.py', 'test-server.yml', 'trusted_entry.py'})
MUTABLE = (CHANGED, 'config.json', 'runtime-lock.json')
SYSTEMCTL = '/usr/bin/systemctl'
DOCKER = '/usr/bin/docker'
CI = 'nexgrid-ci-agent'


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
    require(old['files'][CHANGED] == OLD_MIGRATIONS_SHA, 'MIGRATIONS_BASELINE_PIN_MISMATCH')
    require({name for name in FILES if old['files'][name] != new['files'][name]} == {CHANGED},
            'UNEXPECTED_RUNTIME_CHANGE')
    require(new['files'][CHANGED] == NEW_MIGRATIONS_SHA, 'MIGRATIONS_CANDIDATE_PIN_MISMATCH')


def preflight(old, *, timer_active, installed=False):
    trusted(INSTALL / 'config.json')
    raw = (INSTALL / 'config.json').read_bytes()
    config = json.loads(raw)
    require(isinstance(config, dict), 'CONFIG_REJECTED')
    require(config.get(FLAG) is True if installed else FLAG not in config, 'COMPUTE_V2_APPROVAL_FLAG_DRIFT')
    if installed:
        require('allow_fixed_h4_trigger_install' not in config, 'RETIRED_H4_APPROVAL_REMAINS')
    internal = config.get('trusted_files')
    external = config.get('trusted_external_files')
    jobs = config.get('job_hashes')
    require(isinstance(internal, dict) and CHANGED in internal
            and isinstance(external, dict) and external
            and isinstance(jobs, dict) and set(jobs) == {'backend', 'pc', 'uniapp'},
            'CONFIG_PINS_REJECTED')
    require(config.get('policy_sha256') == EXPECTED_JAR_POLICY_SHA,
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
    for name in ('transaction.json', 'HALTED.json', 'HALTED'):
        absent(ROOT / name, 'RELEASE_BUSY')
    for name in ('active.json', 'START_BLOCKED'):
        absent(ROOT / 'migrations' / name, 'MIGRATION_RECOVERY_REQUIRED')
    absent(PERMIT, 'MIGRATION_START_PERMIT_PRESENT')
    require(command(SYSTEMCTL, 'show', '--property=ActiveState', '--value',
                    'nexgrid-release.service') == b'inactive', 'RELEASE_SERVICE_BUSY')
    require(command(SYSTEMCTL, 'show', '--property=MainPID', '--value',
                    'nexgrid-release.service') == b'0', 'RELEASE_SERVICE_BUSY')
    if timer_active:
        require(command(SYSTEMCTL, 'is-active', 'nexgrid-release.timer') == b'active',
                'TIMER_NOT_ACTIVE')
    else:
        require(command(SYSTEMCTL, 'show', '--property=ActiveState', '--value',
                        'nexgrid-release.timer') == b'inactive', 'TIMER_NOT_STOPPED')
    state = ((ROOT / 'state.json').read_bytes(), (ROOT / 'migrations/state.json').read_bytes())
    return config, raw, state


def build_xml(path):
    require(all(not item.is_symlink() for item in (path, *path.parents)), 'JOB_PATH_REJECTED')
    with path.open('rb') as stream:
        raw = stream.read(8 * 1024 * 1024 + 1)
    require(len(raw) <= 8 * 1024 * 1024 and b'<!DOCTYPE' not in raw.upper()
            and b'<!ENTITY' not in raw.upper(), 'BUILD_XML_REJECTED')
    return ET.fromstring(raw.decode('utf-8-sig'))


def ci_identity():
    value = json.loads(command(DOCKER, 'inspect', '--format',
        '{"id":{{json .Id}},"name":{{json .Name}},"image":{{json .Image}},'
        '"running":{{json .State.Running}},"paused":{{json .State.Paused}},"pid":{{json .State.Pid}}}', CI))
    require(re.fullmatch('[a-f0-9]{64}', value.get('id', ''))
            and value.get('name') == '/' + CI and re.fullmatch('sha256:[a-f0-9]{64}', value.get('image', ''))
            and value.get('running') is True and type(value.get('paused')) is bool
            and type(value.get('pid')) is int and value['pid'] > 0, 'CI_IDENTITY_REJECTED')
    return value


def idle(ci):
    expected = {'nexgrid-' + kind + '-test' for kind in ('backend','pc','uniapp')}
    archives = {name.removesuffix('-test') + '-main' for name in expected}
    jobs = {path.name for path in JOBS.iterdir() if path.is_dir()}
    require(expected <= jobs <= expected | archives, 'UNEXPECTED_JENKINS_JOBS')
    for job in jobs:
        if job in archives:
            config = build_xml(JOBS / job / 'config.xml')
            require(config.tag == 'flow-definition' and config.findtext('disabled','').strip() == 'true', 'ARCHIVED_JOB_ENABLED')
        builds = [path for path in (JOBS / job / 'builds').iterdir() if path.name.isdigit()]
        require(builds and all(build_xml(path / 'build.xml').findtext('completed') == 'true' for path in builds), 'WAIT_FOR_IDLE_CI')
    processes = command(DOCKER, 'top', ci['id'], '-eo', 'pid,comm').decode('ascii').splitlines()
    require(1 < len(processes) <= 4 and all(len(row.split()) == 2 and row.split()[0].isdigit()
            and row.split()[1] in {'tini','docker-init','java'} for row in processes[1:])
            and sum(row.split()[1] == 'java' for row in processes[1:]) == 1, 'WAIT_FOR_IDLE_AGENT')


def same_ci(original, paused):
    current = ci_identity()
    require(all(current[name] == original[name] for name in ('id','name','image','pid'))
            and current['paused'] is paused, 'CI_IDENTITY_OR_PAUSE_DRIFT')
    idle(current)


def sync(directory):
    descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def identity(path):
    info = path.lstat()
    return (info.st_dev, info.st_ino, info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode))


def replace_bound(path, data, mode, expected):
    # These three files can be individually bind-mounted into the CI container.
    # The agent remains paused and the shared release lock remains held throughout.
    trusted(path)
    require(identity(path) == expected, 'BOUND_FILE_IDENTITY_DRIFT')
    descriptor = os.open(path, os.O_RDWR | getattr(os,'O_NOFOLLOW',0))
    with os.fdopen(descriptor, 'r+b') as stream:
        info = os.fstat(stream.fileno())
        require((info.st_dev,info.st_ino,info.st_uid,info.st_gid,stat.S_IMODE(info.st_mode)) == expected, 'BOUND_FILE_IDENTITY_DRIFT')
        stream.write(data)
        stream.truncate()
        stream.flush()
        os.fchmod(stream.fileno(), mode)
        os.fsync(stream.fileno())
    require(identity(path) == expected and path.read_bytes() == data, 'BOUND_FILE_WRITE_NOT_CONFIRMED')


def snapshot():
    return {name: ((INSTALL / name).read_bytes(), stat.S_IMODE((INSTALL / name).lstat().st_mode))
            for name in MUTABLE}


def backup_files(backup, previous, new_pin, identities):
    absent(backup, 'BACKUP_EXISTS')
    backup.mkdir(mode=0o700)
    metadata = {'old_lock_sha256': OLD_LOCK, 'new_lock_sha256': new_pin,
                'files': {}}
    for name, (data, mode) in previous.items():
        with (backup / name).open('xb') as stream:
            stream.write(data)
            stream.flush()
            (backup / name).chmod(0o600)
            os.fsync(stream.fileno())
        require((backup / name).read_bytes() == data, 'BACKUP_VERIFY_FAILED')
        metadata['files'][name] = {'sha256': digest(data), 'mode': mode, 'identity': list(identities[name])}
    with (backup / 'backup-manifest.json').open('xb') as stream:
        stream.write((json.dumps(metadata, indent=2, sort_keys=True) + '\n').encode())
        stream.flush()
        (backup / 'backup-manifest.json').chmod(0o600)
        os.fsync(stream.fileno())
    sync(backup)
    sync(backup.parent)


def update(stage, pin):
    require(os.geteuid() == 0 and sys.flags.isolated, 'ISOLATED_ROOT_REQUIRED')
    trusted(Path(__file__).absolute())
    require(stage.parent == STAGES and stage.resolve() == stage, 'STAGE_REJECTED')
    new, new_raw, sources = closure(stage, pin)
    require({path.name for path in stage.iterdir()} == FILES | {'runtime-lock.json'}, 'STAGE_INVENTORY_REJECTED')
    old, _, _ = closure(INSTALL, OLD_LOCK)
    validate_change(old, new)
    # Syntax-check the sole changed, SHA-verified source; never execute it.
    compile(sources[CHANGED], str(stage / CHANGED), 'exec')
    timer_state = command(SYSTEMCTL, 'show', '--property=ActiveState', '--value', 'nexgrid-release.timer')
    require(timer_state in (b'active', b'inactive'), 'TIMER_STATE_REJECTED')
    timer_was_active = timer_state == b'active'
    _, config_raw, state = preflight(old, timer_active=timer_was_active)
    ci = ci_identity()
    require(not ci['paused'], 'CI_ALREADY_PAUSED')
    idle(ci)
    trusted(BACKUPS, directory=True)
    backup = BACKUPS / ('compute-v2-migrations-' + time.strftime('%Y%m%d-%H%M%S', time.gmtime()))
    absent(backup, 'BACKUP_EXISTS')
    previous = snapshot()
    identities = {name: identity(INSTALL / name) for name in MUTABLE}
    command(SYSTEMCTL, 'stop', 'nexgrid-release.timer')
    safe_to_resume = False
    import fcntl
    with (ROOT / 'lock').open('r+b') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            old_again, _, _ = closure(INSTALL, OLD_LOCK)
            new_again, raw_again, sources_again = closure(stage, pin)
            validate_change(old_again, new_again)
            config_again, config_raw_again, state_again = preflight(old_again, timer_active=False)
            require(config_raw_again == config_raw and state_again == state
                    and raw_again == new_raw and sources_again == sources and snapshot() == previous,
                    'UPDATE_PREFLIGHT_DRIFT')
            same_ci(ci, False)
            command(DOCKER, 'pause', ci['id'])
            same_ci(ci, True)
            # Pausing can race a job launch; verify the whole closed source set again.
            closure(INSTALL, OLD_LOCK)
            require({path.name for path in stage.iterdir()} == FILES | {'runtime-lock.json'}
                    and closure(stage, pin)[1:] == (new_raw,sources) and snapshot() == previous
                    and all(identity(INSTALL / name) == identities[name] for name in MUTABLE), 'UPDATE_PREFLIGHT_DRIFT')
            _, raw_paused, state_paused = preflight(old, timer_active=False)
            require(raw_paused == config_raw and state_paused == state, 'UPDATE_PREFLIGHT_DRIFT')
            backup_files(backup, previous, pin, identities)
            print('COMPUTE_V2_MIGRATIONS_BACKUP_READY ' + str(backup), flush=True)
            config_again['trusted_files'][CHANGED] = new['files'][CHANGED]
            config_again[FLAG] = True
            config_again.pop('allow_fixed_h4_trigger_install', None)
            try:
                replace_bound(INSTALL / CHANGED, sources[CHANGED], previous[CHANGED][1], identities[CHANGED])
                replace_bound(INSTALL / 'config.json',
                       (json.dumps(config_again, indent=2, sort_keys=True) + '\n').encode(),
                       previous['config.json'][1], identities['config.json'])
                replace_bound(INSTALL / 'runtime-lock.json', new_raw, previous['runtime-lock.json'][1], identities['runtime-lock.json'])
                closure(INSTALL, pin)
                installed, _, installed_state = preflight(new, timer_active=False, installed=True)
                require(installed == config_again and installed_state == state, 'INSTALLED_CONFIG_OR_STATE_DRIFT')
                require((ROOT / 'state.json').read_bytes() == state[0]
                        and (ROOT / 'migrations/state.json').read_bytes() == state[1], 'STATE_DRIFT')
                safe_to_resume = True
                print('COMPUTE_V2_MIGRATIONS_INSTALLED ' + pin, flush=True)
            except BaseException:
                for name, (data, mode) in previous.items():
                    replace_bound(INSTALL / name, data, mode, identities[name])
                closure(INSTALL, OLD_LOCK)
                require(snapshot() == previous, 'ROLLBACK_BYTES_OR_MODE_DRIFT')
                _, restored_raw, restored_state = preflight(old, timer_active=False)
                require(restored_raw == config_raw and restored_state == state, 'ROLLBACK_STATE_DRIFT')
                safe_to_resume = True
                print('COMPUTE_V2_MIGRATIONS_RESTORED ' + str(backup), flush=True)
                raise
        finally:
            if safe_to_resume:
                try:
                    same_ci(ci, True)
                    command(DOCKER, 'unpause', ci['id'])
                    same_ci(ci, False)
                    if timer_was_active:
                        command(SYSTEMCTL, 'start', 'nexgrid-release.timer')
                except BaseException:
                    print('OPERATOR_RECOVERY_REQUIRED_TIMER_STATE_UNCERTAIN ' + str(backup), flush=True)
                    raise
            elif not safe_to_resume:
                print('OPERATOR_RECOVERY_REQUIRED_TIMER_STOPPED ' + str(backup), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', type=Path)
    parser.add_argument('lock_sha256')
    args = parser.parse_args()
    update(args.stage, args.lock_sha256)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        reason = str(error)
        print(reason if re.fullmatch(r'[A-Z0-9_]+', reason) else type(error).__name__, flush=True)
        raise SystemExit(1)

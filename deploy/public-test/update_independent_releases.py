#!/usr/bin/env python3
"""One-time operator update from an externally SHA-pinned immutable GitHub commit.

Not part of the automatic broker runtime. Run with python3 -I as root, after
independently verifying this updater and the supplied new runtime-lock SHA256.
Only broker/CI build rules change; preserve services, data, jobs, auth and policies.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

INSTALL = Path('/srv/jenkins/release')
ROOT = Path('/srv/nexgrid/cd')
BUILD_SCRIPT = Path('/srv/jenkins/install/ci-build.sh')
JOBS = Path('/var/lib/docker/volumes/nexgrid_jenkins_home/_data/jobs')
BACKUPS = Path('/srv/jenkins/backups')
STAGE_PARENT = Path('/srv/jenkins/updates')
OLD_LOCK = '51f53a1734797d7ed65495c97df0e3aed61225ca06d8c63101c3fcf1cddb1bfd'
CHANGES = {'release_broker.py', 'ci-build.sh'}


def require(condition, reason):
    if not condition:
        raise RuntimeError(reason)


def trusted(path):
    for item in (path, *path.parents):
        st = item.lstat()
        require(not stat.S_ISLNK(st.st_mode) and st.st_uid == 0 and st.st_mode & 0o022 == 0,
                'UNTRUSTED_PATH')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def command(*args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=90)
    require(result.returncode == 0, 'UPDATE_COMMAND_FAILED_' + Path(args[0]).name.upper())
    return result.stdout.strip()


def closure(directory, expected):
    lock = directory / 'runtime-lock.json'
    trusted(lock)
    raw = lock.read_bytes()
    require(sha(raw) == expected, 'LOCK_PIN_MISMATCH')
    manifest = json.loads(raw)
    require(manifest.get('version') == 1 and len(manifest['files']) == 12, 'CLOSURE_REJECTED')
    for name, value in manifest['files'].items():
        require(re.fullmatch(r'[a-zA-Z0-9_.-]+', name) and re.fullmatch(r'[0-9a-f]{64}', value), 'MANIFEST_REJECTED')
        file = directory / name
        trusted(file)
        require(file.is_file() and sha(file.read_bytes()) == value, 'RUNTIME_PIN_MISMATCH')
    return manifest


def validate_change(old, new):
    require(set(old['files']) == set(new['files']), 'RUNTIME_CLOSURE_CHANGE_REJECTED')
    changed = {name for name in old['files'] if old['files'][name] != new['files'][name]}
    require(changed == CHANGES, 'UNEXPECTED_RUNTIME_CHANGE')


def assert_idle():
    expected = {f'nexgrid-{kind}-main' for kind in ['backend', 'pc', 'uniapp']}
    require({p.name for p in JOBS.iterdir() if p.is_dir()} == expected, 'UNEXPECTED_JENKINS_JOBS')
    for kind in ['backend', 'pc', 'uniapp']:
        builds = JOBS / f'nexgrid-{kind}-main/builds'
        numbers = [int(p.name) for p in builds.iterdir() if p.name.isdigit()]
        require(numbers, 'MISSING_BUILD_HISTORY')
        data = (builds / str(max(numbers)) / 'build.xml').read_bytes()
        require(len(data) < 8*1024*1024 and b'<!DOCTYPE' not in data.upper(), 'BUILD_XML_REJECTED')
        require(ET.fromstring(data).findtext('completed') == 'true', 'WAIT_FOR_IDLE_CI')
    # Docker top also works while paused. These dedicated jobs always launch a
    # shell/git/node/Maven subprocess when doing work; only init/remoting may remain.
    processes = command('docker', 'top', 'nexgrid-ci-agent', '-eo', 'comm').splitlines()
    require(len(processes) > 1 and {p.strip() for p in processes[1:]} <= {'tini', 'java'}, 'WAIT_FOR_IDLE_AGENT')


def atomic(path, data, mode=0o600):
    temp = path.with_name(path.name + '.policy-update-new')
    require(not temp.exists() and not temp.is_symlink(), 'UPDATE_TEMP_EXISTS')
    with temp.open('xb') as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    temp.chmod(mode)
    temp.replace(path)
    sync(path.parent)


def sync(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def replace_bound_script(data):
    # The idle agent is paused. Preserve this bind-mounted inode so it sees the
    # complete new script on resume, without restarting any CI/business container.
    trusted(BUILD_SCRIPT)
    inode = BUILD_SCRIPT.stat().st_ino
    with BUILD_SCRIPT.open('r+b') as stream:
        stream.write(data)
        stream.truncate()
        stream.flush()
        os.fsync(stream.fileno())
    require(BUILD_SCRIPT.stat().st_ino == inode and BUILD_SCRIPT.read_bytes() == data, 'BOUND_SCRIPT_UPDATE_FAILED')


def update(stage, lock_hash):
    import fcntl
    require(os.geteuid() == 0 and sys.flags.isolated, 'ISOLATED_ROOT_REQUIRED')
    require(stage.parent == STAGE_PARENT and stage.resolve() == stage, 'STAGE_PATH_REJECTED')
    new = closure(stage, lock_hash)
    old = closure(INSTALL, OLD_LOCK)
    validate_change(old, new)
    trusted(INSTALL / 'config.json')
    trusted(ROOT / 'lock')
    trusted(BUILD_SCRIPT)
    require(sha(BUILD_SCRIPT.read_bytes()) == old['files']['ci-build.sh'], 'BOUND_SCRIPT_DRIFT')
    config = json.loads((INSTALL / 'config.json').read_bytes())
    require(config['trusted_files']['release_broker.py'] == old['files']['release_broker.py'], 'BROKER_PIN_DRIFT')
    for kind, expected in config['job_hashes'].items():
        require(sha((JOBS / f'nexgrid-{kind}-main/config.xml').read_bytes()) == expected, 'JOB_DRIFT')
    assert_idle()
    require(command('systemctl', 'is-active', 'nexgrid-release.timer') == 'active', 'EXPECTED_AUTO_TIMER')
    backup = BACKUPS / ('independent-releases-' + time.strftime('%Y%m%d-%H%M%S', time.gmtime()))
    trusted(backup.parent)
    require(not backup.exists(), 'BACKUP_EXISTS')
    command('systemctl', 'stop', 'nexgrid-release.timer')
    safe_to_resume, paused = True, False
    try:
        with (ROOT / 'lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            require(not (ROOT / 'transaction.json').exists() and not (ROOT / 'HALTED.json').exists(), 'RELEASE_RECOVERY_REQUIRED')
            command('docker', 'pause', 'nexgrid-ci-agent')
            paused = True
            assert_idle()  # If a build raced the first check, resume without writes.
            backup.mkdir(mode=0o700)
            names = [*old['files'], 'runtime-lock.json', 'config.json']
            for name in names:
                shutil.copyfile(INSTALL / name, backup / name)
                (backup / name).chmod(0o600)
            shutil.copyfile(BUILD_SCRIPT, backup / 'bound-ci-build.sh')
            (backup / 'bound-ci-build.sh').chmod(0o600)
            for path in backup.iterdir():
                with path.open('r+b') as stream:
                    os.fsync(stream.fileno())
            sync(backup)
            print('POLICY_BACKUP_READY ' + str(backup), flush=True)
            safe_to_resume = False
            try:
                replace_bound_script((stage / 'ci-build.sh').read_bytes())
                for name in sorted(CHANGES):
                    atomic(INSTALL / name, (stage / name).read_bytes(), 0o644)
                config['trusted_files']['release_broker.py'] = new['files']['release_broker.py']
                atomic(INSTALL / 'config.json', (json.dumps(config, indent=2, sort_keys=True)+'\n').encode())
                atomic(INSTALL / 'runtime-lock.json', (stage / 'runtime-lock.json').read_bytes(), 0o644)
                closure(INSTALL, lock_hash)
                command('bash', '-n', str(BUILD_SCRIPT))
                command('/usr/bin/python3', '-I', str(INSTALL / 'trusted_entry.py'), 'broker', '--help')
                safe_to_resume = True
                print('INDEPENDENT_RELEASE_POLICY_INSTALLED ' + lock_hash, flush=True)
            except BaseException:
                # The agent is still paused and the broker lock is held. Restore
                # only this update's files; no state, jobs, secrets or data rollback.
                replace_bound_script((backup / 'bound-ci-build.sh').read_bytes())
                for name in [*sorted(CHANGES), 'runtime-lock.json', 'config.json']:
                    atomic(INSTALL / name, (backup / name).read_bytes(), 0o600 if name == 'config.json' else 0o644)
                closure(INSTALL, OLD_LOCK)
                safe_to_resume = True
                print('POLICY_UPDATE_RESTORED_PREVIOUS_RUNTIME', flush=True)
                raise
    finally:
        try:
            require(safe_to_resume, 'UPDATE_ROLLBACK_INCOMPLETE')
            if paused:
                command('docker', 'unpause', 'nexgrid-ci-agent')
            command('systemctl', 'start', 'nexgrid-release.timer')
        except BaseException:
            print('OPERATOR_RECOVERY_REQUIRED; check agent and timer; backup ' + str(backup), flush=True)
            raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('stage', type=Path)
    parser.add_argument('lock_sha256')
    args = parser.parse_args()
    try:
        require(re.fullmatch(r'[0-9a-f]{64}', args.lock_sha256), 'LOCK_ARGUMENT_REJECTED')
        update(args.stage, args.lock_sha256)
    except Exception as error:
        reason = str(error)
        print(reason if re.fullmatch('[A-Z_]+', reason) else type(error).__name__, flush=True)
        raise SystemExit(1)

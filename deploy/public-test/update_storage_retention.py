#!/usr/bin/env python3
"""One-time root update for release and Jenkins retention on the TEST server."""
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
BOUND_PIPELINE = Path('/srv/jenkins/install/main.pipeline.groovy')
BOUND_BUILD = Path('/srv/jenkins/install/ci-build.sh')
JOBS = Path('/var/lib/docker/volumes/nexgrid_jenkins_home/_data/jobs')
ROOT = Path('/srv/nexgrid/cd')
BACKUPS = Path('/srv/jenkins/backups')
STAGE_PARENT = Path('/srv/jenkins/updates')
OLD_LOCK = 'b1f96dc229031186961944dfdb01026e49b58e3ff246986ced09b75b00dcb5e0'
CHANGES = {'release_broker.py', 'main.pipeline.groovy', 'ci-build.sh'}


def require(condition, reason):
    if not condition:
        raise RuntimeError(reason)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def command(*args, timeout=180):
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    require(result.returncode == 0, 'COMMAND_FAILED_' + Path(args[0]).name.upper())
    return result.stdout.strip()


def trusted(path):
    for item in (path, *path.parents):
        info = item.lstat()
        require(not stat.S_ISLNK(info.st_mode) and info.st_uid == 0 and info.st_mode & 0o022 == 0,
                'UNTRUSTED_PATH')


def closure(directory, expected):
    lock = directory / 'runtime-lock.json'
    trusted(lock)
    raw = lock.read_bytes()
    require(sha(raw) == expected, 'LOCK_PIN_MISMATCH')
    manifest = json.loads(raw)
    require(manifest.get('version') == 1 and len(manifest.get('files', {})) == 13,
            'CLOSURE_REJECTED')
    for name, value in manifest['files'].items():
        require(re.fullmatch(r'[A-Za-z0-9_.-]+', name) and re.fullmatch(r'[0-9a-f]{64}', value),
                'MANIFEST_REJECTED')
        path = directory / name
        trusted(path)
        require(path.is_file() and sha(path.read_bytes()) == value, 'RUNTIME_PIN_MISMATCH')
    return manifest


def atomic(path, data, mode=0o600, owner=None):
    temporary = path.with_name(path.name + '.storage-retention-new')
    if temporary.exists() or temporary.is_symlink():
        info = temporary.lstat()
        require(not stat.S_ISLNK(info.st_mode) and stat.S_ISREG(info.st_mode)
                and info.st_uid == 0, 'UPDATE_TEMP_REJECTED')
        temporary.unlink()
    try:
        with temporary.open('xb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        temporary.chmod(mode)
        if owner:
            os.chown(temporary, *owner)
        temporary.replace(path)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def replace_bound(path, data):
    trusted(path)
    inode = path.stat().st_ino
    with path.open('r+b') as stream:
        stream.write(data)
        stream.truncate()
        stream.flush()
        os.fsync(stream.fileno())
    require(path.stat().st_ino == inode and path.read_bytes() == data,
            'BOUND_FILE_UPDATE_FAILED')


def idle_jobs():
    expected = {f'nexgrid-{kind}-test' for kind in ('backend', 'pc', 'uniapp')}
    require(expected <= {path.name for path in JOBS.iterdir() if path.is_dir()},
            'MISSING_TEST_JENKINS_JOBS')
    for name in expected:
        builds = JOBS / name / 'builds'
        numbers = [int(path.name) for path in builds.iterdir() if path.name.isdigit()]
        require(numbers, 'MISSING_BUILD_HISTORY')
        xml = ET.fromstring((builds / str(max(numbers)) / 'build.xml').read_bytes())
        require(xml.findtext('completed') == 'true', 'WAIT_FOR_IDLE_CI')
    processes = command('docker', 'top', 'nexgrid-ci-agent', '-eo', 'pid,comm').splitlines()
    require(len(processes) > 1 and all(len(line.split()) == 2 and line.split()[1] in
            {'tini', 'docker-init', 'java'} for line in processes[1:]), 'WAIT_FOR_IDLE_AGENT')


def retained_job_xml(path, template, kind):
    root = ET.fromstring(path.read_bytes())
    script = root.find('./definition/script')
    require(script is not None and 'RELEASE_ARTIFACT_READY' in (script.text or ''),
            'EXPECTED_RELEASE_JOB')
    repositories = {'backend': 'nexion-backend', 'pc': 'nexion-frontend-pc',
                    'uniapp': 'nexion-frontend-uniapp'}
    script.text = template.replace('@KIND@', kind).replace(
        '@REPO@', f'https://github.com/agentabatiuo572-byte/{repositories[kind]}.git')
    properties = root.find('properties')
    require(properties is not None, 'JOB_PROPERTIES_REQUIRED')
    for old in properties.findall('jenkins.model.BuildDiscarderProperty'):
        properties.remove(old)
    discard = ET.SubElement(properties, 'jenkins.model.BuildDiscarderProperty')
    strategy = ET.SubElement(discard, 'strategy', {'class': 'hudson.tasks.LogRotator'})
    for name, value in [('daysToKeep', '-1'), ('numToKeep', '10'),
                        ('artifactDaysToKeep', '-1'), ('artifactNumToKeep', '5')]:
        ET.SubElement(strategy, name).text = value
    return ET.tostring(root, encoding='utf-8', xml_declaration=True)


def update(stage, new_lock_hash):
    import fcntl
    require(os.geteuid() == 0 and sys.flags.isolated, 'ISOLATED_ROOT_REQUIRED')
    require(stage.parent == STAGE_PARENT and stage.resolve() == stage, 'STAGE_PATH_REJECTED')
    old = closure(INSTALL, OLD_LOCK)
    new = closure(stage, new_lock_hash)
    require(set(old['files']) == set(new['files']), 'CLOSURE_CHANGE_REJECTED')
    changed = {name for name in old['files'] if old['files'][name] != new['files'][name]}
    require(changed == CHANGES, 'UNEXPECTED_RUNTIME_CHANGE')
    idle_jobs()
    require(command('systemctl', 'is-active', 'nexgrid-release.timer') == 'active',
            'EXPECTED_RELEASE_TIMER')
    backup = BACKUPS / ('storage-retention-' + time.strftime('%Y%m%d-%H%M%S', time.gmtime()))
    require(not backup.exists(), 'BACKUP_EXISTS')
    backup.mkdir(mode=0o700)
    for name in [*CHANGES, 'runtime-lock.json', 'config.json']:
        shutil.copyfile(INSTALL / name, backup / name)
    shutil.copyfile(BOUND_PIPELINE, backup / 'bound-main.pipeline.groovy')
    shutil.copyfile(BOUND_BUILD, backup / 'bound-ci-build.sh')
    for kind in ('backend', 'pc', 'uniapp'):
        shutil.copyfile(JOBS / f'nexgrid-{kind}-test/config.xml', backup / f'{kind}-job.xml')
    paused = controller_stopped = timer_stopped = False
    with (ROOT / 'lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        command('systemctl', 'stop', 'nexgrid-release.timer')
        timer_stopped = True
        try:
            require(not (ROOT / 'transaction.json').exists() and not (ROOT / 'HALTED.json').exists(),
                    'RELEASE_RECOVERY_REQUIRED')
            command('docker', 'pause', 'nexgrid-ci-agent')
            paused = True
            idle_jobs()
            command('docker', 'stop', '--time', '30', 'nexgrid-jenkins')
            controller_stopped = True
            template = (stage / 'main.pipeline.groovy').read_text()
            replace_bound(BOUND_PIPELINE, template.encode())
            replace_bound(BOUND_BUILD, (stage / 'ci-build.sh').read_bytes())
            for name in CHANGES:
                atomic(INSTALL / name, (stage / name).read_bytes(), 0o644)
            config = json.loads((INSTALL / 'config.json').read_text())
            config['trusted_files']['release_broker.py'] = new['files']['release_broker.py']
            for kind in ('backend', 'pc', 'uniapp'):
                path = JOBS / f'nexgrid-{kind}-test/config.xml'
                data = retained_job_xml(path, template, kind)
                atomic(path, data, 0o644, (1000, 1000))
                config['job_hashes'][kind] = sha(data)
            atomic(INSTALL / 'config.json', (json.dumps(config, indent=2, sort_keys=True) + '\n').encode())
            atomic(INSTALL / 'runtime-lock.json', (stage / 'runtime-lock.json').read_bytes(), 0o644)
            command('docker', 'start', 'nexgrid-jenkins')
            controller_stopped = False
            command('docker', 'unpause', 'nexgrid-ci-agent')
            paused = False
            command('systemctl', 'start', 'nexgrid-release.timer')
            timer_stopped = False
            print('STORAGE_RETENTION_INSTALLED ' + new_lock_hash, flush=True)
        except BaseException:
            if not controller_stopped:
                command('docker', 'stop', '--time', '30', 'nexgrid-jenkins')
            replace_bound(BOUND_PIPELINE, (backup / 'bound-main.pipeline.groovy').read_bytes())
            replace_bound(BOUND_BUILD, (backup / 'bound-ci-build.sh').read_bytes())
            for name in [*CHANGES, 'runtime-lock.json', 'config.json']:
                atomic(INSTALL / name, (backup / name).read_bytes(),
                       0o600 if name == 'config.json' else 0o644)
            for kind in ('backend', 'pc', 'uniapp'):
                atomic(JOBS / f'nexgrid-{kind}-test/config.xml',
                       (backup / f'{kind}-job.xml').read_bytes(), 0o644, (1000, 1000))
            command('docker', 'start', 'nexgrid-jenkins')
            if paused:
                command('docker', 'unpause', 'nexgrid-ci-agent')
            if timer_stopped:
                command('systemctl', 'start', 'nexgrid-release.timer')
            print('STORAGE_RETENTION_RESTORED ' + str(backup), flush=True)
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
        print(reason if re.fullmatch(r'[A-Z0-9_]+', reason) else type(error).__name__, flush=True)
        raise SystemExit(1)

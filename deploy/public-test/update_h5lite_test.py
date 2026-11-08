#!/usr/bin/env python3
"""One-time, externally SHA-pinned TEST Lite upgrade; never run from Jenkins.

The supplied new lock and resource source hashes must be independently approved.
The installed 13-file baseline is fixed below; unchanged members are never copied
from repository examples. No build, promotion, SQL, budget or receipt is changed.
"""
import argparse
from contextlib import ExitStack
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
HOME = Path('/var/lib/docker/volumes/nexgrid_jenkins_home/_data')
JOBS = HOME / 'jobs'
STAGES = Path('/mnt/test-scratch/uvel-tests/be27-lite-test-entry-20261108')
BACKUPS = Path('/srv/jenkins/backups')
RESOURCE = Path('/opt/uvel-task-control/resource-control.py')
CONTROL = Path('/var/lib/uvel-task-control')
BUDGET = Path('/etc/uvel-task-control/budget.json')
NGINX = Path('/etc/nginx/sites-available/nexgrid-public')
LOCK_NAME = 'runtime-lock-20261108-host-h5lite.json'
OLD_LOCK_SHA = '61929e7cb3f57d3b2772748fa7fb27155c3cb0e53f6ee2f3b086a47665163cf3'
OLD_RESOURCE_SHA = '129569c7d164de4c3402995dc87424f4c838a304bc5eeb788793b9ec419b09d7'
RETIREMENT_SHA = 'cf920ab9a76f42bfa72869c1f050febd11645daf918ba9d0d3517ab4e59c5767'
CHANGED = {'40-release-jobs.groovy', 'ci-build.sh', 'install_release.py',
           'main.pipeline.groovy', 'release_broker.py'}
BOUND = {Path('/srv/jenkins/install/ci-build.sh'): 'ci-build.sh',
         Path('/srv/jenkins/install/main.pipeline.groovy'): 'main.pipeline.groovy'}
BOUND_OLD_SHA = {Path('/srv/jenkins/install/ci-build.sh'):
                '2de25be6d6fc4460b99fd52e7e985195bc9ad025b15cd279ef0d3e1b5f216bbe',
                Path('/srv/jenkins/install/main.pipeline.groovy'):
                'ecc862d7833c627c413a5c8e2db82939a10cdb338c6301f72faa8a60b6d3ce45'}
BASE_COMPONENTS = ('backend', 'pc', 'uniapp')
LITE = JOBS / 'nexgrid-h5lite-test'
ABSENT_LITE = {'build': 0, 'sha': None, 'port': None, 'container': None}


def require(condition, reason):
    if not condition:
        raise RuntimeError(reason)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return (json.dumps(value, indent=2, sort_keys=True) + '\n').encode()


def trusted(path, directory=False):
    for index, part in enumerate((path, *path.parents)):
        info = part.lstat()
        kind = stat.S_ISDIR(info.st_mode) if directory or index else stat.S_ISREG(info.st_mode)
        require(kind and info.st_uid == 0 and not info.st_mode & 0o022, 'UNTRUSTED_PATH')


def data_file(path):
    require(all(not part.is_symlink() for part in (path, *path.parents))
            and path.is_file(), 'JENKINS_DATA_PATH_REJECTED')
    raw = path.read_bytes()
    require(len(raw) <= 8 * 1024 * 1024 and b'<!DOCTYPE' not in raw.upper()
            and b'<!ENTITY' not in raw.upper(), 'JENKINS_XML_REJECTED')
    return ET.fromstring(raw.decode('utf-8-sig'))


def command(*args):
    result = subprocess.run(args, capture_output=True, timeout=180,
                            env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C'})
    require(result.returncode == 0, 'UPDATE_COMMAND_FAILED_' + Path(args[0]).name.upper())
    return result.stdout.strip()


def closure(directory, raw, expected):
    require(re.fullmatch('[0-9a-f]{64}', expected) and sha(raw) == expected, 'LOCK_PIN_MISMATCH')
    lock = json.loads(raw)
    require(lock.get('version') == 1 and set(lock.get('files', {})) == {
        'trusted_entry.py', 'release_broker.py', 'schema_fingerprint.py', 'database_migrations.py',
        'install_release.py', 'ci-build.sh', 'main.pipeline.groovy', '40-release-jobs.groovy',
        'test-server.yml', 'h5-nginx.conf', 'public-test-policy.properties',
        'nexgrid-release.service', 'nexgrid-release.timer'}, 'CLOSURE_REJECTED')
    for name, pin in lock['files'].items():
        trusted(directory / name)
        require(sha((directory / name).read_bytes()) == pin, 'RUNTIME_PIN_MISMATCH')
    return lock


def identity(path):
    info = path.lstat()
    return [info.st_dev, info.st_ino, info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode)]


def replace_bound(path, data, expected):
    trusted(path)
    require(identity(path) == expected, 'BOUND_FILE_IDENTITY_DRIFT')
    descriptor = os.open(path, os.O_RDWR | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'r+b') as stream:
        info = os.fstat(stream.fileno())
        require([info.st_dev, info.st_ino, info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode)] == expected,
                'BOUND_FILE_IDENTITY_DRIFT')
        stream.write(data)
        stream.truncate()
        stream.flush()
        os.fsync(stream.fileno())
    require(identity(path) == expected and path.read_bytes() == data, 'BOUND_FILE_WRITE_NOT_CONFIRMED')


def sync(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def container(name):
    value = json.loads(command('docker', 'inspect', '--format',
        '{"id":{{json .Id}},"running":{{json .State.Running}},"paused":{{json .State.Paused}},'
        '"pid":{{json .State.Pid}},"memory":{{json .HostConfig.Memory}},'
        '"swap":{{json .HostConfig.MemorySwap}},"mounts":{{json .Mounts}}}', name))
    require(value['running'] is True and value['pid'] > 0, 'CI_NOT_RUNNING')
    value['mounts'] = sorted(value['mounts'], key=lambda mount: mount['Destination'])
    return value


def idle(config, lite=False):
    expected = {f'nexgrid-{kind}-test' for kind in BASE_COMPONENTS}
    archives = {f'nexgrid-{kind}-main' for kind in BASE_COMPONENTS}
    if lite:
        expected.add('nexgrid-h5lite-test')
    jobs = {path.name for path in JOBS.iterdir() if path.is_dir()}
    require(expected <= jobs <= expected | archives, 'UNEXPECTED_JENKINS_JOBS')
    for kind, pin in config['job_hashes'].items():
        path = JOBS / f'nexgrid-{kind}-test/config.xml'
        data_file(path)
        require(sha(path.read_bytes()) == pin, 'JOB_CONFIGURATION_DRIFT')
    for name in jobs:
        xml = data_file(JOBS / name / 'config.xml')
        if name in archives:
            require(xml.tag == 'flow-definition' and xml.findtext('disabled') == 'true', 'ARCHIVED_JOB_ENABLED')
        builds = JOBS / name / 'builds'
        for path in builds.iterdir() if builds.exists() else []:
            if path.name.isdigit():
                require(data_file(path / 'build.xml').findtext('completed') == 'true', 'WAIT_FOR_IDLE_CI')
    queue = HOME / 'queue.xml'
    if queue.exists():
        xml = data_file(queue)
        require(not xml.findall('.//item') and not xml.findall('.//hudson.model.Queue_-WaitingItem')
                and all(not list(node) for node in xml.findall('items')), 'JENKINS_QUEUE_NOT_EMPTY')
    reservations = CONTROL / 'build-reservations.json'
    if reservations.exists():
        trusted(reservations)
        for entry in json.loads(reservations.read_bytes()).values():
            require(entry['component'] in BASE_COMPONENTS and type(entry['number']) is int
                    and entry['number'] > 0, 'BUILD_RESERVATION_REJECTED')
            path = JOBS / f"nexgrid-{entry['component']}-test/builds/{entry['number']}/build.xml"
            require(data_file(path).findtext('completed') == 'true', 'WAIT_FOR_BUILD_RESERVATIONS')
    processes = command('docker', 'top', 'nexgrid-ci-agent', '-eo', 'pid,comm').decode().splitlines()
    require(1 < len(processes) <= 4 and all(len(row.split()) == 2 and row.split()[0].isdigit()
            and row.split()[1] in {'tini', 'docker-init', 'java'} for row in processes[1:])
            and sum(row.split()[1] == 'java' for row in processes[1:]) == 1, 'WAIT_FOR_IDLE_AGENT')


def preflight(old):
    for path in (INSTALL / 'config.json', ROOT / 'state.json', NGINX, RESOURCE,
                 BUDGET, BUDGET.with_name('retired-ci.json'), CONTROL / 'compute-retirement.json'):
        trusted(path)
    config = json.loads((INSTALL / 'config.json').read_bytes())
    require(set(config['job_hashes']) == set(BASE_COMPONENTS), 'EXISTING_COMPONENTS_REQUIRED')
    for name, pin in config['trusted_files'].items():
        require(old['files'].get(name) == pin, 'CONFIG_PIN_DRIFT')
    for name, pin in config['trusted_external_files'].items():
        trusted(Path(name))
        require(sha(Path(name).read_bytes()) == pin, 'EXTERNAL_PIN_DRIFT')
    require(sha(RESOURCE.read_bytes()) == OLD_RESOURCE_SHA, 'RESOURCE_SOURCE_PIN_DRIFT')
    budget = json.loads(BUDGET.read_bytes())
    receipt_raw = (CONTROL / 'compute-retirement.json').read_bytes()
    receipt = json.loads(receipt_raw)
    sealed = {key: value for key, value in budget.items() if key != 'retirementReceiptSha256'}
    sealed_sha = sha(json.dumps(sealed, sort_keys=True, separators=(',', ':'), allow_nan=False).encode())
    require(sha(receipt_raw) == RETIREMENT_SHA and budget['retirementReceiptSha256'] == RETIREMENT_SHA
            and budget['executionProfile'] == 'RETIRED' and receipt['retiredBudgetSha256'] == sealed_sha
            and budget['buildRequests'] == {kind: 1024**3 for kind in BASE_COMPONENTS}
            and json.loads(BUDGET.with_name('retired-ci.json').read_bytes()) == {
                'profile': 'CI_10_GIB', 'retirementReceiptSha256': RETIREMENT_SHA}, 'RETIRED_CI_PROFILE_DRIFT')
    require(not any((ROOT / name).exists() for name in
            ('transaction.json', 'HALTED.json', 'HALTED', 'migrations/active.json', 'migrations/START_BLOCKED'))
            and not any((CONTROL / name).exists() for name in ('drain.json', 'drain-receipt.json')),
            'RELEASE_OR_DRAIN_RECOVERY_REQUIRED')
    require(command('systemctl', 'show', '--property=ActiveState', '--value', 'nexgrid-release.service') == b'inactive'
            and command('systemctl', 'show', '--property=MainPID', '--value', 'nexgrid-release.service') == b'0',
            'RELEASE_SERVICE_BUSY')
    require(not (ROOT / 'AUTO_ENABLED').exists(), 'AUTO_MUST_REMAIN_HELD')
    state = json.loads((ROOT / 'state.json').read_bytes())
    require('h5lite' not in state and sha(NGINX.read_bytes()) == state['nginx_sha256']
            and NGINX.read_text().count('    include /srv/jenkins/install/jenkins-location.conf;') == 1
            and '/h5-lite' not in NGINX.read_text(), 'LITE_INGRESS_OR_STATE_DRIFT')
    require(not LITE.exists() and not LITE.is_symlink() and not (ROOT / 'h5lite').exists()
            and not (ROOT / 'h5lite').is_symlink(), 'LITE_MUST_BE_NEW')
    listeners = command('ss', '-H', '-ltn').decode().splitlines()
    require(not any(row.split()[3].rsplit(':', 1)[-1] in {'8084', '8085'} for row in listeners), 'LITE_PORT_BUSY')
    for path, name in BOUND.items():
        trusted(path)
        require(sha(path.read_bytes()) == BOUND_OLD_SHA[path], 'BOUND_SOURCE_PIN_DRIFT')
    idle(config)
    return config, state


def job_xml(template):
    root = ET.Element('flow-definition')
    ET.SubElement(root, 'actions')
    ET.SubElement(root, 'description').text = 'h5-lite TEST artifact; only the independent host broker can promote it.'
    ET.SubElement(root, 'keepDependencies').text = 'false'
    properties = ET.SubElement(root, 'properties')
    discard = ET.SubElement(properties, 'jenkins.model.BuildDiscarderProperty')
    strategy = ET.SubElement(discard, 'strategy', {'class': 'hudson.tasks.LogRotator'})
    for name, value in [('daysToKeep', '-1'), ('numToKeep', '10'), ('artifactDaysToKeep', '-1'), ('artifactNumToKeep', '5')]:
        ET.SubElement(strategy, name).text = value
    concurrent = ET.SubElement(properties, 'org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty')
    ET.SubElement(concurrent, 'abortPrevious').text = 'false'
    definition = ET.SubElement(root, 'definition', {'class': 'org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition'})
    ET.SubElement(definition, 'script').text = template.replace('@KIND@', 'h5lite').replace(
        '@REPO@', 'https://github.com/agentabatiuo572-byte/nexion-frontend-uniapp.git')
    ET.SubElement(definition, 'sandbox').text = 'true'
    ET.SubElement(root, 'triggers')
    ET.SubElement(root, 'disabled').text = 'false'
    return ET.tostring(root, encoding='utf-8', xml_declaration=True)


def update(stage, lock_pin, resource_pin):
    import fcntl
    require(os.geteuid() == 0 and sys.flags.isolated, 'ISOLATED_ROOT_REQUIRED')
    trusted(Path(__file__).absolute())
    require(stage.parent == STAGES and stage.resolve() == stage, 'STAGE_PATH_REJECTED')
    trusted(stage, directory=True)
    require({path.name for path in stage.iterdir()} == CHANGED | {LOCK_NAME, 'compute-v2-resource-control.py'},
            'STAGE_INVENTORY_REJECTED')
    trusted(INSTALL / 'runtime-lock.json')
    old_raw = (INSTALL / 'runtime-lock.json').read_bytes()
    old = closure(INSTALL, old_raw, OLD_LOCK_SHA)
    trusted(stage / LOCK_NAME)
    new_raw = (stage / LOCK_NAME).read_bytes()
    require(re.fullmatch('[0-9a-f]{64}', lock_pin) and sha(new_raw) == lock_pin, 'NEW_LOCK_PIN_MISMATCH')
    new = json.loads(new_raw)
    require(new.get('version') == 1 and set(new.get('files', {})) == set(old['files'])
            and {name for name in old['files'] if old['files'][name] != new['files'][name]} == CHANGED,
            'UNEXPECTED_CLOSURE_CHANGE')
    sources = {}
    for name in CHANGED:
        trusted(stage / name)
        sources[name] = (stage / name).read_bytes()
        require(sha(sources[name]) == new['files'][name], 'CANDIDATE_PIN_MISMATCH')
        if name.endswith('.py'):
            compile(sources[name], str(stage / name), 'exec')
    trusted(stage / 'compute-v2-resource-control.py')
    resource_raw = (stage / 'compute-v2-resource-control.py').read_bytes()
    require(re.fullmatch('[0-9a-f]{64}', resource_pin) and sha(resource_raw) == resource_pin, 'RESOURCE_CANDIDATE_PIN_MISMATCH')
    compile(resource_raw, str(stage / 'compute-v2-resource-control.py'), 'exec')
    config, state = preflight(old)
    ci = container('nexgrid-ci-agent')
    controller = container('nexgrid-jenkins')
    require(not ci['paused'] and not controller['paused'] and ci['memory'] == ci['swap'] == 10 * 1024**3,
            'CI_10_GIB_REQUIRED')
    for actual, source, destination in [(ci, '/srv/jenkins/install/ci-build.sh', '/opt/nexgrid-ci/ci-build.sh'),
                                       (controller, '/srv/jenkins/install/main.pipeline.groovy', '/opt/nexgrid-ci/main.pipeline.groovy')]:
        require(sum(mount['Source'] == source and mount['Destination'] == destination and not mount['RW']
                    for mount in actual['mounts']) == 1, 'CI_BIND_MOUNT_DRIFT')
    timer = command('systemctl', 'show', '--property=ActiveState', '--value', 'nexgrid-release.timer')
    require(timer in (b'active', b'inactive'), 'TIMER_STATE_REJECTED')
    targets = [*(INSTALL / name for name in sorted(CHANGED)), INSTALL / 'runtime-lock.json',
               INSTALL / 'config.json', ROOT / 'state.json', RESOURCE, *BOUND]
    before = {path: (path.read_bytes(), identity(path)) for path in targets}
    preserved = {path: (path.read_bytes(), identity(path)) for path in
        [NGINX, BUDGET, BUDGET.with_name('retired-ci.json'), CONTROL / 'compute-retirement.json',
         *(path / 'config.xml' for path in JOBS.iterdir() if path.is_dir())]}
    trusted(BACKUPS, directory=True)
    backup = BACKUPS / ('h5lite-test-' + time.strftime('%Y%m%d-%H%M%S', time.gmtime()))
    require(not backup.exists(), 'BACKUP_EXISTS')
    command('systemctl', 'stop', 'nexgrid-release.timer')
    safe, paused, stopped = True, False, False
    with ExitStack() as stack:
        try:
            for path in (ROOT / 'lock', CONTROL / 'control.lock'):
                trusted(path)
                stream = stack.enter_context(path.open('r+b'))
                fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            require(preflight(old) == (config, state) and closure(INSTALL, old_raw, OLD_LOCK_SHA) == old
                    and all((path.read_bytes(), identity(path)) == value for path, value in before.items()), 'PREFLIGHT_DRIFT')
            safe = True  # No source/state writes yet; an idle-check failure can resume CI.
            paused = True
            command('docker', 'pause', ci['id'])
            require(container('nexgrid-ci-agent') == {**ci, 'paused': True}, 'CI_IDENTITY_DRIFT')
            idle(config)
            stopped = True
            command('docker', 'stop', '--time', '30', controller['id'])
            idle(config)
            backup.mkdir(mode=0o700)
            metadata = {'old_lock_sha256': OLD_LOCK_SHA, 'new_lock_sha256': lock_pin,
                        'old_resource_sha256': OLD_RESOURCE_SHA, 'new_resource_sha256': resource_pin,
                        'timer_was_active': timer == b'active', 'files': {}}
            for number, (path, (raw, info)) in enumerate({**before, **preserved}.items()):
                name = f'{number:02d}-{path.name}'
                with (backup / name).open('xb') as stream:
                    stream.write(raw)
                    stream.flush()
                    os.fchmod(stream.fileno(), 0o600)
                    os.fsync(stream.fileno())
                require((backup / name).read_bytes() == raw, 'BACKUP_VERIFY_FAILED')
                metadata['files'][str(path)] = {'backup': name, 'sha256': sha(raw), 'identity': info,
                                              'automatic_restore': path in before}
            with (backup / 'backup-manifest.json').open('xb') as stream:
                stream.write(encoded(metadata))
                stream.flush()
                os.fchmod(stream.fileno(), 0o600)
                os.fsync(stream.fileno())
            sync(backup)
            sync(backup.parent)
            print('H5LITE_BACKUP_READY ' + str(backup), flush=True)
            safe = False
            try:
                for name, raw in sources.items():
                    replace_bound(INSTALL / name, raw, before[INSTALL / name][1])
                for path, name in BOUND.items():
                    replace_bound(path, sources[name], before[path][1])
                replace_bound(RESOURCE, resource_raw, before[RESOURCE][1])
                xml = job_xml(sources['main.pipeline.groovy'].decode())
                LITE.mkdir(mode=0o755)
                LITE.chmod(0o755)
                os.chown(LITE, 1000, 1000)
                (LITE / 'builds').mkdir(mode=0o755)
                (LITE / 'builds').chmod(0o755)
                os.chown(LITE / 'builds', 1000, 1000)
                with (LITE / 'config.xml').open('xb') as stream:
                    stream.write(xml)
                    stream.flush()
                    os.fchmod(stream.fileno(), 0o644)
                    os.fchown(stream.fileno(), 1000, 1000)
                    os.fsync(stream.fileno())
                sync(LITE)
                sync(JOBS)
                (ROOT / 'h5lite').mkdir(mode=0o755)
                (ROOT / 'h5lite').chmod(0o755)
                sync(ROOT)
                config['job_hashes']['h5lite'] = sha(xml)
                for name in CHANGED & config['trusted_files'].keys():
                    config['trusted_files'][name] = new['files'][name]
                state['h5lite'] = ABSENT_LITE
                replace_bound(INSTALL / 'config.json', encoded(config), before[INSTALL / 'config.json'][1])
                replace_bound(ROOT / 'state.json', encoded(state), before[ROOT / 'state.json'][1])
                replace_bound(INSTALL / 'runtime-lock.json', new_raw, before[INSTALL / 'runtime-lock.json'][1])
                closure(INSTALL, new_raw, lock_pin)
                idle(config, lite=True)
                require(all((path.read_bytes(), identity(path)) == value for path, value in preserved.items()),
                        'PRESERVED_STATE_DRIFT')
                safe = True
                print('H5LITE_TEST_INSTALLED ' + lock_pin, flush=True)
            except BaseException:
                for path, (raw, info) in before.items():
                    replace_bound(path, raw, info)
                if LITE.exists():
                    require(not LITE.is_symlink()
                            and (not (LITE / 'builds').exists() or not any((LITE / 'builds').iterdir()))
                            and {path.name for path in LITE.iterdir()} <= {'builds', 'config.xml'}, 'NEW_LITE_JOB_NOT_EMPTY')
                    shutil.rmtree(LITE)
                if (ROOT / 'h5lite').exists():
                    (ROOT / 'h5lite').rmdir()
                closure(INSTALL, old_raw, OLD_LOCK_SHA)
                require(all((path.read_bytes(), identity(path)) == value for path, value in before.items())
                        and all((path.read_bytes(), identity(path)) == value for path, value in preserved.items()), 'ROLLBACK_DRIFT')
                safe = True
                print('H5LITE_TEST_RESTORED ' + str(backup), flush=True)
                raise
        finally:
            if safe:
                try:
                    if stopped:
                        command('docker', 'start', controller['id'])
                        require(container('nexgrid-jenkins')['id'] == controller['id'], 'CONTROLLER_IDENTITY_DRIFT')
                    if paused:
                        current_ci = container('nexgrid-ci-agent')
                        require({**current_ci, 'paused': False} == ci, 'CI_IDENTITY_DRIFT')
                        if current_ci['paused']:
                            command('docker', 'unpause', ci['id'])
                        require(container('nexgrid-ci-agent') == ci, 'CI_IDENTITY_DRIFT')
                    if timer == b'active':
                        command('systemctl', 'start', 'nexgrid-release.timer')
                except BaseException:
                    command('systemctl', 'stop', 'nexgrid-release.timer')
                    print('OPERATOR_RECOVERY_REQUIRED_CI_OR_TIMER_UNCERTAIN ' + str(backup), flush=True)
                    raise
            else:
                print('OPERATOR_RECOVERY_REQUIRED_TIMER_HELD ' + str(backup), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', type=Path)
    parser.add_argument('lock_sha256')
    parser.add_argument('resource_sha256')
    args = parser.parse_args()
    try:
        update(args.stage, args.lock_sha256, args.resource_sha256)
    except Exception as error:
        reason = str(error)
        print(reason if re.fullmatch('[A-Z0-9_]+', reason) else type(error).__name__, flush=True)
        raise SystemExit(1)

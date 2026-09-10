#!/usr/bin/env python3
"""Host-owned release broker. Never run from Jenkins or execute repository scripts as root.

Controller build metadata authorizes one of three fixed artifacts. Build code is untrusted;
it gets no host socket, credentials or deployment API. Installation/activation is manual.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys
import subprocess
import tarfile
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path('/srv/nexgrid/cd')
INSTALL = Path('/srv/jenkins/release')
JOBS = Path('/var/lib/docker/volumes/nexgrid_jenkins_home/_data/jobs')
NGINX = Path('/etc/nginx/sites-available/nexgrid-public')
DROPIN = Path('/etc/systemd/system/nexgrid-backend.service.d/50-public-test-release.conf')
ARTIFACTS = {'backend': 'backend.jar', 'pc': 'pc-build.tgz', 'uniapp': 'uniapp-h5-build.tgz'}
REPOS = {'backend': 'nexion-backend', 'pc': 'nexion-frontend-pc', 'uniapp': 'nexion-frontend-uniapp'}
MAX_EXPANDED = 1024 * 1024 * 1024
MAX_ARCHIVE = 300 * 1024 * 1024
SHA = re.compile(r'[0-9a-f]{40}')
HASH = re.compile(r'[0-9a-f]{64}')


class Rejected(RuntimeError):
    pass


class ComponentFailed(Rejected):
    """This artifact failed, but the previous deployment is unchanged/restored."""


def failure_reason(error):
    return str(error) if isinstance(error, Rejected) else type(error).__name__


def require(condition, message):
    if not condition:
        raise Rejected(message)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def verify_digest(path, expected):
    require(HASH.fullmatch(expected) and digest(path) == expected, 'ARTIFACT_DIGEST_REJECTED')


def safe_read(path, limit):
    # Reject symlinks throughout the path, including controller-writable ancestors.
    for part in (path, *path.parents):
        require(not part.is_symlink(), 'SOURCE_SYMLINK_REJECTED')
    require(path.is_file() and path.stat().st_size <= limit, 'SOURCE_SIZE_REJECTED')
    with path.open('rb') as stream:
        data = stream.read(limit + 1)
    require(len(data) <= limit, 'SOURCE_SIZE_REJECTED')
    return data


def safe_extract(source, destination):
    """Inspect the complete manifest first; no extractall, links, devices or special modes."""
    with tarfile.open(source) as archive:
        members = archive.getmembers()
        require(len(members) <= 60000, 'ARCHIVE_ENTRY_LIMIT')
        seen, total, checked = set(), 0, []
        for member in members:
            path = PurePosixPath(member.name)
            if str(path) == '.' and member.isdir():
                continue
            require(not path.is_absolute() and '..' not in path.parts and '\\' not in member.name
                    and not any(p.startswith('.env') for p in path.parts), 'ARCHIVE_PATH_REJECTED')
            require(str(path) not in seen and (member.isfile() or member.isdir()), 'ARCHIVE_TYPE_REJECTED')
            seen.add(str(path))
            total += member.size
            require(0 <= member.size <= MAX_ARCHIVE and total <= MAX_EXPANDED, 'ARCHIVE_SIZE_REJECTED')
            checked.append((member, path))
        require(not destination.exists(), 'EXTRACTION_DESTINATION_EXISTS')
        destination.mkdir(parents=True, mode=0o755)
        destination.chmod(0o755)
        for member, relative in checked:
            target = destination.joinpath(*relative.parts)
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True, mode=0o755)
                target.chmod(0o755)
            else:
                target.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
                for parent in target.parents:
                    if parent == destination.parent:
                        break
                    parent.chmod(0o755)
                with archive.extractfile(member) as src, target.open('xb') as dst:
                    shutil.copyfileobj(src, dst)
                target.chmod(0o644)


def xml_root(data):
    require(len(data) <= 8 * 1024 * 1024 and b'<!DOCTYPE' not in data.upper()
            and b'<!ENTITY' not in data.upper(), 'XML_REJECTED')
    return ET.fromstring(data)


def build_identity(data):
    root = xml_root(data)
    require(root.tag == 'flow-build' and root.findtext('result') == 'SUCCESS'
            and root.findtext('completed') == 'true', 'BUILD_NOT_SUCCESSFUL')
    # git 5.10 serializes BuildData through marked/sha1 and reference nodes.
    builds = root.findall('./actions/hudson.plugins.git.util.BuildData')
    require(len(builds) == 1, 'SCM_IDENTITY_AMBIGUOUS')
    shas = {e.text for e in builds[0].iter() if e.tag in ('sha1', 'SHA1') and e.text}
    names = {e.text for e in builds[0].iter('name')}
    require(len(shas) == 1 and all(SHA.fullmatch(s or '') for s in shas)
            and names and names <= {'origin/main', 'refs/remotes/origin/main'}, 'SCM_MAIN_REQUIRED')
    return shas.pop()


def validate_manifest(manifest, component, sha):
    require(manifest.get('version') == 1 and manifest.get('component') == component
            and manifest.get('branch') == 'main' and manifest.get('sha') == sha
            and manifest.get('artifact') == ARTIFACTS[component]
            and HASH.fullmatch(manifest.get('sha256', ''))
            and HASH.fullmatch(manifest.get('schema', '')), 'RELEASE_MANIFEST_REJECTED')
    return manifest


def swap_upstream(text, old, new):
    before = f'proxy_pass http://127.0.0.1:{old}'
    require(before in text, 'NGINX_UPSTREAM_DRIFT')
    return re.sub(re.escape(before) + r'(?=[/;])', f'proxy_pass http://127.0.0.1:{new}', text)


def transaction(apply, verify, rollback, verify_old, after_rollback=lambda: None):
    try:
        apply()
        verify()
    except BaseException:
        rollback()
        verify_old()
        after_rollback()
        raise


def run(*args, timeout=90):
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout,
                            env={**os.environ, 'GIT_CONFIG_GLOBAL': '/dev/null', 'GIT_CONFIG_NOSYSTEM': '1',
                                 'GIT_TERMINAL_PROMPT': '0'})
    # Do not echo arbitrary service logs, environment or command output on failure.
    require(result.returncode == 0, 'COMMAND_FAILED: ' + args[0])
    return result.stdout.strip()


def atomic_write(path, data, mode=0o600):
    temporary = path.with_name(path.name + '.new')
    require(not temporary.is_symlink(), 'WRITE_SYMLINK_REJECTED')
    with temporary.open('wb') as stream:
        stream.write(data if isinstance(data, bytes) else data.encode())
        stream.flush()
        os.fsync(stream.fileno())
    temporary.chmod(mode)
    temporary.replace(path)
    sync_directory(path.parent)


def sync_directory(path):
    if os.name != 'nt':
        descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


def save(path, data):
    atomic_write(path, json.dumps(data, sort_keys=True, indent=2) + '\n')


def link(path, target):
    temporary = path.with_name(path.name + '.new')
    if temporary.is_symlink():
        temporary.unlink()  # Exact broker-owned unfinished atomic-link temporary.
    require(not temporary.exists() and not temporary.is_symlink(), 'LINK_TRANSACTION_EXISTS')
    temporary.symlink_to(target, target_is_directory=True)
    temporary.replace(path)
    sync_directory(path.parent)


def fetch(url, expected=200):
    request = urllib.request.Request(url, headers={'X-Nexion-Edge-Country': 'VN'})
    # Health probes never use a machine-wide outbound proxy for local traffic.
    class LocalRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            from urllib.parse import urlsplit
            require(urlsplit(req.full_url).netloc == urlsplit(newurl).netloc
                    and urlsplit(newurl).scheme == 'http', 'HEALTH_REDIRECT_REJECTED')
            return super().redirect_request(req, fp, code, msg, headers, newurl)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), LocalRedirect())
    try:
        with opener.open(request, timeout=8) as response:
            require(response.status == expected, 'HTTP_HEALTH_STATUS')
            return response.read(2 * 1024 * 1024)
    except urllib.error.HTTPError as error:
        if error.code == expected:
            return error.read(1024)
        raise Rejected('HTTP_HEALTH_FAILED') from None


def health(component, port, attempts=30):
    for attempt in range(attempts):
        try:
            base = f'http://127.0.0.1:{port}'
            if component == 'backend':
                data = json.loads(fetch(base + '/api/config/platform'))
                require(data.get('code') == 0 and data.get('data') is not None, 'BACKEND_CONFIG_NOT_READY')
                fetch(base + '/api/admin/auth/session', 401)
            else:
                body = fetch(base + '/').decode()
                require('<html' in body.lower(), 'FRONTEND_HTML_MISSING')
                assets = re.findall(r'(?:src|href)=["\']([^"\']+\.(?:js|css)(?:\?[^"\']*)?)["\']', body)
                require(assets, 'FRONTEND_ASSETS_MISSING')
                for asset in assets[:10]:
                    require(asset.startswith('/'), 'FRONTEND_ASSET_ORIGIN_REJECTED')
                    if component == 'uniapp' and asset.startswith('/app/'):
                        asset = asset[4:]
                    fetch(base + asset)
            return
        except (Rejected, OSError, ValueError):
            if attempt == attempts - 1:
                raise Rejected('HEALTH_FAILED: ' + component) from None
            time.sleep(2)


def restore(journal):
    component = journal['component']
    if component == 'backend':
        if journal['old_dropin'] is None:
            DROPIN.unlink(missing_ok=True)
        else:
            atomic_write(DROPIN, journal['old_dropin'], 0o644)
        link(ROOT / 'backend' / 'current', journal['old_target'])
        run('systemctl', 'daemon-reload')
        run('systemctl', 'restart', 'nexgrid-backend', timeout=90)
        health('backend', 8110)
    else:
        old_name = journal['old_state'][component]['container']
        if run('docker', 'inspect', old_name, '--format', '{{.State.Running}}') != 'true':
            run('docker', 'start', old_name)
        health(component, journal['old_port'])
        atomic_write(NGINX, journal['old_nginx'], 0o644)
        run('nginx', '-t')
        run('systemctl', 'reload', 'nginx')
        health(component, journal['old_port'])
        # Only the exact failed candidate created by this transaction is stopped.
        stop_candidate(journal['candidate'])


def stop_candidate(name):
    require(re.fullmatch(r'nexgrid-cd-(pc|uniapp)-[0-9]+(?:-rollback)?', name), 'CANDIDATE_NAME_REJECTED')
    existing = run('docker', 'ps', '-a', '--format', '{{.Names}}').splitlines()
    if name in existing:
        run('docker', 'stop', '--time', '15', name)


def finish_commit(journal):
    # HEALTHY is durable before state is advanced. Repeating this operation is safe.
    save(ROOT / 'state.json', journal['new_state'])
    component = journal['component']
    if component != 'backend':
        old_name = journal['old_state'][component]['container']
        existing = run('docker', 'ps', '-a', '--format', '{{.Names}}').splitlines()
        if old_name in existing:
            run('docker', 'stop', '--time', '20', old_name)
    (ROOT / 'transaction.json').unlink(missing_ok=True)
    sync_directory(ROOT)


def recover_transaction(journal):
    phase = journal['phase']
    require(phase in ('STAGING', 'APPLYING', 'HEALTHY'), 'UNKNOWN_TRANSACTION_PHASE')
    component = journal['component']
    if phase == 'HEALTHY':
        try:
            health(component, journal['new_state'][component]['port'])
        except Rejected:
            restore(journal)
            save(ROOT / 'state.json', journal['old_state'])
            save(ROOT / 'HALTED.json', {'reason': 'COMMITTED_CANDIDATE_UNHEALTHY_RESTORED', **journal})
            return
        else:
            finish_commit(journal)
            return
    if phase == 'APPLYING':
        restore(journal)
    elif component != 'backend':
        stop_candidate(journal['candidate'])
    save(ROOT / 'state.json', journal['old_state'])
    save(ROOT / 'HALTED.json', {'reason': 'INTERRUPTED_RELEASE_RESTORED', **journal})


def trusted_root_path(path, directory=False):
    for part in (path, *path.parents):
        info = part.lstat()
        require(not stat.S_ISLNK(info.st_mode) and info.st_uid == 0
                and info.st_mode & 0o022 == 0, 'HOST_TRUST_ROOT_REJECTED')
    require(path.is_dir() if directory else path.is_file(), 'HOST_TRUST_TYPE_REJECTED')


def backend_dropin():
    policy = {}
    for line in (INSTALL / 'public-test-policy.properties').read_text().splitlines():
        if line and not line.startswith('#'):
            key, value = line.split('=', 1)
            require(re.fullmatch(r'[a-z0-9.-]+', key) and re.fullmatch(r'[A-Za-z0-9.-]+', value), 'POLICY_SYNTAX')
            policy[key] = value
    require(len(policy) >= 20, 'POLICY_INCOMPLETE')
    args = ['--spring.profiles.active=dev', '--nexion.deployment.public-test=true',
            '--spring.config.additional-location=file:/srv/jenkins/release/test-server.yml']
    args += [f'--{key}={value}' for key, value in sorted(policy.items())]
    return ('[Service]\nWorkingDirectory=/srv/nexgrid/cd/backend/current\nExecStart=\n'
            'ExecStart=/usr/bin/java -Xms1g -Xmx4g -XX:+ExitOnOutOfMemoryError '
            '-jar /srv/nexgrid/cd/backend/current/nexion-backend.jar ' + ' '.join(args) + '\n')


def frontend_run(component, candidate, port, release, config):
    args = ['docker', 'run', '-d', '--name', candidate, '--label', 'nexgrid.managed=release-broker-v1',
            '--restart', 'unless-stopped', '--network', 'host', '--read-only', '--cap-drop', 'ALL',
            '--security-opt', 'no-new-privileges:true', '--pids-limit', '256',
            '--log-opt', 'max-size=10m', '--log-opt', 'max-file=3',
            '--tmpfs', '/tmp:rw,nosuid,nodev,size=128m,mode=1777']
    if component == 'pc':
        require((release / 'server.js').is_file() and (release / '.next/static').is_dir(), 'PC_LAYOUT_REJECTED')
        args += ['--user', '1000:1000', '--memory', '2g', '--memory-swap', '2g', '--cpus', '2',
                 '--mount', f'type=bind,src={release},dst=/opt/app,readonly', '--workdir', '/opt/app',
                 '-e', 'NODE_ENV=production', '-e', 'NEXT_TELEMETRY_DISABLED=1',
                 '-e', 'NEXION_BACKEND_URL=http://127.0.0.1:8110', '-e', 'HOSTNAME=127.0.0.1',
                 '-e', f'PORT={port}', '--entrypoint', 'node', config['pc_image'], '/opt/app/server.js']
    else:
        require((release / 'index.html').is_file(), 'H5_LAYOUT_REJECTED')
        nginx = (INSTALL / 'h5-nginx.conf').read_text().replace('@PORT@', str(port))
        conf = release.parent / 'nginx.conf'
        atomic_write(conf, nginx, 0o644)
        args += ['--user', '101:101', '--memory', '256m', '--memory-swap', '256m', '--cpus', '1',
                 '--mount', f'type=bind,src={release},dst=/usr/share/nginx/html,readonly',
                 '--mount', f'type=bind,src={conf},dst=/etc/nginx/nexgrid.conf,readonly',
                 '--entrypoint', 'nginx', config['h5_image'], '-c', '/etc/nginx/nexgrid.conf', '-g', 'daemon off;']
    run(*args)


def clear_restored_transaction(journal):
    save(ROOT / 'state.json', journal['old_state'])
    (ROOT / 'transaction.json').unlink()
    sync_directory(ROOT)


def discard_staging(journal):
    """No cutover occurred. Stop only this candidate; never restart a live service."""
    require(journal['phase'] == 'STAGING', 'STAGING_PHASE_REQUIRED')
    require(json.loads((ROOT / 'state.json').read_text()) == journal['old_state'], 'STAGING_STATE_DRIFT')
    require(digest(NGINX) == journal['old_state']['nginx_sha256'], 'STAGING_NGINX_DRIFT')
    if journal['component'] == 'backend':
        require(str((ROOT / 'backend/current').resolve()) == journal['old_target'], 'STAGING_BACKEND_DRIFT')
        require((DROPIN.read_text() if DROPIN.exists() else None) == journal['old_dropin'], 'STAGING_DROPIN_DRIFT')
    else:
        require(journal['candidate'] != journal['old_state'][journal['component']]['container'], 'LIVE_CANDIDATE_REJECTED')
        stop_candidate(journal['candidate'])
    clear_restored_transaction(journal)


def stage_release(component, build, destination, manifest, config, journal, port):
    destination.mkdir(mode=0o755)
    destination.chmod(0o755)
    payload = destination / manifest['artifact']
    atomic_write(payload, safe_read(build / 'archive/artifacts' / manifest['artifact'], MAX_ARCHIVE), 0o644)
    verify_digest(payload, manifest['sha256'])
    release = destination / 'app'
    if component == 'backend':
        with zipfile.ZipFile(payload) as jar:
            policy = jar.read('BOOT-INF/classes/public-test-policy.properties')
            require(hashlib.sha256(policy).hexdigest() == config['policy_sha256'], 'JAR_POLICY_REJECTED')
            require('BOOT-INF/classes/ffdd/opsconsole/PublicTestDeploymentSafety.class' in jar.namelist(), 'JAR_GUARD_MISSING')
        release.mkdir(mode=0o755)
        release.chmod(0o755)
        shutil.copyfile(payload, release / 'nexion-backend.jar')
        (release / 'nexion-backend.jar').chmod(0o644)
    else:
        safe_extract(payload, release)
        frontend_run(component, journal['candidate'], port, release, config)
        health(component, port)
    return release


def apply_with_rollback(journal, apply, port, old_port):
    restored = False

    def restored_ok():
        nonlocal restored
        clear_restored_transaction(journal)
        restored = True

    try:
        transaction(apply, lambda: health(journal['component'], port), lambda: restore(journal),
                    lambda: health(journal['component'], old_port), restored_ok)
    except BaseException as error:
        if restored:
            raise ComponentFailed('ROLLED_BACK: ' + failure_reason(error)) from None
        save(ROOT / 'HALTED.json', {'reason': 'ROLLBACK_INCOMPLETE_OPERATOR_REQUIRED', **journal})
        raise


def promote(component, number, config, state, rollback_check=False):
    require(component in ARTIFACTS and str(number).isdigit(), 'COMPONENT_REJECTED')
    require(not rollback_check or not (ROOT / 'AUTO_ENABLED').exists(), 'ROLLBACK_CHECK_REQUIRES_AUTO_HELD')
    job = JOBS / f'nexgrid-{component}-main'
    require(digest(job / 'config.xml') == config['job_hashes'][component], 'JOB_CONFIGURATION_DRIFT')
    build = job / 'builds' / str(number)
    metadata = safe_read(build / 'build.xml', 8 * 1024 * 1024)
    sha = build_identity(metadata)
    manifest = validate_manifest(json.loads(safe_read(build / 'archive/artifacts/release.json', 4096)), component, sha)
    url = f'https://github.com/agentabatiuo572-byte/{REPOS[component]}.git'
    remote = run('git', 'ls-remote', url, 'refs/heads/main', timeout=30).split()
    require(len(remote) == 2 and remote == [sha, 'refs/heads/main'], 'BUILD_IS_NOT_CURRENT_MAIN')
    # GitHub main is the user's approved business source. Mapper/startup/source
    # changes do not require a second fingerprint approval. No SQL is run here.
    suffix = '-rollback' if rollback_check else ''
    destination = ROOT / component / f'{number}-{sha[:12]}{suffix}'
    require(not destination.exists(), 'RELEASE_ALREADY_STAGED')
    require(shutil.disk_usage(ROOT).free > 12 * 1024**3, 'RELEASE_DISK_LOW')
    current = state[component]
    journal = {'component': component, 'build': number, 'sha': sha, 'phase': 'STAGING',
               'staged_path': str(destination), 'old_state': json.loads(json.dumps(state))}
    if component == 'backend':
        journal.update(old_target=str((ROOT / 'backend/current').resolve()),
                       old_dropin=DROPIN.read_text() if DROPIN.exists() else None)
        port = 8110
    else:
        ports = [3003, 3004] if component == 'pc' else [8082, 8083]
        port = next(p for p in ports if p != current['port'])
        candidate = f'nexgrid-cd-{component}-{number}{suffix}'
        old_nginx = NGINX.read_text()
        require(hashlib.sha256(old_nginx.encode()).hexdigest() == state['nginx_sha256'], 'NGINX_CONFIGURATION_DRIFT')
        changed = swap_upstream(old_nginx, current['port'], port)
        journal.update(old_nginx=old_nginx, old_port=current['port'], candidate=candidate)
    save(ROOT / 'transaction.json', journal)
    try:
        release = stage_release(component, build, destination, manifest, config, journal, port)
    except BaseException as error:
        try:
            discard_staging(journal)
        except BaseException:
            save(ROOT / 'HALTED.json', {'reason': 'STAGING_CLEANUP_INCOMPLETE', **journal})
            raise
        raise ComponentFailed('STAGING_FAILED: ' + failure_reason(error)) from None
    if component == 'backend':
        def apply():
            link(ROOT / 'backend/current', release)
            atomic_write(DROPIN, backend_dropin(), 0o644)
            run('systemctl', 'daemon-reload')
            run('systemctl', 'restart', 'nexgrid-backend', timeout=90)
    else:
        def apply():
            atomic_write(NGINX, changed, 0o644)
            run('nginx', '-t')
            run('systemctl', 'reload', 'nginx')
    journal['phase'] = 'APPLYING'
    save(ROOT / 'transaction.json', journal)
    if rollback_check:
        require(not (ROOT / 'AUTO_ENABLED').exists(), 'ROLLBACK_CHECK_REQUIRES_AUTO_HELD')
        # Exercise the real cutover and actual restore functions. A durable
        # APPLYING journal ensures a crash restores the old release, too.
        try:
            apply()
            health(component, port)
        finally:
            restore(journal)
        health(component, current['port'])
        save(ROOT / 'state.json', journal['old_state'])
        proof_path = ROOT / 'rollback-verified.json'
        proof = json.loads(proof_path.read_text()) if proof_path.exists() else {}
        proof[component] = {'sha': sha, 'build': number, 'verified_at': int(time.time()),
                            'old_state': journal['old_state'][component]}
        save(proof_path, proof)
        (ROOT / 'transaction.json').unlink()
        sync_directory(ROOT)
        print(json.dumps({'event': 'ROLLBACK_VERIFIED', 'component': component, 'sha': sha}), flush=True)
        return
    apply_with_rollback(journal, apply, port, current['port'])
    new_state = {**current, 'build': number, 'sha': sha, 'port': port, 'release': str(release)}
    if component != 'backend':
        new_state['container'] = candidate
    state[component] = new_state
    state['nginx_sha256'] = digest(NGINX)
    journal.update(phase='HEALTHY', new_state=state)
    save(ROOT / 'transaction.json', journal)
    # Preserve the exact previous config for operator recovery; no secrets here.
    atomic_write(destination / 'promotion.json', json.dumps(journal, sort_keys=True))
    finish_commit(journal)
    print(json.dumps({'event': 'DEPLOYED', 'component': component, 'build': number, 'sha': sha}), flush=True)


def poll(config, state):
    failure_path = ROOT / 'component-failures.json'
    failures = json.loads(failure_path.read_text()) if failure_path.exists() else {}
    for component in ARTIFACTS:
        latest = None
        try:
            builds = JOBS / f'nexgrid-{component}-main/builds'
            numbers = sorted((int(p.name) for p in builds.iterdir() if p.name.isdigit()), reverse=True)
            if not numbers or numbers[0] <= state[component]['build']:
                continue
            latest = numbers[0]
            previous = failures.get(component, {})
            if previous.get('build') == latest and previous.get('retryable') is False:
                continue  # A failed artifact needs a new build, not repeated cutovers.
            try:
                build_identity(safe_read(builds / str(latest) / 'build.xml', 8 * 1024 * 1024))
            except (Rejected, FileNotFoundError):
                continue  # queued/running/failed/unstable cannot replace the live release
            promote(component, latest, config, state)
        except Exception as error:
            failures[component] = {'build': latest, 'reason': failure_reason(error),
                                   'retryable': not isinstance(error, ComponentFailed), 'at': int(time.time())}
            save(failure_path, failures)
            print(json.dumps({'event': 'COMPONENT_RELEASE_FAILED', 'component': component,
                              **failures[component]}), flush=True)
            # Shared ingress/state is safe only after a completed cleanup/rollback.
            # Never swallow interrupted commits or failed recovery just to continue.
            require(not (ROOT / 'HALTED.json').exists() and not (ROOT / 'transaction.json').exists(),
                    'RELEASE_RECOVERY_REQUIRED')
            state = json.loads((ROOT / 'state.json').read_text())
            continue
        if component in failures:
            del failures[component]
            save(failure_path, failures)


def main():
    require(getattr(sys, '_nexgrid_verified_entry', False), 'TRUSTED_ENTRY_REQUIRED')
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['poll', 'deploy', 'rollback-check'])
    parser.add_argument('component', nargs='?', choices=list(ARTIFACTS))
    parser.add_argument('build', nargs='?', type=int)
    args = parser.parse_args()
    require(os.geteuid() == 0, 'HOST_OPERATOR_REQUIRED')
    trusted_root_path(INSTALL, directory=True)
    trusted_root_path(Path(__file__).absolute())
    trusted_root_path(INSTALL / 'config.json')
    trusted_root_path(ROOT, directory=True)
    trusted_root_path(ROOT / 'state.json')
    if (ROOT / 'lock').exists():
        trusted_root_path(ROOT / 'lock')
    import fcntl
    with (ROOT / 'lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        config = json.loads((INSTALL / 'config.json').read_text())
        state = json.loads((ROOT / 'state.json').read_text())
        require(not (ROOT / 'HALTED.json').exists(), 'RELEASE_HALTED_OPERATOR_REQUIRED')
        for name, expected in config['trusted_files'].items():
            trusted_root_path(INSTALL / name)
            verify_digest(INSTALL / name, expected)
        for name, expected in config['trusted_external_files'].items():
            trusted_root_path(Path(name))
            verify_digest(Path(name), expected)
        if (ROOT / 'transaction.json').exists():
            trusted_root_path(ROOT / 'transaction.json')
            recover_transaction(json.loads((ROOT / 'transaction.json').read_text()))
            require(not (ROOT / 'HALTED.json').exists(), 'INTERRUPTED_RELEASE_OPERATOR_REQUIRED')
            state = json.loads((ROOT / 'state.json').read_text())
        if args.action in ('deploy', 'rollback-check'):
            require(args.component and args.build, 'BUILD_ARGUMENT_REQUIRED')
            promote(args.component, args.build, config, state, rollback_check=args.action == 'rollback-check')
            return
        require((ROOT / 'AUTO_ENABLED').is_file(), 'AUTO_DEPLOYMENT_HELD')
        poll(config, state)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('RELEASE_REJECTED: ' + (str(error) if isinstance(error, Rejected) else type(error).__name__), flush=True)
        raise SystemExit(1)

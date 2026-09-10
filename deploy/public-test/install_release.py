#!/usr/bin/env python3
"""Operator-only installer; execute only through the externally pinned entry."""

# Deliberately block all operator actions, before importing local executable code.
# This is a workflow HOLD, not a replacement for an external trusted bootstrap.
import sys
if __name__ == '__main__' and not getattr(sys, '_nexgrid_verified_entry', False):
    raise SystemExit('TRUSTED_ENTRY_REQUIRED')

import argparse
import json
import os
from pathlib import Path
import shutil
import time
import release_broker as b

LEGACY = Path('/srv/jenkins/install')
HOME = Path('/var/lib/docker/volumes/nexgrid_jenkins_home/_data')
BACKUP = Path('/srv/jenkins/backups/release-v1-ci')
PC_IMAGE = 'sha256:59497c536f8033cdf8aed7e3773ebcd7eb2136a4841b7b97ef8db37db001705e'
H5_IMAGE = 'sha256:5616878291a2eed594aee8db4dade5878cf7edcb475e59193904b198d9b830de'
POLICY_HASH = '6ed34f3ea7e78ea8de6211e62de9d3ebda09243cd29f0a65d0d2284c48209f5e'
SCHEMA_HASH = 'ddd45d764398ce296d4da821f8568d1a3150e70bcb8ff10af95b18840e465733'


def mkdir(path, mode):
    path.mkdir(parents=True, exist_ok=True, mode=mode)
    path.chmod(mode)
    os.chown(path, 0, 0)


def idle_jobs():
    for kind in b.ARTIFACTS:
        job = b.JOBS / f'nexgrid-{kind}-main'
        numbers = [int(p.name) for p in (job / 'builds').iterdir() if p.name.isdigit()]
        if numbers:
            root = b.xml_root(b.safe_read(job / 'builds' / str(max(numbers)) / 'build.xml', 8*1024*1024))
            b.require(root.findtext('completed') == 'true', 'WAIT_FOR_CURRENT_BUILDS')
        b.require('DEPLOYMENT_HELD' in (job / 'config.xml').read_text(), 'EXPECTED_OLD_HOLD_REQUIRED')


def install_ci():
    b.require(not BACKUP.exists(), 'CI_INSTALL_ALREADY_STARTED')
    idle_jobs()
    b.require((HOME / 'queue.xml').exists(), 'JENKINS_QUEUE_FILE_REQUIRED')
    mkdir(BACKUP, 0o700)
    old = ['ci-build.sh', 'main.pipeline.groovy']
    for name in old:
        shutil.copyfile(LEGACY / name, BACKUP / name)
        (BACKUP / name).chmod(0o600)
    for kind in b.ARTIFACTS:
        shutil.copyfile(b.JOBS / f'nexgrid-{kind}-main/config.xml', BACKUP / f'{kind}-job.xml')
        (BACKUP / f'{kind}-job.xml').chmod(0o600)
    print('CI_BACKUP_READY; stopping only idle Jenkins controller and agent', flush=True)
    b.run('docker', 'stop', '--time', '30', 'nexgrid-ci-agent', 'nexgrid-jenkins', timeout=100)
    hook = HOME / 'init.groovy.d/40-release-jobs.groovy'
    try:
        for name in old:
            b.atomic_write(LEGACY / name, (b.INSTALL / name).read_bytes(), 0o644)
        b.require(not hook.exists(), 'UNEXPECTED_EXISTING_RELEASE_HOOK')
        b.atomic_write(hook, (b.INSTALL / hook.name).read_bytes(), 0o644)
        recreate_ci()
        verify_ci_hook()
    except BaseException:
        # Restore only the files this installation owns; never restore auth/credentials.
        b.run('docker', 'stop', '--time', '30', 'nexgrid-ci-agent', 'nexgrid-jenkins', timeout=100)
        for name in old:
            b.atomic_write(LEGACY / name, (BACKUP / name).read_bytes(), 0o644)
        for kind in b.ARTIFACTS:
            target = b.JOBS / f'nexgrid-{kind}-main/config.xml'
            b.atomic_write(target, (BACKUP / f'{kind}-job.xml').read_bytes(), 0o644)
            os.chown(target, 1000, 1000)
        (HOME / 'nexgrid-release-v1-jobs-configured').unlink(missing_ok=True)
        if hook.exists():
            hook.rename(hook.with_suffix('.groovy.failed'))
        recreate_ci()
        raise
    print('CI_RESTARTED; verify the release hook marker and all three builds before host setup', flush=True)


def recreate_ci():
    # Individual bind-mounted files were atomically replaced: recreate the two
    # idle CI containers so mounts resolve the new inode. Retain both volumes,
    # credentials, controller authentication and the existing egress container.
    b.run('docker', 'compose', '-f', str(LEGACY / 'compose.json'),
          '-f', str(LEGACY / 'ci.compose.json'), 'up', '-d', '--no-deps',
          '--no-build', '--force-recreate', 'jenkins', 'agent', timeout=180)


def verify_ci_hook():
    for attempt in range(60):
        if (HOME / 'nexgrid-release-v1-jobs-configured').is_file():
            for kind in b.ARTIFACTS:
                text = (b.JOBS / f'nexgrid-{kind}-main/config.xml').read_text()
                b.require('RELEASE_ARTIFACT_READY' in text and 'DEPLOYMENT_HELD' not in text,
                          'PARTIAL_CI_JOB_CONFIGURATION')
            return
        time.sleep(2)
    raise b.Rejected('CI_CONFIGURATION_TIMEOUT')


def prepare_host():
    b.require(not (b.ROOT / 'state.json').exists(), 'HOST_RELEASE_STATE_ALREADY_EXISTS')
    b.require((HOME / 'nexgrid-release-v1-jobs-configured').is_file(), 'RELEASE_JOBS_NOT_READY')
    b.require(Path('/srv/nexgrid/current').resolve() == Path('/srv/nexgrid/releases/20260908-v4'), 'BUSINESS_RELEASE_DRIFT')
    b.require(not b.DROPIN.exists(), 'BACKEND_DROPIN_ALREADY_EXISTS')
    b.require(b.run('docker', 'inspect', 'nexgrid-pc', '--format', '{{.Image}}') == PC_IMAGE, 'PC_IMAGE_DRIFT')
    b.require(b.run('docker', 'inspect', 'nexgrid-uniapp', '--format', '{{.Image}}') == H5_IMAGE, 'H5_IMAGE_DRIFT')
    b.verify_digest(b.INSTALL / 'public-test-policy.properties', POLICY_HASH)
    for kind, port in [('backend', 8110), ('pc', 3002), ('uniapp', 8081)]:
        b.health(kind, port, attempts=1)
    # Verify the actual ingress destroys caller-supplied forwarding metadata.
    snippet = Path('/etc/nginx/snippets/nexgrid-proxy.conf')
    text = snippet.read_text()
    b.require('proxy_set_header X-Forwarded-For $remote_addr;' in text
              and 'proxy_set_header Forwarded "";' in text
              and '$proxy_add_x_forwarded_for' not in text, 'INGRESS_FORWARDING_TRUST_REQUIRED')
    mkdir(b.ROOT, 0o755)
    for kind in b.ARTIFACTS:
        mkdir(b.ROOT / kind, 0o755)
    b.link(b.ROOT / 'backend/current', '/srv/nexgrid/releases/20260908-v4/backend')
    # Defense in depth for endpoints deliberately absent from public TEST beans.
    original = b.NGINX.read_bytes()
    b.atomic_write(b.ROOT / 'nginx-before-test-boundary.conf', original)
    marker = '    include /srv/jenkins/install/jenkins-location.conf;'
    nginx = original.decode()
    b.require(nginx.count(marker) == 1, 'NGINX_BOUNDARY_INSERTION_DRIFT')
    deny = ('\n    location ^~ /auth/users/oauth/development/ { return 404; }'
            '\n    location ^~ /api/admin/finance/withdrawals/development/ { return 404; }')
    try:
        b.atomic_write(b.NGINX, nginx.replace(marker, marker+deny), 0o644)
        b.run('nginx', '-t')
        b.run('systemctl', 'reload', 'nginx')
    except BaseException:
        b.atomic_write(b.NGINX, original, 0o644)
        b.run('nginx', '-t')
        b.run('systemctl', 'reload', 'nginx')
        raise
    config = {'pc_image': PC_IMAGE, 'h5_image': H5_IMAGE, 'policy_sha256': POLICY_HASH,
              'schema': {'backend': SCHEMA_HASH},
              'job_hashes': {kind: b.digest(b.JOBS / f'nexgrid-{kind}-main/config.xml') for kind in b.ARTIFACTS},
              'trusted_files': {name: b.digest(b.INSTALL / name) for name in
                ['release_broker.py', 'schema_fingerprint.py', 'public-test-policy.properties', 'test-server.yml', 'h5-nginx.conf']},
              'trusted_external_files': {str(snippet): b.digest(snippet)}}
    b.save(b.INSTALL / 'config.json', config)
    state = {'nginx_sha256': b.digest(b.NGINX),
             'backend': {'build': 0, 'sha': 'legacy-v4', 'port': 8110},
             'pc': {'build': 0, 'sha': 'legacy-v4', 'port': 3002, 'container': 'nexgrid-pc'},
             'uniapp': {'build': 0, 'sha': 'legacy-v4', 'port': 8081, 'container': 'nexgrid-uniapp'}}
    b.save(b.ROOT / 'state.json', state)
    for name in ['nexgrid-release.service', 'nexgrid-release.timer']:
        b.atomic_write(Path('/etc/systemd/system') / name, (b.INSTALL / name).read_bytes(), 0o644)
    b.run('systemctl', 'daemon-reload')
    hook = HOME / 'init.groovy.d/40-release-jobs.groovy'
    if hook.exists():
        hook.rename(hook.with_suffix('.groovy.done'))
    print('HOST_BROKER_READY; AUTO remains disabled; perform explicit first promotions and rollback verification', flush=True)


def enable():
    state = json.loads((b.ROOT / 'state.json').read_text())
    b.require(not (b.ROOT / 'HALTED.json').exists() and not (b.ROOT / 'transaction.json').exists(), 'RELEASE_NOT_CLEAN')
    b.require((b.ROOT / 'rollback-verified.json').is_file(), 'ROLLBACK_VERIFICATION_REQUIRED')
    proof = json.loads((b.ROOT / 'rollback-verified.json').read_text())
    for kind in b.ARTIFACTS:
        b.require(state[kind]['build'] > 0, 'FIRST_PROMOTION_REQUIRED')
        b.require(proof.get(kind, {}).get('sha') == state[kind]['sha'], 'ROLLBACK_SHA_NOT_VERIFIED')
        b.health(kind, state[kind]['port'], attempts=2)
    b.atomic_write(b.ROOT / 'AUTO_ENABLED', 'main TEST promotion enabled after operator verification\n')
    b.run('systemctl', 'enable', '--now', 'nexgrid-release.timer')
    print('MAIN_AUTO_DEPLOYMENT_ENABLED', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['ci', 'host', 'enable'])
    args = parser.parse_args()
    b.require(os.geteuid() == 0 and Path(__file__).parent.resolve() == b.INSTALL, 'VERIFIED_ROOT_INSTALL_REQUIRED')
    b.trusted_root_path(b.INSTALL, directory=True)
    {'ci': install_ci, 'host': prepare_host, 'enable': enable}[args.action]()

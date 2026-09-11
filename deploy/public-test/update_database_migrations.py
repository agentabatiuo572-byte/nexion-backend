#!/usr/bin/env python3
"""One-time, externally SHA-pinned operator upgrade; never invoked by CI."""
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

INSTALL = Path('/srv/jenkins/release')
ROOT = Path('/srv/nexgrid/cd')
STAGES = Path('/srv/jenkins/updates')
BACKUPS = Path('/srv/jenkins/backups')
OLD_LOCK = '9c1f0f22ddc83916236d449fe855ae0cc662382fffb84bc77c40966f6116f2ed'
CHANGED = {'trusted_entry.py', 'release_broker.py'}
ADDED = {'database_migrations.py'}
BASELINE = '98bc9eb07be41d0e7342eeaf58ef110054314ee3'
REPAIR = Path('/srv/nexgrid/backups/registration-migration-20260911-024539/receipt.json')
GUARD = Path('/etc/systemd/system/nexgrid-backend.service.d/60-database-migration-hold.conf')
GUARD_TEXT = ('[Unit]\nConditionPathExists=|!/srv/nexgrid/cd/migrations/START_BLOCKED\n'
              'ConditionPathExists=|/run/nexgrid-backend-migration-start\n')


def require(ok, reason):
    if not ok:
        raise RuntimeError(reason)


def trusted(path):
    for item in (path, *path.parents):
        info = item.lstat()
        require(not stat.S_ISLNK(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022,
                'UNTRUSTED_PATH')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def command(*args):
    p = subprocess.run(args, capture_output=True, timeout=90)
    require(p.returncode == 0, 'UPDATE_COMMAND_FAILED_' + Path(args[0]).name.upper())
    return p.stdout.strip()


def closure(directory, pin, count):
    trusted(directory / 'runtime-lock.json')
    raw = (directory / 'runtime-lock.json').read_bytes()
    require(digest(raw) == pin, 'LOCK_PIN_MISMATCH')
    manifest = json.loads(raw)
    require(manifest.get('version') == 1 and len(manifest['files']) == count, 'CLOSURE_REJECTED')
    for name, value in manifest['files'].items():
        require(re.fullmatch(r'[a-zA-Z0-9_.-]+', name) and re.fullmatch(r'[0-9a-f]{64}', value), 'MANIFEST_REJECTED')
        trusted(directory / name)
        require((directory / name).is_file() and digest((directory / name).read_bytes()) == value, 'FILE_PIN_MISMATCH')
    return manifest


def validate_change(old, new):
    require(set(new['files']) == set(old['files']) | ADDED, 'CLOSURE_CHANGE_REJECTED')
    require({name for name in old['files'] if old['files'][name] != new['files'][name]} == CHANGED,
            'UNEXPECTED_CHANGE')


def sync(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def atomic(path, data, mode=0o644):
    tmp = path.with_name(path.name + '.migration-update-new')
    require(not tmp.exists() and not tmp.is_symlink(), 'UPDATE_TEMP_EXISTS')
    with tmp.open('xb') as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    tmp.chmod(mode)
    tmp.replace(path)
    sync(path.parent)


def update(stage, pin):
    import fcntl
    require(os.geteuid() == 0 and sys.flags.isolated, 'ISOLATED_ROOT_REQUIRED')
    require(stage.parent == STAGES and stage.resolve() == stage, 'STAGE_REJECTED')
    new = closure(stage, pin, 13)
    old = closure(INSTALL, OLD_LOCK, 12)
    validate_change(old, new)
    trusted(INSTALL / 'config.json')
    trusted(ROOT / 'lock')
    trusted(GUARD.parent)
    require(not GUARD.exists() and not GUARD.is_symlink(), 'START_GUARD_ALREADY_EXISTS')
    config = json.loads((INSTALL / 'config.json').read_bytes())
    for name, expected in config['trusted_files'].items():
        require(name in old['files'] and old['files'][name] == expected, 'CONFIG_PIN_DRIFT')
    # CI scripts, jobs, environment, services and deployment state are not changed.
    require(command('systemctl', 'is-active', 'nexgrid-release.timer') == b'active', 'TIMER_NOT_ACTIVE')
    trusted(BACKUPS)
    backup = BACKUPS / ('auto-migrations-' + time.strftime('%Y%m%d-%H%M%S', time.gmtime()))
    command('systemctl', 'stop', 'nexgrid-release.timer')
    safe = True
    try:
        with (ROOT / 'lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            require(not any((ROOT / n).exists() for n in ('transaction.json', 'HALTED.json')), 'RELEASE_BUSY')
            closure(INSTALL, OLD_LOCK, 12)
            require(not any((INSTALL / name).exists() for name in ADDED), 'NEW_RUNTIME_FILE_EXISTS')
            backup.mkdir(mode=0o700)
            for name in [*old['files'], 'runtime-lock.json', 'config.json']:
                shutil.copyfile(INSTALL / name, backup / name)
                (backup / name).chmod(0o600)
                with (backup / name).open('r+b') as stream:
                    os.fsync(stream.fileno())
            sync(backup)
            print('RUNTIME_BACKUP_READY ' + str(backup), flush=True)
            # Compile only the externally verified source bytes, while holding
            # the same release lock. No unverified repository imports or pycache.
            scope = {'__name__': 'verified_operator_migrations'}
            exec(compile((stage / 'database_migrations.py').read_bytes(), 'verified_migrations', 'exec'), scope)
            scope['baseline'](BASELINE, REPAIR)
            safe = False
            try:
                atomic(GUARD, GUARD_TEXT.encode())
                command('systemctl', 'daemon-reload')
                for name in sorted(CHANGED | ADDED):
                    atomic(INSTALL / name, (stage / name).read_bytes())
                for name in CHANGED | ADDED:
                    config['trusted_files'][name] = new['files'][name]
                atomic(INSTALL / 'config.json', (json.dumps(config, indent=2, sort_keys=True) + '\n').encode(), 0o600)
                atomic(INSTALL / 'runtime-lock.json', (stage / 'runtime-lock.json').read_bytes())
                closure(INSTALL, pin, 13)
                command('/usr/bin/python3', '-I', str(INSTALL / 'trusted_entry.py'), 'broker', '--help')
                safe = True
                print('AUTO_MIGRATIONS_INSTALLED ' + pin, flush=True)
            except BaseException:
                for name in [*sorted(CHANGED), 'config.json', 'runtime-lock.json']:
                    atomic(INSTALL / name, (backup / name).read_bytes(), 0o600 if name == 'config.json' else 0o644)
                for name in ADDED:
                    # Exact files created by this updater; no user data removed.
                    path = INSTALL / name
                    if path.exists():
                        trusted(path)
                        require(digest(path.read_bytes()) == new['files'][name], 'ROLLBACK_NEW_FILE_DRIFT')
                        path.unlink()
                if GUARD.exists():
                    trusted(GUARD)
                    require(GUARD.read_bytes() == GUARD_TEXT.encode(), 'ROLLBACK_GUARD_DRIFT')
                    GUARD.unlink()
                    sync(GUARD.parent)
                    command('systemctl', 'daemon-reload')
                sync(INSTALL)
                closure(INSTALL, OLD_LOCK, 12)
                safe = True
                print('RUNTIME_RESTORED_BASELINE_RETAINED ' + str(backup), flush=True)
                raise
    finally:
        if safe:
            command('systemctl', 'start', 'nexgrid-release.timer')
        else:
            print('OPERATOR_RECOVERY_REQUIRED_TIMER_STOPPED ' + str(backup), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('stage', type=Path)
    parser.add_argument('lock_sha256')
    args = parser.parse_args()
    try:
        require(re.fullmatch(r'[0-9a-f]{64}', args.lock_sha256), 'LOCK_ARGUMENT_REJECTED')
        update(args.stage, args.lock_sha256)
    except Exception as error:
        message = str(error)
        print(message if re.fullmatch(r'[A-Z_]+', message) else type(error).__name__, flush=True)
        raise SystemExit(1)

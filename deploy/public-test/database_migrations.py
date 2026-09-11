"""Versioned SQL from the exact approved main commit, never repository shell code.

The host broker owns the release lock. Existing history is baselined explicitly,
not replayed. New forward migrations run once, after a verified full backup with
the backend stopped. MySQL DDL is NOT transactional: uncertain failures stay held.
"""
import argparse
import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import shutil
import stat
import subprocess
import tarfile
import time
import urllib.request

ROOT = Path('/srv/nexgrid/cd/migrations')
BACKUPS = Path('/srv/nexgrid/backups/auto-migrations')
ENV_FILE = Path('/srv/nexgrid/secrets/backend.env')
DB = 'nexion'
CONTAINER = 'nexgrid-mysql'
GUARD = Path('/etc/systemd/system/nexgrid-backend.service.d/60-database-migration-hold.conf')
PERMIT = Path('/run/nexgrid-backend-migration-start')
GUARD_TEXT = ('[Unit]\nConditionPathExists=|!/srv/nexgrid/cd/migrations/START_BLOCKED\n'
              'ConditionPathExists=|/run/nexgrid-backend-migration-start\n')
NAME = re.compile(r'[0-9]{8}_[a-z0-9_]+\.sql')
SHA = re.compile(r'[0-9a-f]{40}')
MAX_ARCHIVE = 100 * 1024**2
MAX_SQL = 8 * 1024**2


class MigrationError(RuntimeError):
    pass


def require(condition, reason):
    if not condition:
        raise MigrationError(reason)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def trusted(path):
    for item in (path, *path.parents):
        st = item.lstat()
        require(not stat.S_ISLNK(st.st_mode) and st.st_uid == 0 and not st.st_mode & 0o022,
                'MIGRATION_PATH_UNTRUSTED')


def sync(directory):
    if os.name != 'nt':
        fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)


def save(path, value):
    temp = path.with_name(path.name + '.new')
    require(not temp.is_symlink(), 'MIGRATION_TEMP_UNTRUSTED')
    with temp.open('wb') as stream:
        stream.write((json.dumps(value, indent=2, sort_keys=True) + '\n').encode())
        stream.flush()
        os.fsync(stream.fileno())
    temp.chmod(0o600)
    temp.replace(path)
    sync(path.parent)


def remove_journal():
    (ROOT / 'active.json').unlink()
    sync(ROOT)


def catalog_from_archive(raw, sha):
    require(SHA.fullmatch(sha) and len(raw) <= MAX_ARCHIVE, 'MIGRATION_ARCHIVE_REJECTED')
    scripts, total, count = {}, 0, 0
    prefix = 'nexion-backend-' + sha
    with tarfile.open(fileobj=io.BytesIO(raw), mode='r:gz') as archive:
        for member in archive:
            count += 1
            require(count <= 50000, 'MIGRATION_ARCHIVE_ENTRY_LIMIT')
            path = PurePosixPath(member.name)
            require(not path.is_absolute() and '..' not in path.parts and '\\' not in member.name,
                    'MIGRATION_ARCHIVE_PATH_REJECTED')
            if len(path.parts) != 4 or path.parts[:3] != (prefix, 'scripts', 'migrations'):
                continue
            name = path.name
            if not name.endswith('.sql'):
                continue
            require(NAME.fullmatch(name) and not any(x in name for x in ('rollback', '_down_', '_reset_')),
                    'MIGRATION_FILENAME_REJECTED')
            require(member.isfile() and not member.issym() and name not in scripts
                    and 0 < member.size <= MAX_SQL, 'MIGRATION_ENTRY_REJECTED')
            total += member.size
            require(total <= 32 * 1024**2, 'MIGRATION_SQL_SIZE_LIMIT')
            scripts[name] = archive.extractfile(member).read(MAX_SQL + 1)
    require(scripts, 'MIGRATION_CATALOG_EMPTY')
    return dict(sorted(scripts.items()))


def download_catalog(sha):
    require(SHA.fullmatch(sha), 'MIGRATION_COMMIT_REJECTED')
    url = 'https://codeload.github.com/agentabatiuo572-byte/nexion-backend/tar.gz/' + sha
    with urllib.request.urlopen(url, timeout=45) as response:
        require(response.status == 200 and response.url == url, 'MIGRATION_DOWNLOAD_REJECTED')
        raw = response.read(MAX_ARCHIVE + 1)
    return catalog_from_archive(raw, sha)


def pending_scripts(state, scripts):
    require(state.get('version') == 1 and state.get('database') == DB
            and isinstance(state.get('scripts'), dict), 'MIGRATION_STATE_REJECTED')
    for name, previous in state['scripts'].items():
        require(name in scripts, 'MIGRATION_HISTORY_REMOVED: ' + name)
        require(previous['sha256'] == digest(scripts[name]),
                'MIGRATION_HISTORY_CHANGED_ADD_NEW_FILE: ' + name)
        require(previous['status'] in ('BASELINE_NOT_REPLAYED', 'APPLIED'), 'MIGRATION_STATUS_REJECTED')
    return [(name, data) for name, data in sorted(scripts.items()) if name not in state['scripts']]


def validate_sql(data):
    text = data.decode('utf-8-sig')
    require('\0' not in text and text.strip(), 'MIGRATION_SQL_ENCODING_REJECTED')
    # Database isolation is enforced by server-side grants, not a SQL regex.
    # The binary, noninteractive mysql client disables source/system/tee commands.
    # Forbid selecting another DB or redefining access even if a future grant drifts.
    require(not re.search(r'(?im)^\s*(?:USE\s|(?:CREATE|DROP|ALTER)\s+DATABASE\b|'
                          r'GRANT\s|REVOKE\s|(?:CREATE|ALTER|DROP)\s+USER\b|SET\s+(?:@@)?GLOBAL\b)', text),
            'MIGRATION_DATABASE_OR_ACCESS_COMMAND_REJECTED')


def command(*args, timeout=90):
    result = subprocess.run(args, capture_output=True, timeout=timeout)
    require(result.returncode == 0, 'MIGRATION_COMMAND_FAILED_' + Path(args[0]).name.upper())
    return result.stdout


def credentials():
    trusted(ENV_FILE)
    info = ENV_FILE.lstat()
    require(stat.S_ISREG(info.st_mode) and not info.st_mode & 0o077, 'MIGRATION_SECRET_PERMISSIONS_REJECTED')
    kv = {}
    for line in ENV_FILE.read_text().splitlines():
        if '=' not in line or line.lstrip().startswith('#'):
            continue
        key, value = line.split('=', 1)
        if key in ('NEXION_DB_URL', 'NEXION_DB_USERNAME', 'NEXION_DB_PASSWORD'):
            kv[key] = shlex.split(value)[0] if value and value[0] in '\"\'' else value
    require(re.fullmatch(r'jdbc:mysql://(?:127\.0\.0\.1|localhost):3306/nexion(?:\?[^\r\n]*)?',
                         kv.get('NEXION_DB_URL', '')), 'MIGRATION_DATABASE_ROUTE_REJECTED')
    require(re.fullmatch(r'[a-zA-Z0-9_]+', kv.get('NEXION_DB_USERNAME', ''))
            and kv['NEXION_DB_USERNAME'] != 'root' and kv.get('NEXION_DB_PASSWORD'),
            'MIGRATION_APP_CREDENTIAL_REJECTED')
    container = json.loads(command('docker', 'inspect', CONTAINER))[0]
    root = dict(x.split('=', 1) for x in container['Config']['Env'] if '=' in x).get('MYSQL_ROOT_PASSWORD')
    require(root, 'MIGRATION_BACKUP_CREDENTIAL_MISSING')
    return kv['NEXION_DB_USERNAME'], kv['NEXION_DB_PASSWORD'], root


def mysql_args(user):
    return ['docker', 'exec', '-i', '-e', 'MYSQL_PWD', CONTAINER, 'mysql', '--user=' + user,
            '--batch', '--raw', '--skip-column-names', '--binary-mode', '--local-infile=0',
            '--skip-reconnect', '--default-character-set=utf8mb4', DB]


def scoped_account(user, password):
    result = subprocess.run(mysql_args(user), input=b'SHOW GRANTS FOR CURRENT_USER();',
                            capture_output=True, timeout=30, env={**os.environ, 'MYSQL_PWD': password})
    require(result.returncode == 0, 'MIGRATION_GRANT_QUERY_FAILED')
    grants = result.stdout.decode().splitlines()
    require(grants and any(' ON `nexion`.* TO ' in row for row in grants), 'MIGRATION_DB_GRANT_MISSING')
    require(all(re.fullmatch(r'GRANT USAGE ON \*\.\* TO .+', row)
                or (' ON `nexion`.* TO ' in row and 'WITH GRANT OPTION' not in row)
                for row in grants), 'MIGRATION_PRIVILEGES_NOT_DATABASE_SCOPED')


def stop_backend():
    revoke_candidate_start()
    save(ROOT / 'START_BLOCKED', {'reason': 'DATABASE_MIGRATION_HOLD'})
    command('systemctl', 'stop', 'nexgrid-backend', timeout=90)
    require(command('systemctl', 'show', 'nexgrid-backend', '-p', 'ActiveState', '--value').strip()
            == b'inactive', 'MIGRATION_BACKEND_NOT_STOPPED')


def start_previous():
    revoke_candidate_start()
    (ROOT / 'START_BLOCKED').unlink(missing_ok=True)
    sync(ROOT)
    command('systemctl', 'start', 'nexgrid-backend', timeout=90)


def allow_candidate_start():
    journal = active()
    if journal:
        require(journal.get('phase') == 'SQL_APPLIED', 'MIGRATION_CANDIDATE_START_REJECTED')
        trusted(PERMIT.parent)
        save(PERMIT, {'reason': 'ONE_CONTROLLED_FORWARD_START'})
    else:
        require(not (ROOT / 'START_BLOCKED').exists(), 'MIGRATION_ORPHAN_START_HOLD')


def revoke_candidate_start():
    PERMIT.unlink(missing_ok=True)
    sync(PERMIT.parent)


def verify_backup(path):
    total, ending = 0, b''
    with gzip.open(path, 'rb') as stream:
        for chunk in iter(lambda: stream.read(1024**2), b''):
            total += len(chunk)
            ending = (ending + chunk)[-1024:]
    require(total > 10000 and b'Dump completed on' in ending, 'MIGRATION_BACKUP_INVALID')
    with path.open('rb') as stream:
        checksum = hashlib.file_digest(stream, 'sha256').hexdigest()
    return {'path': str(path), 'sha256': checksum, 'uncompressed_bytes': total}


def backup_database(folder, password):
    require(shutil.disk_usage(BACKUPS).free > 12 * 1024**3, 'MIGRATION_BACKUP_DISK_LOW')
    args = ['docker', 'exec', '-e', 'MYSQL_PWD', CONTAINER, 'mysqldump', '--user=root',
            '--single-transaction', '--quick', '--no-tablespaces', '--set-gtid-purged=OFF',
            '--hex-blob', '--routines', '--events', '--triggers', '--default-character-set=utf8mb4', DB]
    # Spool directly to a protected file: no SQL/credentials in Jenkins or journal.
    raw = folder / 'database.sql'
    with raw.open('xb') as out, (folder / 'backup.stderr').open('xb') as error:
        result = subprocess.run(args, stdout=out, stderr=error, timeout=240,
                                env={**os.environ, 'MYSQL_PWD': password})
        out.flush()
        os.fsync(out.fileno())
    require(result.returncode == 0, 'MIGRATION_BACKUP_FAILED_NO_SQL')
    target = folder / 'database.sql.gz'
    with raw.open('rb') as source, target.open('xb') as out:
        with gzip.GzipFile(fileobj=out, mode='wb') as compressed:
            shutil.copyfileobj(source, compressed, 1024**2)
        out.flush()
        os.fsync(out.fileno())
    proof = verify_backup(target)
    raw.unlink()  # Exact newly-created uncompressed copy; verified gzip is retained.
    sync(folder)
    return proof


def execute_sql(name, data, folder, user, password):
    with (folder / (name + '.stdout')).open('xb') as out, (folder / (name + '.stderr')).open('xb') as err:
        result = subprocess.run(mysql_args(user),
                                input=b'SET SESSION lock_wait_timeout=30; SET SESSION innodb_lock_wait_timeout=30;\n' + data,
                                stdout=out, stderr=err, timeout=180,
                                env={**os.environ, 'MYSQL_PWD': password})
    require(result.returncode == 0, 'MIGRATION_SQL_FAILED: ' + name)


def active():
    path = ROOT / 'active.json'
    if not path.exists():
        return None
    trusted(path)
    return json.loads(path.read_text())


def apply(sha, *, rollback_check=False):
    trusted(ROOT)
    trusted(ROOT / 'state.json')
    trusted(GUARD)
    require(GUARD.read_text() == GUARD_TEXT, 'MIGRATION_START_GUARD_CHANGED')
    previous = active()
    require(not previous or previous.get('phase') == 'SQL_APPLIED', 'MIGRATION_PARTIAL_OR_INTERRUPTED_HOLD')
    require(not (previous and rollback_check), 'MIGRATION_NEEDS_FORWARD_DEPLOY_NOT_ROLLBACK_CHECK')
    state = json.loads((ROOT / 'state.json').read_text())
    scripts = download_catalog(sha)
    pending = pending_scripts(state, scripts)
    if not pending:
        return previous
    require(not rollback_check, 'MIGRATION_NEEDS_FORWARD_DEPLOY_NOT_ROLLBACK_CHECK')
    for _, data in pending:
        validate_sql(data)
    user, password, root_password = credentials()
    scoped_account(user, password)
    trusted(BACKUPS)
    folder = BACKUPS / (time.strftime('%Y%m%d-%H%M%S', time.gmtime()) + '-' + sha[:12])
    folder.mkdir(mode=0o700)
    journal = {'version': 1, 'phase': 'PREPARING', 'sha': sha, 'database': DB,
               'folder': str(folder), 'pending': [name for name, _ in pending],
               'completed': [], 'previous_sql_applied': previous}
    save(folder / 'state-before.json', state)
    save(ROOT / 'active.json', journal)
    sql_started = False
    try:
        stop_backend()
        journal['backup'] = backup_database(folder, root_password)
        journal['phase'] = 'BACKUP_VERIFIED'
        save(ROOT / 'active.json', journal)
        save(folder / 'receipt.json', journal)
        print(json.dumps({'event': 'DB_BACKUP_VERIFIED', 'sha': sha, **journal['backup']}), flush=True)
        for name, data in pending:
            with (folder / name).open('xb') as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            journal.update(phase='SQL_APPLYING', current=name)
            save(ROOT / 'active.json', journal)
            sql_started = True
            execute_sql(name, data, folder, user, password)
            state['scripts'][name] = {'sha256': digest(data), 'status': 'APPLIED',
                                      'sha': sha, 'backup': journal['backup']['path']}
            save(ROOT / 'state.json', state)
            journal['completed'].append(name)
            save(ROOT / 'active.json', journal)
            print(json.dumps({'event': 'DB_MIGRATION_APPLIED', 'file': name, 'sha': sha}), flush=True)
        journal['phase'] = 'SQL_APPLIED'
        save(ROOT / 'active.json', journal)
        save(folder / 'receipt.json', journal)
        # Leave the backend stopped. The broker starts the new JAR, not old code
        # whose SQL may be incompatible. Only successful release clears active.
        return journal
    except BaseException as error:
        journal.update(phase='SQL_FAILED' if sql_started else 'BACKUP_FAILED',
                       error=str(error) if isinstance(error, MigrationError) else type(error).__name__)
        save(ROOT / 'active.json', journal)
        save(folder / 'receipt.json', journal)
        if not sql_started and not previous:
            start_previous()
            remove_journal()
        elif not sql_started and previous:
            save(ROOT / 'active.json', previous)
        raise


def complete_release():
    journal = active()
    if journal:
        require(journal.get('phase') == 'SQL_APPLIED', 'MIGRATION_INCOMPLETE_CANNOT_COMMIT')
        journal['phase'] = 'RELEASE_HEALTHY'
        save(Path(journal['folder']) / 'receipt.json', journal)
        (ROOT / 'START_BLOCKED').unlink(missing_ok=True)
        sync(ROOT)
        revoke_candidate_start()
        remove_journal()


def baseline(sha, repair_receipt):
    trusted(ROOT.parent)
    trusted(repair_receipt)
    proof = json.loads(repair_receipt.read_text())
    require(proof.get('phase') == 'APPLIED' and proof.get('sha') == sha, 'MIGRATION_REPAIR_PROOF_REJECTED')
    backup = repair_receipt.parent / 'nexion.sql.gz'
    require(verify_backup(backup)['sha256'] == proof['backup_sha256'], 'MIGRATION_REPAIR_BACKUP_CHANGED')
    scripts = download_catalog(sha)
    repaired = proof['migration']
    require(repaired in scripts and digest(scripts[repaired]) == proof['migration_sha256'],
            'MIGRATION_REPAIR_SCRIPT_CHANGED')
    state = {'version': 1, 'database': DB, 'baseline_sha': sha,
             'baseline_note': 'Historical files recorded without replay; not a claim all historical SQL ran.',
             'scripts': {name: {'sha256': digest(data), 'status': 'BASELINE_NOT_REPLAYED'}
                         for name, data in scripts.items()}}
    state['scripts'][repaired].update(status='APPLIED', sha=sha, backup=str(backup))
    if ROOT.exists():
        trusted(ROOT / 'state.json')
        require(json.loads((ROOT / 'state.json').read_text()) == state
                and not (ROOT / 'active.json').exists() and not (ROOT / 'START_BLOCKED').exists(),
                'MIGRATION_EXISTING_BASELINE_DIFFERS')
        print('MIGRATION_BASELINE_ALREADY_VERIFIED', flush=True)
        return
    ROOT.mkdir(mode=0o700)
    save(ROOT / 'state.json', state)
    BACKUPS.mkdir(mode=0o700, exist_ok=True)
    trusted(BACKUPS)
    print('MIGRATION_BASELINE_READY ' + str(len(scripts)), flush=True)


def main():
    require(os.geteuid() == 0 and getattr(__import__('sys'), '_nexgrid_verified_entry', False),
            'MIGRATION_TRUSTED_ENTRY_REQUIRED')
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['baseline'])
    parser.add_argument('sha')
    parser.add_argument('repair_receipt', type=Path)
    args = parser.parse_args()
    import fcntl
    with (ROOT.parent / 'lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        require(not (ROOT.parent / 'transaction.json').exists(), 'MIGRATION_RELEASE_BUSY')
        baseline(args.sha, args.repair_receipt)

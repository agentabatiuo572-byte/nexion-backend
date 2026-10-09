#!/usr/bin/env python3
"""One incident only: prepare ordinary backend240 promotion; never execute SQL writes.

Root uses an independently approved SHA with Python -I. Arm requires explicit
fixed-trigger permission. Any interruption remains stopped behind both holds.
Original SQL/history/backup are immutable; SQL runs only in the normal broker.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import types

CD = Path('/srv/nexgrid/cd')
ROOT = CD / 'migrations'
INSTALL = Path('/srv/jenkins/release')
FOLDER = Path('/srv/nexgrid/backups/auto-migrations/20261009-214203-7efbe92a339e')
ATTEMPT = ROOT / 'earnings1419-20261009-214203'
STAGED = CD / 'backend/240-7efbe92a339e'
OLD_TARGET = CD / 'backend/239-2aabbc9fce55/app'
RUNNER_STAGE = Path('/srv/jenkins/updates/earnings1419-20261009-r119/database_migrations.py')
NGINX = Path('/etc/nginx/sites-available/nexgrid-public')
DROPIN = Path('/etc/systemd/system/nexgrid-backend.service.d/50-public-test-release.conf')
PERMIT = Path('/run/nexgrid-backend-migration-start')
GUARD = Path('/etc/systemd/system/nexgrid-backend.service.d/60-database-migration-hold.conf')
GUARD_BYTES = (b'[Unit]\nConditionPathExists=|!/srv/nexgrid/cd/migrations/START_BLOCKED\n'
               b'ConditionPathExists=|/run/nexgrid-backend-migration-start\n')
FAILED = '20261007_earnings_source_recovery.sql'
FIRST = '20261007_e4_wallet_bill_prerequisite.sql'
SOURCE = '7efbe92a339ebb8872a96ee293cae99be8099062'
OLD_SOURCE = '2aabbc9fce55426a2d3e9fb93e7a5eda4423f120'
OLD_RUNNER = 'a9cf7dad5c086dfc53c11c73a683b1f088151f844c5c3a3760c77298b028d64d'
NEW_RUNNER = 'bb75e69f5e09234fc5631ce60799f9026bc19eb5e6ccb8129b5715f9051acf2e'
OLD_LOCK = '3b15ce47b5e9c8adcff2f8375f7724f54b27fa297703a1192966be55b9d57edd'
OLD_CONFIG = 'fe08cc1a550d673869a7253535075f018e0733a130aa39d7575431d39cf83655'
SQL_HASH = 'f443722d71d6a9da77759e55142ddd12e15b71c8af356a5dc9cd73dd03a410e9'
FIRST_HASH = '6b21252550def4328029d014cf3dfd283e10851fcda4f630e88aa64d09546ccd'
ERROR_HASH = '0e96176fdfe3a14b179942eaa987681eb9e2293406d3836706dd4706286eb5b2'
BACKUP_HASH = '12f305b5c75b71ea86a3aefbb4b88f3c092235f4fb2245e12766a92a18720e3b'
BACKUP_EXPANDED = 143009466  # uncompressed bytes, not gzip st_size
CHECK_HASH = '98b6baa521991f617bcf6e8f39ec7b6234ada55b725d235191ff5f447c5e4bab'
JAR_HASH = 'e9c778320de9ce60cb46853c042a7d2dd7d22d4c48d362abd79c25147095392e'
JAR_BYTES = 138971739
FLAG = 'allow_fixed_earnings_trigger_install'
HALT = {'reason': 'EARNINGS_1419_EXACT_INCIDENT_RECOVERY', 'sha': SOURCE}
MUTABLE = ('database_migrations.py', 'config.json', 'runtime-lock.json')
PENDING = [FIRST, FAILED, '20261007_growth_promotions.sql',
           '20261007_growth_promotions_list_snapshot.sql', '20261007_growth_promotions_order_receipt.sql',
           '20261007_growth_promotions_quota_restore.sql', '20261007_voucher_zero_payment.sql',
           '20261008_l6_promotion_reward_routes.sql', '20261009_admin_session_idle60.sql',
           '20261009_e4_wallet_bill_schema_precision_forward.sql',
           '20261009_support_leaderboard_publication.sql', '20261009_support_timeout_segment.sql']


class RecoveryError(RuntimeError):
    pass


def require(ok, reason):
    if not ok:
        raise RecoveryError(reason)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def trusted(path, *, directory=False):
    require(path.is_absolute() and path.resolve() == path, 'UNTRUSTED_PATH')
    for index, part in enumerate((path, *path.parents)):
        info = part.lstat()
        kind = stat.S_ISDIR(info.st_mode) if directory or index else stat.S_ISREG(info.st_mode)
        require(kind and info.st_uid == 0 and not info.st_mode & 0o022, 'UNTRUSTED_PATH')


def read(path, *, private=False):
    trusted(path)
    if private:
        require(not path.stat().st_mode & 0o077, 'PRIVATE_MODE_REQUIRED')
    return path.read_bytes()


def hashed(path):
    trusted(path)
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def absent(path):
    require(not path.exists() and not path.is_symlink(), 'UNEXPECTED_PATH_PRESENT')


def sync(path):
    if os.name != 'nt':
        fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)


def new_file(path, data, mode=0o600):
    absent(path)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'wb') as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    path.chmod(mode)
    sync(path.parent)


def atomic(path, data, mode=0o600, *, rollback=False):
    temporary = path.with_name(path.name + ('.1419-rollback-new' if rollback else '.1419-new'))
    new_file(temporary, data, mode)
    temporary.replace(path)
    sync(path.parent)


def encoded(value):
    return (json.dumps(value, indent=2, sort_keys=True) + '\n').encode()


def command(*args):
    result = subprocess.run(args, capture_output=True, timeout=90)
    require(result.returncode == 0, 'COMMAND_FAILED')
    return result.stdout.strip()


def services():
    for service in ('nexgrid-backend', 'nexgrid-release.service', 'nexgrid-release.timer'):
        require(command('systemctl', 'show', service, '-p', 'ActiveState', '--value') == b'inactive',
                'SERVICE_NOT_INACTIVE')
    absent(CD / 'AUTO_ENABLED')
    absent(CD / 'transaction.json')
    absent(PERMIT)
    require(json.loads(read(ROOT / 'START_BLOCKED', private=True)) == {'reason': 'DATABASE_MIGRATION_HOLD'},
            'START_HOLD_CHANGED')
    require(read(GUARD) == GUARD_BYTES, 'START_GUARD_CHANGED')


def runner():
    require(RUNNER_STAGE.name == 'database_migrations.py', 'RUNNER_PATH_CHANGED')
    raw = read(RUNNER_STAGE)
    require(digest(raw) == NEW_RUNNER, 'CANDIDATE_RUNNER_CHANGED')
    module = types.ModuleType('verified_1419_migrations')
    exec(compile(raw, 'verified_1419_migrations', 'exec'), module.__dict__)
    return module


def closure(*, upgraded=False):
    raw = read(INSTALL / 'runtime-lock.json')
    lock = json.loads(raw)
    require(set(lock) == {'version', 'files'} and lock['version'] == 1 and len(lock['files']) == 13,
            'RUNTIME_CLOSURE_CHANGED')
    expected_runner = NEW_RUNNER if upgraded else OLD_RUNNER
    if not upgraded:
        require(digest(raw) == OLD_LOCK, 'OLD_LOCK_CHANGED')
    require(lock['files']['database_migrations.py'] == expected_runner, 'RUNNER_PIN_CHANGED')
    for name, expected in lock['files'].items():
        require(re.fullmatch(r'[a-zA-Z0-9_.-]+', name) and re.fullmatch(r'[a-f0-9]{64}', expected)
                and hashed(INSTALL / name) == expected, 'RUNTIME_FILE_CHANGED')
    config_raw = read(INSTALL / 'config.json', private=True)
    config = json.loads(config_raw)
    require(isinstance(config.get('trusted_files'), dict), 'CONFIG_REJECTED')
    for name, expected in config['trusted_files'].items():
        require(lock['files'].get(name) == expected, 'CONFIG_PIN_CHANGED')
    for name, expected in config.get('trusted_external_files', {}).items():
        require(hashed(Path(name)) == expected, 'EXTERNAL_PIN_CHANGED')
    if not upgraded:
        require(digest(config_raw) == OLD_CONFIG and FLAG not in config, 'OLD_CONFIG_CHANGED')
    else:
        require(config.get(FLAG) is True, 'FIXED_TRIGGER_APPROVAL_MISSING')
    return lock, config


def prefix_metadata(m):
    user, password, root_password = m.credentials()
    m.scoped_account(user, password)
    context = m.server_context(user, password)
    root_context = m.server_context('root', root_password)
    require(context['version'] == '8.4.11' and context['log_bin'] == 1 and context['trust'] == 0
            and root_context['principal'].startswith('root@')
            and all(root_context[k] == context[k] for k in
                    ('version', 'database', 'log_bin', 'trust', 'mode', 'charset', 'collation')),
            'SERVER_CONTEXT_CHANGED')
    m.account_definer(context['principal'], user)
    columns = m.probe(user, password,
        "SELECT JSON_ARRAYAGG(JSON_OBJECT('table',TABLE_NAME,'name',COLUMN_NAME,'type',COLUMN_TYPE,"
        "'nullable',IS_NULLABLE,'default',COLUMN_DEFAULT)) FROM information_schema.COLUMNS WHERE "
        "TABLE_SCHEMA=DATABASE() AND ((TABLE_NAME='nx_user_wallet' AND COLUMN_NAME IN "
        "('earnings_usdt_debited','earnings_nex_debited')) OR (TABLE_NAME='nx_earnings_release_entry' "
        "AND COLUMN_NAME IN ('source_debit_baseline','source_wallet_id','recovered_amount')));")
    actual = {(c['table'], c['name']): (c['type'], c['nullable'], c['default']) for c in columns}
    expected = {('nx_user_wallet', name): ('decimal(30,6)', 'NO', '0.000000')
                for name in ('earnings_usdt_debited', 'earnings_nex_debited')}
    expected.update({('nx_earnings_release_entry', 'source_debit_baseline'): ('decimal(30,6)', 'YES', None),
                     ('nx_earnings_release_entry', 'source_wallet_id'): ('bigint', 'YES', None),
                     ('nx_earnings_release_entry', 'recovered_amount'): ('decimal(24,6)', 'NO', '0.000000')})
    require(len(columns) == 5 and actual == expected, 'PARTIAL_COLUMNS_CHANGED')
    checks = m.probe(user, password,
        "SELECT JSON_ARRAYAGG(JSON_OBJECT('name',t.CONSTRAINT_NAME,'enforced',t.ENFORCED,'hash',"
        "SHA2(REGEXP_REPLACE(LOWER(c.CHECK_CLAUSE),'[[:space:]]',''),256))) FROM "
        "information_schema.TABLE_CONSTRAINTS t JOIN information_schema.CHECK_CONSTRAINTS c ON "
        "c.CONSTRAINT_SCHEMA=t.CONSTRAINT_SCHEMA AND c.CONSTRAINT_NAME=t.CONSTRAINT_NAME WHERE "
        "t.CONSTRAINT_SCHEMA=DATABASE() AND t.TABLE_NAME='nx_earnings_release_entry' AND "
        "t.CONSTRAINT_NAME='chk_earnings_source_recovery';")
    require(checks == [{'name': 'chk_earnings_source_recovery', 'enforced': 'YES', 'hash': CHECK_HASH}],
            'PARTIAL_CHECK_CHANGED')
    require(m.probe(user, password, m.TRIGGER_SQL) == [], 'WALLET_TRIGGER_CHANGED')
    require(m.probe(user, password, "SELECT COUNT(*) FROM information_schema.TABLES WHERE "
                    "TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_earnings_source_recovery';") == 0,
            'SUFFIX_TABLE_PRESENT')
    for account, secret, scope, needed in (
        (user, password, '`nexion`.*', {'ALTER', 'CREATE', 'REFERENCES', 'TRIGGER', 'SELECT', 'UPDATE'}),
        ('root', root_password, '*.*', {'SUPER', 'TRIGGER'})):
        privileges = m.grant_privileges(m.probe(account, secret, 'SHOW GRANTS FOR CURRENT_USER();',
                                               as_json=False), scope)
        require('ALL PRIVILEGES' in privileges or needed <= privileges, 'CAPABILITY_MISSING')
        if account == 'root':
            require('SET_ANY_DEFINER' in privileges, 'DEFINER_CAPABILITY_MISSING')
    # Credentials/principal/grants stay in memory and are never returned or persisted.


def original(m, *, archived=False):
    active_raw = read(ATTEMPT / 'failed-active.json' if archived else ROOT / 'active.json', private=True)
    active = json.loads(active_raw)
    require(active.get('version') == 1 and active.get('database') == 'nexion'
            and active.get('sha') == SOURCE and active.get('phase') == 'SQL_FAILED'
            and active.get('current') == FAILED and active.get('segment') is None
            and active.get('error') == 'MIGRATION_SQL_FAILED: ' + FAILED
            and active.get('folder') == str(FOLDER) and active.get('pending') == PENDING
            and active.get('completed') == [FIRST] and active.get('previous_sql_applied') is None,
            'FAILED_JOURNAL_CHANGED')
    require(read(FOLDER / 'receipt.json', private=True) == active_raw, 'FAILED_RECEIPT_CHANGED')
    require(hashed(FOLDER / FAILED) == SQL_HASH and hashed(FOLDER / FIRST) == FIRST_HASH
            and hashed(FOLDER / (FAILED + '.stderr')) == ERROR_HASH, 'FAILED_BYTES_CHANGED')
    trusted(FOLDER / 'database.sql.gz')
    require(not (FOLDER / 'database.sql.gz').stat().st_mode & 0o077, 'PRIVATE_MODE_REQUIRED')
    backup = m.verify_backup(FOLDER / 'database.sql.gz')
    require(backup == active.get('backup') and backup['sha256'] == BACKUP_HASH
            and backup['uncompressed_bytes'] == BACKUP_EXPANDED, 'BACKUP_CHANGED')
    before = json.loads(read(FOLDER / 'state-before.json', private=True))
    state_raw = read(ROOT / 'state.json', private=True)
    state = json.loads(state_raw)
    require(before['version'] == state['version'] == 1 and before['database'] == state['database'] == 'nexion'
            and set(state['scripts']) - set(before['scripts']) == {FIRST}
            and all(state['scripts'].get(k) == v for k, v in before['scripts'].items())
            and state['scripts'][FIRST] == {'sha256': FIRST_HASH, 'status': 'APPLIED',
                                          'sha': SOURCE, 'backup': str(FOLDER / 'database.sql.gz')}
            and FAILED not in state['scripts'], 'HISTORY_DELTA_CHANGED')
    return active_raw, state_raw


def release_state():
    raw = read(CD / 'state.json', private=True)
    state = json.loads(raw)
    backend = state.get('backend')
    expected = {'build': 239, 'port': 8110, 'sha': OLD_SOURCE}
    require(isinstance(backend, dict) and all(key in backend and type(backend[key]) is type(value)
            and backend[key] == value for key, value in expected.items()), 'OLD_RELEASE_CHANGED')
    require((CD / 'backend/current').is_symlink()
            and (CD / 'backend/current').resolve() == OLD_TARGET, 'LIVE_TARGET_CHANGED')
    trusted(OLD_TARGET, directory=True)
    require(hashed(NGINX) == state['nginx_sha256'], 'NGINX_CHANGED')
    return raw


def staging(path):
    trusted(path, directory=True)
    require({p.name for p in path.iterdir()} == {'backend.jar', 'app'}, 'STAGING_CONTENT_CHANGED')
    trusted(path / 'app', directory=True)
    require({p.name for p in (path / 'app').iterdir()} == {'nexion-backend.jar'}
            and (path / 'backend.jar').stat().st_size == JAR_BYTES
            and (path / 'app/nexion-backend.jar').stat().st_size == JAR_BYTES
            and hashed(path / 'backend.jar') == hashed(path / 'app/nexion-backend.jar') == JAR_HASH,
            'STAGING_JAR_CHANGED')


def audit():
    services()
    absent(CD / 'HALTED.json')
    absent(ATTEMPT)
    m = runner()
    require(read(m.GUARD) == m.GUARD_TEXT.encode(), 'START_GUARD_CHANGED')
    closure()
    original(m)
    release_state()
    staging(STAGED)
    prefix_metadata(m)
    return m


def phase(value, **fields):
    payload = encoded({'phase': value, 'source': SOURCE,
                       'atUTC': datetime.datetime.now(datetime.timezone.utc).isoformat(), **fields})
    number = len(list(ATTEMPT.glob('phase-*.json'))) + 1
    new_file(ATTEMPT / f'phase-{number:03d}.json', payload)
    atomic(ATTEMPT / 'intent.json', payload)


def arm(enable):
    require(enable is True, 'ACTION_TIME_FIXED_TRIGGER_APPROVAL_REQUIRED')
    m = audit()
    lock, config = closure()
    snapshots = {name: (read(INSTALL / name), stat.S_IMODE((INSTALL / name).stat().st_mode)) for name in MUTABLE}
    active_raw, state_raw = original(m)
    release_raw = release_state()
    ATTEMPT.mkdir(mode=0o700)
    sync(ROOT)
    # Durable broker hold precedes any runtime or journal/staging change.
    new_file(CD / 'HALTED.json', encoded(HALT))
    try:
        phase('ARMING')
        for name, (raw, mode) in snapshots.items():
            new_file(ATTEMPT / (name + '.before'), raw)
        new_file(ATTEMPT / 'failed-active.json', active_raw)
        new_file(ATTEMPT / 'migration-state.json', state_raw)
        new_file(ATTEMPT / 'release-state.json', release_raw)
        new_file(ATTEMPT / 'dropin.before', read(DROPIN))
        preservation = {p.name: digest(p.read_bytes()) for p in ATTEMPT.iterdir()
                        if p.name != 'intent.json' and not p.name.startswith('phase-')}
        preservation['original_receipt'] = digest(read(FOLDER / 'receipt.json', private=True))
        preservation['original_stdout'] = hashed(FOLDER / (FAILED + '.stdout'))
        preservation['original_stderr'] = ERROR_HASH
        preservation['original_backup'] = BACKUP_HASH
        new_file(ATTEMPT / 'preservation.json', encoded({'hashes': preservation,
            'modes': {name: mode for name, (_, mode) in snapshots.items()}}))
        phase('SNAPSHOT_VERIFIED')
        config['trusted_files']['database_migrations.py'] = NEW_RUNNER
        config[FLAG] = True  # Explicit arm argument, never inferred from missing flag.
        lock['files']['database_migrations.py'] = NEW_RUNNER
        atomic(INSTALL / 'database_migrations.py', read(RUNNER_STAGE), snapshots['database_migrations.py'][1])
        atomic(INSTALL / 'config.json', encoded(config), snapshots['config.json'][1])
        atomic(INSTALL / 'runtime-lock.json', encoded(lock), snapshots['runtime-lock.json'][1])
        closure(upgraded=True)
        phase('RUNTIME_VERIFIED')
        require(read(ROOT / 'active.json', private=True) == active_raw
                and read(ROOT / 'state.json', private=True) == state_raw
                and release_state() == release_raw, 'STATE_RACED')
        prefix_metadata(m)
        staging(STAGED)
        STAGED.replace(ATTEMPT / 'staging240')
        sync(STAGED.parent)
        sync(ATTEMPT)
        phase('STAGING_QUARANTINED')
        require(read(ROOT / 'active.json', private=True) == read(ATTEMPT / 'failed-active.json', private=True),
                'ACTIVE_RACED')
        (ROOT / 'active.json').unlink()
        sync(ROOT)
        phase('ARMED')
    except BaseException as error:
        # Never clear either hold, change DB history, restart old JAR or retry SQL.
        try:
            phase('FAILED_HELD', exceptionType=type(error).__name__)
        except BaseException:
            pass
        raise


def preserved():
    info = json.loads(read(ATTEMPT / 'preservation.json', private=True))
    for name, expected in info['hashes'].items():
        if not name.startswith('original_'):
            require(hashed(ATTEMPT / name) == expected, 'SNAPSHOT_CHANGED')
    require(hashed(FOLDER / (FAILED + '.stdout')) == info['hashes']['original_stdout'], 'ORIGINAL_LOG_CHANGED')
    return info


def release():
    services()
    require(json.loads(read(CD / 'HALTED.json', private=True)) == HALT, 'FOREIGN_HALT')
    require(json.loads(read(ATTEMPT / 'intent.json', private=True))['phase'] == 'ARMED', 'NOT_ARMED')
    preserved()
    absent(ROOT / 'active.json')
    absent(STAGED)
    m = runner()
    original(m, archived=True)
    require(read(ROOT / 'state.json', private=True) == read(ATTEMPT / 'migration-state.json', private=True)
            and release_state() == read(ATTEMPT / 'release-state.json', private=True)
            and read(DROPIN) == read(ATTEMPT / 'dropin.before', private=True), 'STATE_DRIFT')
    lock, config = closure(upgraded=True)
    old_lock = json.loads(read(ATTEMPT / 'runtime-lock.json.before', private=True))
    old_config = json.loads(read(ATTEMPT / 'config.json.before', private=True))
    old_lock['files']['database_migrations.py'] = NEW_RUNNER
    old_config['trusted_files']['database_migrations.py'] = NEW_RUNNER
    old_config[FLAG] = True
    require(lock == old_lock and config == old_config, 'UNEXPECTED_RUNTIME_CHANGE')
    staging(ATTEMPT / 'staging240')
    prefix_metadata(m)
    try:
        phase('READY_FOR_ORDINARY_BACKEND240')
        (CD / 'HALTED.json').unlink()  # Only this exact tool-created halt. Startup hold remains.
        sync(CD)
    except BaseException:
        retain_release_hold()
        raise


def retain_release_hold():
    intent = json.loads(read(ATTEMPT / 'intent.json', private=True))
    require(intent['source'] == SOURCE and intent['phase'] in
            ('ARMED', 'READY_FOR_ORDINARY_BACKEND240'), 'NOT_OWN_RELEASE')
    if not (CD / 'HALTED.json').exists() and not (CD / 'HALTED.json').is_symlink():
        new_file(CD / 'HALTED.json', encoded(HALT))
    # An existing foreign hold is never replaced or cleared.


def rollback():
    """Explicit file-only rollback under the hold; never a database/JAR rollback."""
    services()
    require(json.loads(read(CD / 'HALTED.json', private=True)) == HALT, 'FOREIGN_HALT')
    if not (ATTEMPT / 'preservation.json').exists():
        # Snapshot failure happens before any runtime/staging/journal mutation.
        closure()
        original(runner())
        release_state()
        staging(STAGED)
        phase('ROLLED_BACK_HELD')
        return
    info = preserved()
    require(read(ROOT / 'state.json', private=True) == read(ATTEMPT / 'migration-state.json', private=True)
            and release_state() == read(ATTEMPT / 'release-state.json', private=True), 'STATE_DRIFT')
    original(runner(), archived=True)
    originals = {name: read(ATTEMPT / (name + '.before'), private=True) for name in MUTABLE}
    upgraded_config = json.loads(originals['config.json'])
    upgraded_config['trusted_files']['database_migrations.py'] = NEW_RUNNER
    upgraded_config[FLAG] = True
    upgraded_lock = json.loads(originals['runtime-lock.json'])
    upgraded_lock['files']['database_migrations.py'] = NEW_RUNNER
    upgraded = {'database_migrations.py': read(RUNNER_STAGE),
                'config.json': encoded(upgraded_config), 'runtime-lock.json': encoded(upgraded_lock)}
    for name in MUTABLE:
        require(read(INSTALL / name) in (originals[name], upgraded[name])
                and stat.S_IMODE((INSTALL / name).stat().st_mode) == info['modes'][name], 'RUNTIME_DRIFT')
    archived = read(ATTEMPT / 'failed-active.json', private=True)
    if (ROOT / 'active.json').exists():
        require(read(ROOT / 'active.json', private=True) == archived, 'ACTIVE_CHANGED')
    else:
        new_file(ROOT / 'active.json', archived)
    quarantined = ATTEMPT / 'staging240'
    if quarantined.exists():
        absent(STAGED)
        staging(quarantined)
        quarantined.replace(STAGED)
        sync(STAGED.parent)
    staging(STAGED)
    for name in MUTABLE:
        atomic(INSTALL / name, read(ATTEMPT / (name + '.before'), private=True),
               info['modes'][name], rollback=True)
    closure()
    phase('ROLLED_BACK_HELD')
    # Both holds deliberately stay. No automatic old-code restart or second attempt.


def action_receipt(action, exit_code, error=None):
    if not ATTEMPT.exists() or action == 'audit':
        return
    trusted(ATTEMPT, directory=True)
    target = ATTEMPT / (action + '-result.json')
    if target.exists():
        first = json.loads(read(target, private=True))
        require(first['action'] == action and first['nativeExit'] in (0, 1), 'ACTION_RECEIPT_CHANGED')
        exit_code, reason = first['nativeExit'], first['reason']
    else:
        reason = str(error) if isinstance(error, RecoveryError) else type(error).__name__ if error else None
        new_file(target, encoded({'action': action, 'nativeExit': exit_code, 'reason': reason,
                                 'exitScope': 'ACTION_BEFORE_RECEIPT_COMMIT',
                                 'atUTC': datetime.datetime.now(datetime.timezone.utc).isoformat()}))
    # Complete a partially persisted receipt from its immutable first result,
    # without overwriting any original output or turning a first failure green.
    outputs = {'.nativeexit': (str(exit_code) + '\n').encode(),
               '.stdout': ('EARNINGS_1419_' + action.upper() +
                           ('_OK\n' if exit_code == 0 else '_FAILED_HELD\n')).encode(),
               '.stderr': (('' if reason is None else reason + '\n')).encode()}
    for suffix, raw in outputs.items():
        path = ATTEMPT / (action + suffix)
        if path.exists():
            require(read(path, private=True) == raw, 'ACTION_LOG_CHANGED')
        else:
            new_file(path, raw)


def execute_action(action, enable=False):
    try:
        if action == 'audit':
            audit()
        elif action == 'arm':
            arm(enable)
        elif action == 'release':
            release()
        else:
            rollback()
        action_receipt(action, 0)
    except BaseException as error:
        if action == 'release' and ATTEMPT.exists():
            retain_release_hold()
        try:
            if ATTEMPT.exists() and action != 'audit':
                target = ATTEMPT / (action + '-command-failed.json')
                if not target.exists():
                    new_file(target, encoded({'nativeExit': 1, 'exceptionType': type(error).__name__}))
            action_receipt(action, 1, error)
        except BaseException:
            pass  # The durable holds remain even when disk/receipt writes fail.
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('audit', 'arm', 'release', 'rollback'))
    parser.add_argument('--trusted-tool-sha256')
    parser.add_argument('--enable-fixed-earnings-trigger', action='store_true')
    args = parser.parse_args()
    require(os.geteuid() == 0 and sys.flags.isolated, 'ISOLATED_ROOT_REQUIRED')
    trusted(Path(__file__).absolute())
    require(args.trusted_tool_sha256 == hashed(Path(__file__).absolute()), 'INDEPENDENT_TOOL_PIN_REQUIRED')
    import fcntl
    trusted(CD / 'lock')
    with (CD / 'lock').open('r+b') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        execute_action(args.action, args.enable_fixed_earnings_trigger)
    print('EARNINGS_1419_' + args.action.upper() + '_OK')


if __name__ == '__main__':
    try:
        main()
    except BaseException as error:
        print('EARNINGS_1419_HELD ' + (str(error) if isinstance(error, RecoveryError) else type(error).__name__))
        raise SystemExit(1)

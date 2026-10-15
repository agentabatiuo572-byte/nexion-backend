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
HOST_CONFIG = Path('/srv/jenkins/release/config.json')
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
EARNINGS_FILE = '20261007_earnings_source_recovery.sql'
EARNINGS_SHA256 = 'f443722d71d6a9da77759e55142ddd12e15b71c8af356a5dc9cd73dd03a410e9'
EARNINGS_TRIGGER = 'nx_wallet_earnings_debit_counter_v1'
EARNINGS_BODY = ('SET NEW.earnings_usdt_debited=OLD.earnings_usdt_debited+'
                 'GREATEST(OLD.usdt_available-NEW.usdt_available,0),'
                 'NEW.earnings_nex_debited=OLD.earnings_nex_debited+'
                 'GREATEST(OLD.nex_available-NEW.nex_available,0)')
EARNINGS_CREATE = (f'CREATE TRIGGER IF NOT EXISTS {EARNINGS_TRIGGER}\n'
                   'BEFORE UPDATE ON nx_user_wallet FOR EACH ROW\n' + EARNINGS_BODY + ';\n').encode()
CONTEXT_SQL = ("SELECT JSON_OBJECT('version',VERSION(),'database',DATABASE(),"
               "'principal',CURRENT_USER(),'log_bin',@@GLOBAL.log_bin,"
               "'trust',@@GLOBAL.log_bin_trust_function_creators,'mode',@@SESSION.sql_mode,"
               "'charset',@@SESSION.character_set_client,'collation',@@SESSION.collation_connection);")
TRIGGER_SQL = ("SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('name',TRIGGER_NAME,"
               "'table',EVENT_OBJECT_TABLE,'timing',ACTION_TIMING,'event',EVENT_MANIPULATION,"
               "'body',ACTION_STATEMENT,'definer',DEFINER,'mode',SQL_MODE,"
               "'charset',CHARACTER_SET_CLIENT,'collation',COLLATION_CONNECTION)),JSON_ARRAY()) "
               "FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND "
               f"(TRIGGER_NAME='{EARNINGS_TRIGGER}' OR (EVENT_OBJECT_TABLE='nx_user_wallet' "
               "AND ACTION_TIMING='BEFORE' AND EVENT_MANIPULATION='UPDATE'));")


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
                or (user == 'nexion_app' and row == support_show_grant(SUPPORT_EXECUTE_GRANTS[1]))
                for row in grants), 'MIGRATION_PRIVILEGES_NOT_DATABASE_SCOPED')


def probe(user, password, sql, *, as_json=True):
    """Read-only, fixed caller queries; never print MySQL output or credentials."""
    result = subprocess.run(mysql_args(user), input=sql.encode(), capture_output=True,
                            timeout=30, env={**os.environ, 'MYSQL_PWD': password})
    require(result.returncode == 0, 'MIGRATION_CAPABILITY_QUERY_FAILED')
    try:
        text = result.stdout.decode('utf-8').strip()
        return json.loads(text) if as_json else text.splitlines()
    except (UnicodeError, ValueError):
        raise MigrationError('MIGRATION_CAPABILITY_RESPONSE_REJECTED') from None


def fixed_trigger_enabled():
    if not HOST_CONFIG.exists() and not HOST_CONFIG.is_symlink():
        return False
    trusted(HOST_CONFIG)
    require(stat.S_ISREG(HOST_CONFIG.lstat().st_mode), 'MIGRATION_HOST_CONFIG_REJECTED')
    try:
        config = json.loads(HOST_CONFIG.read_bytes())
    except (UnicodeError, ValueError):
        raise MigrationError('MIGRATION_HOST_CONFIG_REJECTED') from None
    require(isinstance(config, dict), 'MIGRATION_HOST_CONFIG_REJECTED')
    value = config.get('allow_fixed_earnings_trigger_install', False)
    require(type(value) is bool, 'MIGRATION_HOST_CONFIG_REJECTED')
    return value  # Only a separately approved root-owned host configuration enables writing.


def account_definer(principal, user):
    require(isinstance(principal, str) and principal.count('@') == 1,
            'MIGRATION_TRIGGER_DEFINER_REJECTED')
    account, host = principal.split('@')
    require(account == user and account != 'root' and re.fullmatch(r'[a-zA-Z0-9_]{1,32}', account)
            and re.fullmatch(r'[a-zA-Z0-9_.%:_-]{1,255}', host),
            'MIGRATION_TRIGGER_DEFINER_REJECTED')
    # Account identifiers are quoted, and quotes/backticks/control characters are rejected.
    return '`' + account + '`@`' + host + '`'


def earnings_parts(data):
    require(digest(data) == EARNINGS_SHA256, 'MIGRATION_FIXED_TRIGGER_FILE_CHANGED')
    require(data.count(EARNINGS_CREATE) == 1, 'MIGRATION_FIXED_TRIGGER_BLOCK_CHANGED')
    prefix, suffix = data.split(EARNINGS_CREATE)
    require(prefix and suffix, 'MIGRATION_FIXED_TRIGGER_BLOCK_CHANGED')
    return prefix, suffix


def grant_privileges(rows, scope):
    require(isinstance(rows, list) and rows, 'MIGRATION_CAPABILITY_GRANTS_REJECTED')
    privileges = set()
    for row in rows:
        require(isinstance(row, str), 'MIGRATION_CAPABILITY_GRANTS_REJECTED')
        match = re.fullmatch(r'GRANT ([A-Z_, ]+) ON ' + re.escape(scope) + r' TO .+', row)
        if match:
            privileges.update(value.strip() for value in match[1].split(','))
    return privileges


def server_context(user, password):
    context = probe(user, password, CONTEXT_SQL)
    require(isinstance(context, dict) and context.get('database') == DB
            and isinstance(context.get('version'), str)
            and type(context.get('log_bin')) is int and context['log_bin'] in (0, 1)
            and type(context.get('trust')) is int and context['trust'] in (0, 1)
            and all(isinstance(context.get(k), str) and context[k]
                    for k in ('principal', 'mode', 'charset', 'collation')),
            'MIGRATION_CAPABILITY_RESPONSE_REJECTED')
    return context


def verify_earnings_trigger(user, password, context, *, required=False):
    rows = probe(user, password, TRIGGER_SQL)
    require(isinstance(rows, list), 'MIGRATION_TRIGGER_METADATA_REJECTED')
    if not rows:
        require(not required, 'MIGRATION_FIXED_TRIGGER_MISSING')
        return False
    require(len(rows) == 1 and isinstance(rows[0], dict), 'MIGRATION_TRIGGER_SHAPE_REJECTED')
    row = rows[0]
    require(row.get('name') == EARNINGS_TRIGGER and row.get('table') == 'nx_user_wallet'
            and row.get('timing') == 'BEFORE' and row.get('event') == 'UPDATE'
            and isinstance(row.get('body'), str)
            and re.sub(r'\s', '', row['body']).lower() == re.sub(r'\s', '', EARNINGS_BODY).lower()
            and row.get('definer') == context['principal']
            and all(row.get(k) == context[k] for k in ('mode', 'charset', 'collation')),
            'MIGRATION_TRIGGER_SHAPE_REJECTED')
    return True


def stored_program_text(text, *, backslash_escapes):
    # Mask quoted tokens and ordinary comments; retain executable comment SQL.
    # Both escaping modes are checked by the caller, independent of sql_mode.
    escape = r'\\[\s\S]|' if backslash_escapes else ''
    quoted = '|'.join(q + '(?:' + escape + q + q + '|[^' + q
                      + (r'\\' if backslash_escapes else '') + '])*' + q
                      for q in ("'", '"', '`'))
    pattern = quoted + r'|--(?=[\x00-\x20\x7f])[^\r\n]*|#[^\r\n]*|/\*.*?\*/'

    def mask(match):
        value = match[0]
        if value.startswith('/*!'):
            executable = re.sub(r'^\d*', '', value[3:-2])
            return ' ' + stored_program_text(executable, backslash_escapes=backslash_escapes) + ' '
        return ' '

    return re.sub(pattern, mask, text, flags=re.S)


def migration_preflight(pending, user, password, root_password):
    """Check the entire pending batch before creating a journal, hold or any DDL."""
    special = False
    for name, data in pending:
        if name == EARNINGS_FILE:
            earnings_parts(data)
            special = True
        else:
            # Conservative refusal, not a SQL sandbox: grants still enforce isolation.
            text = data.decode('utf-8-sig')
            for backslash_escapes in (False, True):
                scanned = stored_program_text(text, backslash_escapes=backslash_escapes)
                require(not re.search(r'\bCREATE\b[^;]*\b(?:TRIGGER|FUNCTION)\b', scanned, re.I),
                        'MIGRATION_UNREVIEWED_STORED_PROGRAM')
    if not special:
        return None
    context = server_context(user, password)
    account_definer(context['principal'], user)
    privileges = grant_privileges(probe(user, password, 'SHOW GRANTS FOR CURRENT_USER();',
                                       as_json=False), '`nexion`.*')
    require('ALL PRIVILEGES' in privileges
            or {'ALTER', 'CREATE', 'REFERENCES', 'TRIGGER', 'SELECT', 'UPDATE'} <= privileges,
            'MIGRATION_TRIGGER_APP_CAPABILITIES_MISSING')
    verify_earnings_trigger(user, password, context)
    root_install = bool(context['log_bin'] and not context['trust'])
    if root_install:
        require(fixed_trigger_enabled(), 'MIGRATION_TRIGGER_PRIVILEGED_INSTALL_NOT_APPROVED_BEFORE_HOLD')
        root_context = server_context('root', root_password)
        require(context['version'].startswith('8.4.') and root_context['version'] == context['version']
                and root_context['principal'].startswith('root@')
                and all(root_context[k] == context[k] for k in ('mode', 'charset', 'collation', 'log_bin', 'trust')),
                'MIGRATION_TRIGGER_ROOT_CONTEXT_REJECTED')
        root_privileges = grant_privileges(probe('root', root_password, 'SHOW GRANTS FOR CURRENT_USER();',
                                               as_json=False), '*.*')
        require(('ALL PRIVILEGES' in root_privileges or {'SUPER', 'TRIGGER'} <= root_privileges)
                and 'SET_ANY_DEFINER' in root_privileges,
                'MIGRATION_TRIGGER_ROOT_CAPABILITIES_MISSING')
    return {**context, 'root_install': root_install}


# Reviewed source bytes and CREATE slices. New SQL needs a separate fixed contract;
# this never permits an arbitrary archive program to run as root.
SUPPORT_PINS = {'20261011_support_fund_journal.sql': 'bd78b16a586887e02c7125918f950e80a99c33577c39b8e2c1ce825182574ec4',
 '20261011_support_fund_operations.sql': '3be7938cff645df2f30e45087f0e1a5444da83242f3c1974be484478e0129044',
 '20261011_support_fund_operation_party.sql': '23de0c28476a826dc33ca863df0da0d540ef1539788e526f86c74167f30b60a2',
 '20261011_support_fund_schema_reader.sql': 'e953d9df7afb43c1683289153dbcae73c5e28df14d196106df2ec663fd0fc425'}
SUPPORT_PROGRAMS = {'20261011_support_fund_journal.sql': (('nx_support_wallet_journal_ai_v1',
                                        'TRIGGER',
                                        17467,
                                        1363,
                                        '41c8d5155e779ae3cc1c533897a77175902baaf8e453f396da4b00dee32d402f'),
                                       ('nx_support_wallet_journal_bu_v1',
                                        'TRIGGER',
                                        20551,
                                        883,
                                        '3c80725716fd770ccd7abbf9edf07465998b815d9c684cbc78910d61a2c75ed9'),
                                       ('nx_support_wallet_journal_au_v1',
                                        'TRIGGER',
                                        22629,
                                        1097,
                                        '4d005fac299666274ad9344bf93c5bddba2eefb51e59aa1075cb889d57223943')),
 '20261011_support_fund_operations.sql': (('nx_support_fund_operation_bu_v1',
                                           'TRIGGER',
                                           15364,
                                           191,
                                           '8f1853de6818af58cfc214699dd59c5d03360724f229c893efefb4ae95b5be75'),
                                          ('nx_support_fund_operation_bd_v1',
                                           'TRIGGER',
                                           16152,
                                           191,
                                           'd3f14dd317c3642544a158cecc9a0317950efad187675fd535daf2719b6742f1'),
                                          ('nx_support_fund_leg_bu_v1',
                                           'TRIGGER',
                                           16940,
                                           189,
                                           '62d62652b2ae64e04c7b9dc32c80e9bfe5c501a5d0cab6d1d08b54b8bde957d0'),
                                          ('nx_support_fund_leg_bd_v1',
                                           'TRIGGER',
                                           17718,
                                           189,
                                           '70ca1afb2b55223ba322b7a5a9cb1db1d34e3042df546f1eb0b105e544378dc3')),
 '20261011_support_fund_operation_party.sql': (('nx_support_fund_party_bu_v1',
                                                'TRIGGER',
                                                5457,
                                                193,
                                                '288d82c357d97f7cde2984902e056a5a253ffa0d055ba4f39795d49cd9c23691'),
                                               ('nx_support_fund_party_bd_v1',
                                                'TRIGGER',
                                                5650,
                                                193,
                                                '926a6e5794edc9a3307f5bf4ced5e85b542214fc81347907ebd0903721403890')),
 '20261011_support_fund_schema_reader.sql': (('nx_support_fund_schema_ready_v1',
                                              'FUNCTION',
                                              35549,
                                              32281,
                                              '7b9f0780c813a3e3e9fa1cf316737f5ac21d93afa5f6c7a9f228aeb3733b37c9'),)}

SUPPORT_APP = 'nexion_app@%'
SUPPORT_SCHEMA = 'nexion_support_schema@localhost'
SUPPORT_GRANTEE = "'nexion_support_schema'@'localhost'"
SUPPORT_TABLES = ('nx_user_wallet', 'nx_support_fund_operation',
                  'nx_support_fund_operation_leg', 'nx_support_fund_operation_party', 'nx_support_wallet_journal')
# REFERENCES exposes column/key metadata; TRIGGER exposes trigger bodies.
# Neither grant lets this locked metadata account select business rows.
SUPPORT_METADATA_GRANTS = tuple('GRANT REFERENCES, TRIGGER ON `nexion`.`' + table + '` TO ' + SUPPORT_GRANTEE + ';\n'
                                for table in SUPPORT_TABLES)
SUPPORT_EXECUTE_GRANTS = (
    'GRANT EXECUTE ON FUNCTION `nexion`.`nx_support_fund_schema_ready_v1` TO ' + SUPPORT_GRANTEE + ';\n',
    "GRANT EXECUTE ON FUNCTION `nexion`.`nx_support_fund_schema_ready_v1` TO 'nexion_app'@'%';\n")

def support_require(condition, reason):
    require(condition, 'MIGRATION_SUPPORT_' + reason)


def support_prepare(pending):
    """Only the four byte-pinned first-install scripts use the fixed program lane."""
    sources = dict(pending)
    if not any(name in SUPPORT_PROGRAMS for name in sources):
        return None
    support_require(set(SUPPORT_PROGRAMS) <= sources.keys(), 'COMPLETE_FIRST_INSTALL_REQUIRED')
    for name in SUPPORT_PROGRAMS:
        validate_sql(sources[name])
        support_segments(name, sources[name])
    # Keep unrelated migrations in their original positions. Fix only the known
    # dependency order, which differs from the alphabetic party/operations names.
    ordered = iter(SUPPORT_PROGRAMS)
    return [(next(ordered) if name in SUPPORT_PROGRAMS else name) for name, _ in pending]


def support_segments(name, raw):
    support_require(name in SUPPORT_PINS and digest(raw) == SUPPORT_PINS[name], 'FIXED_SOURCE_CHANGED')
    result, cursor = [], 0
    for program, kind, start, length, expected in SUPPORT_PROGRAMS.get(name, ()):
        support_require(cursor <= start and start + length <= len(raw), 'PROGRAM_OFFSET_REJECTED')
        if cursor < start:
            result.append({'data': raw[cursor:start], 'program': '', 'kind': ''})
        block = raw[start:start + length]
        support_require(digest(block) == expected, 'PROGRAM_BLOCK_CHANGED')
        result.append({'data': block, 'program': program, 'kind': kind})
        cursor = start + length
    if cursor < len(raw):
        result.append({'data': raw[cursor:], 'program': '', 'kind': ''})
    migration_preflight([(name, part['data']) for part in result if not part['program']], None, None, None)
    return result


def support_template(name, program, raw):
    """Accept exact fixed source only; return one CREATE block, never a whole file."""
    parts = [part for part in support_segments(name, raw) if part['program'] == program]
    support_require(len(parts) == 1, 'PROGRAM_NAME_REJECTED')
    part = parts[0]
    if part['kind'] == 'FUNCTION':
        old = b'CREATE DEFINER=CURRENT_USER FUNCTION IF NOT EXISTS '
        new = b'CREATE DEFINER=`nexion_support_schema`@`localhost` FUNCTION IF NOT EXISTS '
    else:
        old = b'CREATE TRIGGER IF NOT EXISTS '
        new = b'CREATE DEFINER=`nexion_app`@`%` TRIGGER IF NOT EXISTS '
    support_require(part['data'].count(old) == 1, 'PROGRAM_HEADER_REJECTED')
    return part['data'].replace(old, new, 1)


def support_show_grant(sql):
    return sql.strip().rstrip(';').replace("'", '`')


def support_preflight(user, password, root_password):
    """Read actual capabilities and first-install state before the existing hold."""
    app = server_context(user, password)
    root = server_context('root', root_password)
    support_require(user == 'nexion_app' and app['principal'] == SUPPORT_APP
                    and root['principal'] in ('root@localhost', 'root@%')
                    and app['version'] == root['version'] and app['version'].startswith('8.4.')
                    and app['log_bin'] == root['log_bin'] == 1
                    and app['trust'] == root['trust'] == 0
                    and all(app[key] == root[key] for key in ('mode', 'charset', 'collation')),
                    'LIVE_CONTEXT_REJECTED')
    for actor, secret in ((user, password), ('root', root_password)):
        support_require(probe(actor, secret, 'SELECT @@SESSION.binlog_format;', as_json=False) == ['ROW'],
                        'LIVE_BINLOG_FORMAT_REJECTED')
    root_grants = probe('root', root_password, 'SHOW GRANTS FOR CURRENT_USER();', as_json=False)
    privileges = grant_privileges(root_grants, '*.*')
    support_require(('ALL PRIVILEGES' in privileges
                     or {'SUPER', 'TRIGGER', 'REFERENCES', 'CREATE ROUTINE', 'CREATE USER', 'EXECUTE'} <= privileges)
                    and 'SET_ANY_DEFINER' in privileges
                    and any('WITH GRANT OPTION' in row for row in root_grants), 'FIXED_CREATOR_CAPABILITIES_MISSING')
    account = probe('root', root_password,
        "SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('user',User,'host',Host,'locked',account_locked)),JSON_ARRAY()) "
        "FROM mysql.user WHERE User='nexion_support_schema';")
    support_require(account in ([], [{'user': 'nexion_support_schema', 'host': 'localhost', 'locked': 'Y'}]),
                    'EXACT_LOCKED_LOCAL_SCHEMA_ACCOUNT_REQUIRED')
    if account:
        schema_grants = probe('root', root_password, 'SHOW GRANTS FOR ' + SUPPORT_GRANTEE + ';', as_json=False)
        support_require(schema_grants == ['GRANT USAGE ON *.* TO `nexion_support_schema`@`localhost`'],
                        'UNKNOWN_SCHEMA_GRANTS_OR_PARTIAL_INSTALL')
    inventory = probe('root', root_password,
        "SELECT JSON_OBJECT('fund_tables',(SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() "
        "AND TABLE_NAME IN ('nx_support_wallet_journal','nx_support_fund_operation','nx_support_fund_operation_leg','nx_support_fund_operation_party')),"
        "'wallet_metadata',(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
        "AND TABLE_NAME='nx_user_wallet' AND COLUMN_NAME IN ('fund_revision','fund_incarnation')),"
        "'reader',(SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE() AND ROUTINE_NAME='nx_support_fund_schema_ready_v1'),"
        "'wallet_triggers',(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE='nx_user_wallet'));")
    support_require(inventory == {'fund_tables': 0, 'wallet_metadata': 0, 'reader': 0, 'wallet_triggers': 1},
                    'PARTIAL_OBJECTS_OR_UNKNOWN_TRIGGERS_REQUIRE_INSPECTION')
    verify_earnings_trigger(user, password, app, required=True)
    return {**app, 'root_context': root, 'create_schema_account': not account,
            'app_grants_before': probe(user, password, 'SHOW GRANTS FOR CURRENT_USER();', as_json=False)}


class SupportMysqlSession:
    """One mysql process per original file; GET_LOCK/@variables survive barriers.

    Output is spooled privately; only a random exact barrier is read internally.
    A lost response is uncertain and raises into the existing persistent hold.
    """
    def __init__(self, folder, name, user, password):
        self.folder, self.name, self.serial = folder, name, 0
        self.closed = False
        self.nonce = os.urandom(16).hex()
        self.output = folder / (name + '.session.stdout')
        self.out = self.output.open('xb')
        self.err = (folder / (name + '.session.stderr')).open('xb')
        if os.name != 'nt':
            os.fchmod(self.out.fileno(), 0o600)
            os.fchmod(self.err.fileno(), 0o600)
        self.process = None
        try:
            self.process = subprocess.Popen(mysql_args(user) + ['--unbuffered'], stdin=subprocess.PIPE,
                stdout=self.out, stderr=self.err, env={**os.environ, 'MYSQL_PWD': password})
            self.reader = self.output.open('rb')
            self.buffer = b''
        except BaseException:
            if self.process is not None and self.process.poll() is None:
                self.process.kill()
                self.process.wait(timeout=30)
            self.out.close()
            self.err.close()
            raise

    def send(self, data, timeout=180):
        self.serial += 1
        marker = ('SUPPORT_BARRIER_' + self.nonce + '_' + str(self.serial)).encode()
        support_require(marker not in data, 'BARRIER_COLLISION')
        try:
            self.process.stdin.write(data + b"\nSELECT '" + marker + b"';\n")
            self.process.stdin.flush()
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                self.buffer += self.reader.read(65536)
                support_require(len(self.buffer) <= 1024**2, 'SESSION_OUTPUT_LIMIT')
                lines = self.buffer.split(b'\n')
                self.buffer = lines.pop()
                if marker in [line.rstrip(b'\r') for line in lines]:
                    support_require(self.process.poll() is None, 'SESSION_DIED_AT_BARRIER')
                    return
                support_require(self.process.poll() is None, 'SESSION_FAILED_OR_LOST_ACK')
                time.sleep(0.02)
            raise MigrationError('MIGRATION_SUPPORT_SESSION_TIMEOUT_UNCERTAIN')
        except (BrokenPipeError, OSError):
            raise MigrationError('MIGRATION_SUPPORT_SESSION_IO_UNCERTAIN') from None

    def close(self, *, success):
        if self.closed:
            return
        try:
            if success:
                self.process.stdin.close()
                support_require(self.process.wait(timeout=30) == 0, 'SESSION_EXIT_FAILED')
            elif self.process.poll() is None:
                self.process.kill()
                self.process.wait(timeout=30)
        finally:
            if self.process.poll() is None:
                self.process.kill()
                self.process.wait(timeout=30)
            self.closed = True
            self.reader.close()
            self.out.close()
            self.err.close()


def support_expected_body(name, part, raw):
    if part['kind'] == 'FUNCTION':
        token = b"SET @fund_schema_reader_expected_body='"
        text = raw[raw.index(token) + len(token):].decode()
        result, index = [], 0
        while index < len(text):
            if text[index] != "'":
                result.append(text[index]); index += 1
            elif text[index:index + 2] == "''":
                result.append("'"); index += 2
            else:
                return ''.join(result)
        raise MigrationError('MIGRATION_SUPPORT_EXPECTED_BODY_LITERAL_REJECTED')
    text = part['data'].decode()
    if text.startswith('DELIMITER $$\n'):
        return text[text.index('\nBEGIN\n') + 1:text.index('END$$') + 3]
    return text.split('FOR EACH ROW\n', 1)[1].rstrip().removesuffix(';')


def support_verify_program(name, part, raw, user, password, context):
    program = part['program']
    support_require(any(program == contract[0] for contract in SUPPORT_PROGRAMS[name]), 'PROGRAM_NAME_REJECTED')
    expected = support_expected_body(name, part, raw)
    if part['kind'] == 'TRIGGER':
        rows = probe(user, password,
            "SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('name',TRIGGER_NAME,'body',ACTION_STATEMENT,'definer',DEFINER,"
            "'mode',SQL_MODE,'charset',CHARACTER_SET_CLIENT,'collation',COLLATION_CONNECTION,"
            "'table',EVENT_OBJECT_TABLE,'timing',ACTION_TIMING,'event',EVENT_MANIPULATION,'orientation',ACTION_ORIENTATION)),JSON_ARRAY()) "
            "FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME='" + program + "';")
        definer = SUPPORT_APP
    else:
        rows = probe(user, password,
            "SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('name',ROUTINE_NAME,'body',ROUTINE_DEFINITION,'definer',DEFINER,"
            "'mode',SQL_MODE,'charset',CHARACTER_SET_CLIENT,'collation',COLLATION_CONNECTION,'type',ROUTINE_TYPE,"
            "'return',DTD_IDENTIFIER,'deterministic',IS_DETERMINISTIC,'access',SQL_DATA_ACCESS,'security',SECURITY_TYPE,"
            "'args',(SELECT COUNT(*) FROM information_schema.PARAMETERS WHERE SPECIFIC_SCHEMA=DATABASE() "
            "AND SPECIFIC_NAME='nx_support_fund_schema_ready_v1' AND ORDINAL_POSITION>0))),JSON_ARRAY()) "
            "FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE() AND ROUTINE_NAME='nx_support_fund_schema_ready_v1';")
        definer = SUPPORT_SCHEMA
    support_require(isinstance(rows, list) and len(rows) == 1 and isinstance(rows[0], dict), 'PROGRAM_POSTCHECK_MISSING')
    row = rows[0]
    support_require(row.get('name') == program and row.get('body') == expected and row.get('definer') == definer
                    and all(row.get(key) == context[key] for key in ('mode', 'charset', 'collation')),
                    'PROGRAM_POSTCHECK_CHANGED')
    if part['kind'] == 'FUNCTION':
        support_require(all(row.get(key) == value for key, value in
            {'type': 'FUNCTION', 'return': 'tinyint unsigned', 'deterministic': 'NO',
             'access': 'READS SQL DATA', 'security': 'DEFINER', 'args': 0}.items()), 'FUNCTION_SHAPE_CHANGED')
    else:
        header = re.search(r'(BEFORE|AFTER) (INSERT|UPDATE|DELETE) ON (\w+) FOR EACH ROW', part['data'].decode())
        support_require(header and row.get('timing') == header[1] and row.get('event') == header[2]
                        and row.get('table') == header[3] and row.get('orientation') == 'ROW', 'TRIGGER_SHAPE_CHANGED')


def support_session_settings(context):
    support_require(re.fullmatch(r'[A-Z0-9_,]+', context['mode']) and context['charset'] == 'utf8mb4'
                    and re.fullmatch(r'[A-Za-z0-9_]+', context['collation']), 'SESSION_SETTINGS_REJECTED')
    return ("SET SESSION sql_mode='" + context['mode'] + "'; SET NAMES utf8mb4 COLLATE "
            + context['collation'] + ';\n').encode()


def execute_support_file(name, raw, folder, user, password, root_password, context, journal):
    """Install fixed programs inside the existing backup/hold/journal lifecycle."""
    parts = support_segments(name, raw)
    session = SupportMysqlSession(folder, name, user, password)
    success = False

    def stage(label):
        journal['support_segment'] = label
        save(ROOT / 'active.json', journal)
        save(folder / 'receipt.json', journal)

    def root_write(label, fixed_sql):
        current = server_context('root', root_password)
        support_require(current == context['root_context'], 'ROOT_CONTEXT_CHANGED_BEFORE_WRITE')
        execute_sql(label, support_session_settings(context) + fixed_sql, folder, 'root', root_password)

    try:
        if name == next(iter(SUPPORT_PROGRAMS)) and context['create_schema_account']:
            stage('LOCKED_SCHEMA_ACCOUNT')
            root_write(name + '.schema-account',
                       b"CREATE USER 'nexion_support_schema'@'localhost' ACCOUNT LOCK;\n")
        if name.endswith('fund_schema_reader.sql'):
            stage('EXACT_METADATA_GRANTS')
            for index, sql in enumerate(SUPPORT_METADATA_GRANTS):
                root_write(name + '.metadata-grant-' + str(index), sql.encode())
        session.send(b'SET SESSION lock_wait_timeout=30; SET SESSION innodb_lock_wait_timeout=30;\n'
                     + support_session_settings(context))
        for index, part in enumerate(parts):
            stage(str(index) + ':' + (part['program'] or 'APP_SOURCE'))
            if not part['program']:
                data = part['data']
                if name.endswith('fund_schema_reader.sql'):
                    # Exact pinned source; replace only the two definer comparisons,
                    # never CREATE-body/literal bytes. App cannot see another definer's
                    # ROUTINE_DEFINITION; root already checks the entire body before
                    # the suffix, and App still calls the actual reader readiness guard.
                    support_require(data.count(b'DEFINER=CURRENT_USER()') == 1, 'READER_BINDING_COUNT_CHANGED')
                    data = data.replace(b'DEFINER=CURRENT_USER()', b"DEFINER='nexion_support_schema@localhost'")
                    body_check = b' AND BINARY ROUTINE_DEFINITION=BINARY @fund_schema_reader_expected_body'
                    support_require(data.count(body_check) == 1, 'READER_BODY_CHECK_COUNT_CHANGED')
                    data = data.replace(body_check, b'')
                session.send(data)
            else:
                root_write(name + '.' + part['program'], support_template(name, part['program'], raw))
                support_verify_program(name, part, raw, 'root', root_password, context)
                if part['kind'] == 'FUNCTION':
                    stage('EXACT_FUNCTION_EXECUTE_GRANTS')
                    for grant_index, sql in enumerate(SUPPORT_EXECUTE_GRANTS):
                        root_write(name + '.execute-grant-' + str(grant_index), sql.encode())
        if name.endswith('fund_schema_reader.sql'):
            account = probe('root', root_password,
                "SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('user',User,'host',Host,'locked',account_locked)),JSON_ARRAY()) "
                "FROM mysql.user WHERE User='nexion_support_schema';")
            support_require(account == [{'user': 'nexion_support_schema', 'host': 'localhost', 'locked': 'Y'}],
                            'SCHEMA_ACCOUNT_POSTCHECK_CHANGED')
            expected = {'GRANT USAGE ON *.* TO `nexion_support_schema`@`localhost`'}
            expected.update(support_show_grant(sql) for sql in (*SUPPORT_METADATA_GRANTS, SUPPORT_EXECUTE_GRANTS[0]))
            support_require(set(probe('root', root_password, 'SHOW GRANTS FOR ' + SUPPORT_GRANTEE + ';', as_json=False)) == expected,
                            'SCHEMA_GRANTS_POSTCHECK_CHANGED')
            after = set(probe(user, password, 'SHOW GRANTS FOR CURRENT_USER();', as_json=False))
            expected_app = set(context['app_grants_before']) | {support_show_grant(SUPPORT_EXECUTE_GRANTS[1])}
            support_require(after == expected_app, 'APP_EXACT_EXECUTE_POSTCHECK_CHANGED')
        session.close(success=True)
        success = True
    finally:
        if not success:
            session.close(success=False)


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


def fixed_earnings_template(principal, user):
    definer = account_definer(principal, user)
    return (f'CREATE DEFINER={definer} TRIGGER IF NOT EXISTS `{DB}`.`{EARNINGS_TRIGGER}`\n'
            f'BEFORE UPDATE ON `{DB}`.`nx_user_wallet` FOR EACH ROW\n' + EARNINGS_BODY + ';\n').encode()


def install_fixed_earnings_trigger(folder, user, password, root_password, context):
    """The only root SQL write: no archive bytes, filenames or SQL body accepted."""
    require(context.get('root_install') is True and fixed_trigger_enabled(),
            'MIGRATION_TRIGGER_PRIVILEGED_INSTALL_NOT_APPROVED')
    template = fixed_earnings_template(context['principal'], user)
    app_current = server_context(user, password)
    require(all(app_current[k] == context[k]
                for k in ('principal', 'version', 'mode', 'charset', 'collation', 'log_bin', 'trust')),
            'MIGRATION_TRIGGER_APP_CONTEXT_CHANGED')
    current = server_context('root', root_password)
    require(current['principal'].startswith('root@')
            and all(current[k] == context[k] for k in ('version', 'mode', 'charset', 'collation', 'log_bin', 'trust')),
            'MIGRATION_TRIGGER_ROOT_CONTEXT_REJECTED')
    columns = probe(user, password,
                    "SELECT JSON_ARRAYAGG(JSON_OBJECT('name',COLUMN_NAME,'type',COLUMN_TYPE,"
                    "'nullable',IS_NULLABLE,'default',COLUMN_DEFAULT)) FROM information_schema.COLUMNS "
                    "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_wallet' AND COLUMN_NAME "
                    "IN ('earnings_usdt_debited','earnings_nex_debited');")
    require(isinstance(columns, list) and len(columns) == 2 and all(isinstance(c, dict) for c in columns)
            and {c.get('name') for c in columns} == {'earnings_usdt_debited', 'earnings_nex_debited'}
            and all(c.get('type') == 'decimal(30,6)' and c.get('nullable') == 'NO'
                    and isinstance(c.get('default'), str) and re.fullmatch(r'0(?:\.0+)?', c['default'])
                    for c in columns), 'MIGRATION_TRIGGER_COLUMNS_REJECTED')
    if not verify_earnings_trigger(user, password, context):
        with (folder / (EARNINGS_FILE + '.trigger.stdout')).open('xb') as out, \
                (folder / (EARNINGS_FILE + '.trigger.stderr')).open('xb') as err:
            result = subprocess.run(mysql_args('root'),
                                    input=b'SET SESSION lock_wait_timeout=30;\n' + template,
                                    stdout=out, stderr=err, timeout=180,
                                    env={**os.environ, 'MYSQL_PWD': root_password})
        require(result.returncode == 0, 'MIGRATION_FIXED_TRIGGER_INSTALL_FAILED')
    verify_earnings_trigger(user, password, context, required=True)
    return digest(template)


def execute_earnings_recovery(data, folder, user, password, root_password, context, journal):
    prefix, suffix = earnings_parts(data)

    def segment(value):
        journal['segment'] = value
        save(ROOT / 'active.json', journal)
        save(folder / 'receipt.json', journal)

    journal['fixed_trigger'] = {'actor': 'ROOT_FIXED_TRIGGER_INSTALL', 'definer': context['principal'],
                                'template_sha256': digest(fixed_earnings_template(context['principal'], user))}
    segment('PREFIX')
    execute_sql(EARNINGS_FILE + '.prefix', prefix, folder, user, password)
    segment('TRIGGER_INSTALLING')
    install_fixed_earnings_trigger(folder, user, password, root_password, context)
    journal['fixed_trigger']['verified'] = True
    segment('TRIGGER_VERIFIED')
    segment('SUFFIX')
    execute_sql(EARNINGS_FILE + '.suffix', suffix, folder, user, password)
    verify_earnings_trigger(user, password, context, required=True)
    segment('SUFFIX_VERIFIED')


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
    was_blocked = (ROOT / 'START_BLOCKED').exists()
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
    support_order = support_prepare(pending)
    user, password, root_password = credentials()
    scoped_account(user, password)
    trigger_context = migration_preflight(
        [(name, data) for name, data in pending if not support_order or name not in SUPPORT_PROGRAMS],
        user, password, root_password)
    if support_order:
        support_context = support_preflight(user, password, root_password)
        sources = dict(pending)
        pending = [(name, sources[name]) for name in support_order]
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
            journal.pop('segment', None)
            journal.pop('support_segment', None)
            journal.update(phase='SQL_APPLYING', current=name)
            save(ROOT / 'active.json', journal)
            sql_started = True
            if support_order and name in SUPPORT_PROGRAMS:
                execute_support_file(name, data, folder, user, password, root_password, support_context, journal)
            elif name == EARNINGS_FILE and trigger_context['root_install']:
                execute_earnings_recovery(data, folder, user, password, root_password, trigger_context, journal)
            else:
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
        if not sql_started and not previous and not was_blocked:
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

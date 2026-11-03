"""Render the three local startup inputs from the reviewed, immutable migrations."""
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS / 'release'))
from prepare_h4_migration_support import H4_FILE, SCHEMA_FILE, TRIGGER, schema_bodies

JOURNAL = '20261011_support_fund_journal.sql'
READER = 'nx_support_fund_schema_ready_v1'
MARKER = 'nx_local_h4_reader_rebuild'
LOCAL_OWNER = 'root@localhost'
SCHEMA_OWNER = 'nexion_support_schema@localhost'
APP = 'nexion_app@%'
INVENTORY = "AND EVENT_OBJECT_TABLE='nx_user_wallet')=4\n AND (SELECT COUNT(*)"
ACL = ("SELECT COALESCE(JSON_OBJECTAGG(CONCAT(HEX(User),'@',HEX(Host)),CAST(Proc_priv AS CHAR)),JSON_OBJECT()) "
       f"FROM mysql.procs_priv WHERE Db=DATABASE() AND Routine_name='{READER}' AND Routine_type='FUNCTION'")
EXECUTE = 'PREPARE fund_schema_reader_stmt FROM @fund_schema_reader_sql; EXECUTE fund_schema_reader_stmt; DEALLOCATE PREPARE fund_schema_reader_stmt;'


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


def pinned_source(name, checksum):
    source = (SCRIPTS / 'migrations' / name).read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(source).hexdigest() != checksum:
        raise ValueError('Reviewed migration changed: ' + name)
    return source.decode('utf-8')


def query(mysql, sql):
    result = subprocess.run([*mysql, '-N', '-B', '--raw', '-e', sql], check=True, stdout=subprocess.PIPE)
    return json.loads(result.stdout)


def identifier(name):
    if not name.isprintable() or '\\' in name:
        raise ValueError('Unsupported identifier in reader grants')
    return '`' + name.replace('`', '``') + '`'


def receipt_file(token):
    if not re.fullmatch('[0-9a-f]{32}', token):
        raise ValueError('Invalid reader recovery token')
    folder = Path.home() / '.nexion-local-h4'
    folder.mkdir(mode=0o700, exist_ok=True)
    info = folder.lstat()
    if folder.is_symlink() or (os.name != 'nt' and (info.st_uid != os.getuid() or info.st_mode & 0o077)):
        raise ValueError('The reader recovery directory must be private to this OS user')
    return folder / (token + '.json')


def acl_matches(grants):
    return f"({ACL})=CAST(CONVERT(X'{json.dumps(grants).encode().hex()}' USING utf8mb4) AS JSON)"


def prepare(folder, mysql, journal_only=False):
    h4 = pinned_source(H4_FILE, '921d9f1e3567dfbd049da78ba878ab2187f9c6b15ed449a748be3e2118688d5d')
    journal = pinned_source(JOURNAL, 'bd78b16a586887e02c7125918f950e80a99c33577c39b8e2c1ce825182574ec4')

    body = h4.split('AFTER UPDATE ON nx_user_wallet FOR EACH ROW\n', 1)[1].removesuffix('$$\nDELIMITER ;\n')
    no_comments = '\n'.join(line for line in body.split('\n') if not line.lstrip().startswith('-- '))
    h4_shape = (f"BINARY TRIGGER_NAME='{TRIGGER}' AND EVENT_OBJECT_TABLE='nx_user_wallet'\n"
                " AND ACTION_TIMING='AFTER' AND EVENT_MANIPULATION='UPDATE' AND ACTION_ORIENTATION='ROW'\n"
                f" AND ACTION_ORDER=2 AND BINARY DEFINER IN ('{LOCAL_OWNER}','{APP}')\n"
                f" AND SHA2(ACTION_STATEMENT,256) IN ('{digest(body)}','{digest(no_comments)}')")
    inventory = (f"AND (EVENT_OBJECT_TABLE='nx_user_wallet' OR BINARY TRIGGER_NAME='{TRIGGER}')\n"
                 f" AND NOT ({h4_shape}))=4\n AND (SELECT COUNT(*)")
    # The managed verifier compares the entire original body, including its comments.
    install_shape = h4_shape + f" AND (BINARY DEFINER='{LOCAL_OWNER}' OR SHA2(ACTION_STATEMENT,256)='{digest(body)}')"
    # The archived first-install inventory of one remains intact. Only the final count changes.
    journal = journal.replace(INVENTORY, inventory)
    folder.mkdir(parents=True, exist_ok=True)
    (folder / JOURNAL).write_bytes(journal.encode())
    if journal_only:
        return
    raw = (SCRIPTS / 'migrations' / SCHEMA_FILE).read_bytes()
    baseline, managed, managed_create = schema_bodies(raw)  # Also verifies the locked reader SHA-256.
    reader = raw.decode('utf-8-sig').replace('\r\n', '\n')
    original_create = reader.split('-- FUNCTION_BODY_BEGIN\n')[1].split('-- FUNCTION_BODY_END')[0].rstrip()
    local = baseline.replace(INVENTORY, inventory)
    triggers = 'FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()'
    routines = 'FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE()'
    marker_catalog = f"{routines} AND ROUTINE_NAME='{MARKER}'"
    state = query(mysql, f"SELECT JSON_OBJECT('server',@@server_uuid,'db',DATABASE(),'principal',CURRENT_USER(),"
                  f"'h4',(SELECT DEFINER {triggers} AND TRIGGER_NAME='{TRIGGER}'),"
                  f"'valid_h4',EXISTS(SELECT 1 {triggers} AND {install_shape}),"
                  "'app',EXISTS(SELECT 1 FROM mysql.user WHERE BINARY User='nexion_app' AND Host='%'),"
                  f"'marker',(SELECT JSON_OBJECT('owner',DEFINER,'type',ROUTINE_TYPE,'body',ROUTINE_DEFINITION) {marker_catalog}),"
                  f"'reader',(SELECT JSON_OBJECT('owner',DEFINER,'sha',SHA2(ROUTINE_DEFINITION,256),'comment',ROUTINE_COMMENT) "
                  f"{routines} AND ROUTINE_NAME='{READER}'));")
    current, marker = state['reader'], state['marker']
    if state['h4'] and not state['valid_h4']:
        raise ValueError('Existing H4 trigger does not match the reviewed definition')
    if current and (current['sha'] not in {digest(baseline), digest(managed), digest(local)} or current['comment']):
        raise ValueError('Unrecognized existing support reader definition')

    receipt = None
    token = None
    marker_guard = f'NOT EXISTS(SELECT 1 {marker_catalog})'
    if marker:
        if state['principal'] != LOCAL_OWNER:
            raise ValueError('Interrupted reader recovery requires the local bootstrap root')
        match = re.fullmatch("RETURN '([0-9a-f]{32})'", marker['body'] or '')
        if marker['type'] != 'FUNCTION' or marker['owner'] != LOCAL_OWNER or not match:
            raise ValueError('Unrecognized reader recovery marker')
        token = match[1]
        path = receipt_file(token)
        if path.is_symlink() or not path.is_file():
            raise ValueError('Reader recovery requires the original bootstrap user and its private receipt')
        receipt = json.loads(path.read_bytes())
        if (receipt['server'], receipt['db'], receipt['token']) != (state['server'], state['db'], token):
            raise ValueError('Reader recovery receipt belongs to another database')
        if receipt['original'] not in {digest(baseline), digest(managed), digest(local)}:
            raise ValueError('Reader recovery has an unreviewed original definition')
        marker_guard = (f"EXISTS(SELECT 1 {marker_catalog} AND ROUTINE_TYPE='FUNCTION' AND BINARY DEFINER='{LOCAL_OWNER}' "
                        f"AND BINARY ROUTINE_DEFINITION=BINARY 'RETURN ''{token}''')")
    owner = receipt['owner'] if receipt else current['owner'] if current else state['principal']
    if owner not in (LOCAL_OWNER, SCHEMA_OWNER) or (current and current['owner'] != owner):
        raise ValueError('Unapproved support reader definer')
    if state['h4'] == APP and (not state['app'] or owner != SCHEMA_OWNER or (not current and not receipt)):
        raise ValueError('Restore the managed support reader and its grants before replaying app-defined H4')

    managed_profile = owner == SCHEMA_OWNER and state['h4'] != LOCAL_OWNER
    target, create = (managed, managed_create) if managed_profile else (local, original_create.replace(INVENTORY, inventory))
    if managed_profile and not state['h4'] and current and current['sha'] == digest(baseline):
        target, create = baseline, original_create
    reader = reader.replace(baseline.replace("'", "''"), target.replace("'", "''"), 1).replace(original_create, create, 1)
    rebuild = current and current['sha'] != digest(target)
    if receipt and (receipt['target'] != digest(target) or (rebuild and receipt['original'] != current['sha'])):
        raise ValueError('Reader recovery target or original definition changed')
    create_marker = ''
    if rebuild and not receipt:
        if state['principal'] != LOCAL_OWNER:
            raise ValueError('Existing reader replacement requires the local bootstrap root')
        token = secrets.token_hex(16)
        receipt = {'token': token, 'server': state['server'], 'db': state['db'], 'owner': owner,
                   'original': current['sha'], 'target': digest(target), 'grants': query(mysql, ACL)}
        # The marker contains no grant data. Only the private receipt supplies prior grants.
        create_marker = (f"CREATE DEFINER=`root`@`localhost` FUNCTION {MARKER}() RETURNS CHAR(32) DETERMINISTIC "
                         f"NO SQL SQL SECURITY INVOKER RETURN '{token}';\n")

    grants = dict(receipt['grants']) if receipt else {}
    for required in ([SCHEMA_OWNER] if owner == SCHEMA_OWNER else []) + ([APP] if state['app'] else []):
        account = '@'.join(part.encode().hex().upper() for part in required.split('@'))
        rights = set(grants.get(account, '').split(',')) | {'Execute'}
        grants[account] = ','.join(p for p in ('Execute', 'Alter Routine', 'Grant') if p in rights)
    qualified = identifier(state['db']) + '.' + identifier(READER)
    grant_sql = []
    for account, privileges in grants.items():
        user, host = (identifier(bytes.fromhex(part).decode()) for part in account.split('@'))
        rights = set(privileges.split(','))
        if rights - {'Execute', 'Alter Routine', 'Grant'} or not rights - {'Grant'}:
            raise ValueError('Unrecognized support reader privilege')
        grant_option = ' WITH GRANT OPTION' if 'Grant' in rights else ''
        grant_sql.append(f"GRANT {', '.join(sorted(rights - {'Grant'}))} ON FUNCTION {qualified} TO {user}@{host}{grant_option};")
    if receipt:
        grant_sql.extend([f"SET @fund_schema_reader_sql=IF({acl_matches(grants)},'SELECT 1','FAIL H4_READER_GRANTS_CHANGED');", EXECUTE])

    definer = '@'.join(identifier(part) for part in owner.split('@'))
    reader = reader.replace('DEFINER=CURRENT_USER()', f"BINARY DEFINER='{owner}' AND SQL_MODE=@@SESSION.sql_mode\n"
                            ' AND CHARACTER_SET_CLIENT=@@SESSION.character_set_client AND COLLATION_CONNECTION=@@SESSION.collation_connection')
    reader = reader.replace('CREATE DEFINER=CURRENT_USER', f'CREATE DEFINER={definer}')
    h4_guard = (f"EXISTS(SELECT 1 {triggers} AND {install_shape} AND BINARY DEFINER='{state['h4']}')" if state['h4'] else
                f"NOT EXISTS(SELECT 1 {triggers} AND TRIGGER_NAME='{TRIGGER}')")
    existing = ('@fund_schema_reader_existing=1 AND @fund_schema_reader_existing_ok=1' if current else '@fund_schema_reader_existing=0')
    reader = reader.replace('IF(@fund_schema_reader_existing=0 OR (@fund_schema_reader_existing=1 AND @fund_schema_reader_existing_ok=1)',
                            f'IF({existing} AND {h4_guard} AND {marker_guard}')
    if current:
        before_drop = ' AND ' + acl_matches(receipt['grants']) if rebuild else ''
        reader = reader.replace('BINARY ROUTINE_DEFINITION=BINARY @fund_schema_reader_expected_body',
                                f"SHA2(ROUTINE_DEFINITION,256)='{current['sha']}' AND ROUTINE_COMMENT=''" + before_drop, 1)
    if rebuild:
        reader = reader.replace('CREATE DEFINER=', create_marker + f'DROP FUNCTION {qualified};\nCREATE DEFINER=', 1)
    reader = reader.replace('-- FUNCTION_BODY_END', '-- FUNCTION_BODY_END\n' + '\n'.join(grant_sql))
    if receipt:
        reader = reader.replace('SELECT RELEASE_LOCK(@fund_schema_reader_lock);', f'DROP FUNCTION {MARKER};\nSELECT RELEASE_LOCK(@fund_schema_reader_lock);')

    if managed_profile and not state['h4']:
        # A managed first H4 install belongs to its reviewed, separately authorized release lane.
        h4 = "FAIL LOCAL_MANAGED_H4_INSTALL_REQUIRES_RELEASE;\n"
    h4 += (f"\nSET @h4_check=IF((SELECT COUNT(*) {triggers} AND {install_shape})=1 AND {READER}()=1,"
           "'SELECT 1','FAIL LOCAL_H4_SUPPORT_SCHEMA_NOT_READY');\n"
           'PREPARE h4_check FROM @h4_check; EXECUTE h4_check; DEALLOCATE PREPARE h4_check;\n')
    for name, sql in ((SCHEMA_FILE, reader), (H4_FILE, h4)):
        (folder / name).write_bytes(sql.encode())
    if create_marker:
        path = receipt_file(token)
        with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w', encoding='utf-8') as stream:
            json.dump(receipt, stream)
            stream.flush()
            os.fsync(stream.fileno())
        if os.name != 'nt':
            directory = os.open(path.parent, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
    if receipt:
        (folder / 'recovery-token').write_text(token, encoding='ascii')


def finish(folder, mysql, failed=False):
    pointer = folder / 'recovery-token'
    if pointer.is_file():
        path = receipt_file(pointer.read_text(encoding='ascii'))
        receipt = json.loads(path.read_bytes())
        state = query(mysql, f"SELECT JSON_ARRAY(@@server_uuid,DATABASE(),(SELECT COUNT(*) FROM information_schema.ROUTINES "
                      f"WHERE ROUTINE_SCHEMA=DATABASE() AND ROUTINE_NAME='{MARKER}'));")
        if state[:2] != [receipt['server'], receipt['db']]:
            raise ValueError('Reader recovery receipt belongs to another database')
        if state[2]:
            if failed:
                return  # A failed reader may have installed its marker. Keep the receipt for retry.
            raise ValueError('Reader recovery is still pending. Its private receipt was retained.')
        path.unlink()
        pointer.unlink()


if __name__ == '__main__':
    action, folder, *mysql = sys.argv[1:]
    if action in ('prepare', 'journal'):
        prepare(Path(folder), mysql, journal_only=action == 'journal')
    elif action in ('finish', 'abort'):
        finish(Path(folder), mysql, failed=action == 'abort')
    else:
        raise ValueError('Unknown reader bootstrap action')

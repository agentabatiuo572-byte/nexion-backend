"""Restore the pinned four-wallet reader on local databases upgraded from 3dc5196e."""
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import stat
import subprocess
import sys


READER = 'nx_support_fund_schema_ready_v1'
MARKER = 'nx_local_h4_reader_rebuild'
ROOT = 'root@localhost'
SCHEMA = 'nexion_support_schema@localhost'
# SHA2(ROUTINE_DEFINITION,256) from the archived migration and the two 3dc5196e renderings.
BASE = '45a2ea67a1edfd4fc421c60c766f940432a24928e5506c14fa054de9b2b74bb6'
LOCAL = '5deb448951d7aef59b30ec43c7ea8916a359de0e0192cd77755cb15429764661'
MANAGED = '676127a05edc62c9657c50727de61fccff75a69ab7daa4da2a7cdc77c81584b0'
CATALOG = 'FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE()'
CONTEXT = """SQL_MODE=@@SESSION.sql_mode AND CHARACTER_SET_CLIENT=@@SESSION.character_set_client
 AND COLLATION_CONNECTION=@@SESSION.collation_connection AND ROUTINE_TYPE='FUNCTION' AND ROUTINE_COMMENT=''
 AND NOT EXISTS(SELECT 1 FROM information_schema.PARAMETERS WHERE SPECIFIC_SCHEMA=DATABASE()
 AND SPECIFIC_NAME=ROUTINE_NAME AND ORDINAL_POSITION>0)"""
ACL = f"""(SELECT COALESCE(JSON_OBJECTAGG(CONCAT(HEX(User),'@',HEX(Host)),CAST(Proc_priv AS CHAR)),JSON_OBJECT())
 FROM mysql.procs_priv WHERE BINARY Db=BINARY DATABASE() AND Routine_name='{READER}' AND Routine_type='FUNCTION')"""
STATE = f"""JSON_OBJECT('server',@@server_uuid,'db',DATABASE(),'principal',CURRENT_USER(),
 'app',EXISTS(SELECT 1 FROM mysql.user WHERE BINARY User='nexion_app' AND Host='%'),
 'reader',(SELECT JSON_OBJECT('owner',DEFINER,'sha',SHA2(ROUTINE_DEFINITION,256),
 'valid',({CONTEXT} AND DTD_IDENTIFIER='tinyint unsigned' AND IS_DETERMINISTIC='NO'
 AND SQL_DATA_ACCESS='READS SQL DATA' AND SECURITY_TYPE='DEFINER')) {CATALOG} AND ROUTINE_NAME='{READER}'),
 'marker',(SELECT JSON_OBJECT('body',ROUTINE_DEFINITION,'valid',({CONTEXT} AND BINARY DEFINER='{ROOT}'
 AND DTD_IDENTIFIER='char(32)' AND IS_DETERMINISTIC='YES' AND SQL_DATA_ACCESS='NO SQL'
 AND SECURITY_TYPE='INVOKER')) {CATALOG} AND ROUTINE_NAME='{MARKER}'),
 'grants',{ACL})"""


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def identifier(name):
    require(all(c.isprintable() and c != '\\' for c in name), 'Unsupported identifier in reader grants')
    return '`' + name.replace('`', '``') + '`'


def json_sql(value):
    return "CAST(CONVERT(X'" + json.dumps(value).encode().hex() + "' USING utf8mb4) AS JSON)"


def guard(condition, reason):
    return (f"SET @retire_reader_sql=IF({condition},'DO 0','FAIL {reason}');\n"
            "PREPARE retire_reader_stmt FROM @retire_reader_sql; EXECUTE retire_reader_stmt; "
            "DEALLOCATE PREPARE retire_reader_stmt;\n")


def private(path, directory=False):
    info = path.lstat()
    require((stat.S_ISDIR if directory else stat.S_ISREG)(info.st_mode) and
            not getattr(info, 'st_file_attributes', 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT and
            (os.name == 'nt' or (info.st_uid == os.getuid() and not info.st_mode & 0o077)),
            'Reader recovery requires a private receipt owned by the original bootstrap user')


def write_receipt(path, data):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w', encoding='utf-8') as f:
        json.dump(data, f)
        f.flush()
        os.fsync(f.fileno())
    if os.name != 'nt':
        fd = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)


def grant_sets(grants):
    result = {}
    for key, value in grants.items():
        require(re.fullmatch(r'(?:[0-9A-F]{2})*@(?:[0-9A-F]{2})+', key), 'Invalid reader grantee')
        rights = set(value.split(','))
        require(rights <= {'Execute', 'Alter Routine', 'Grant'} and rights - {'Grant'}, 'Invalid reader privilege')
        result[key] = rights
    return result


def subset(live, expected):
    return all(rights <= expected.get(key, set()) for key, rights in live.items())


def upgrade(mysql):
    archive = (Path(__file__).resolve().parents[1] / 'migrations/20261011_support_fund_schema_reader.sql')
    raw = archive.read_bytes().replace(b'\r\n', b'\n')
    require(hashlib.sha256(raw).hexdigest() == 'e953d9df7afb43c1683289153dbcae73c5e28df14d196106df2ec663fd0fc425',
            'Pinned support reader migration changed')
    state = json.loads(subprocess.check_output([*mysql, '-N', '-B', '--raw', '-e', f'SELECT {STATE};']))
    reader, marker = state['reader'], state['marker']
    require(state['principal'] in (ROOT, 'root@127.0.0.1', 'root@::1'), 'Local bootstrap root required for reader upgrade')
    sha = reader['sha'] if reader else None
    if reader:
        require(reader['valid'] and sha in (BASE, LOCAL, MANAGED) and reader['owner'] in (ROOT, SCHEMA),
                'Unexpected support reader definition, definer or session settings')
    # Compare the entire snapshot again under the archived migration's reader lock before any DDL.
    before = guard(f'{STATE}={json_sql(state)}', 'H4_READER_CATALOG_CHANGED')
    after = ''
    path = old_path = None
    owner = reader['owner'] if reader else None
    if marker or sha != BASE:
        require(marker or reader, 'An absent reader without a recovery marker belongs to the normal install')
        token = secrets.token_hex(16)
        if marker:
            match = re.fullmatch("RETURN '([0-9a-f]{32})'", marker['body'] or '')
            require(marker['valid'] and match, 'Unrecognized local reader recovery marker')
            token = match[1]
        folder = Path.home() / '.nexion-local-h4'
        if not marker:
            folder.mkdir(mode=0o700, exist_ok=True)
        private(folder, directory=True)
        path = folder / (token + '.retire.json')
        old_path = folder / (token + '.json') if marker else None
        binding = {k: state[k] for k in ('server', 'db')} | {'token': token}
        live = grant_sets(state['grants'])
        if path.exists() or path.is_symlink():
            private(path)
            receipt = json.loads(path.read_bytes())
            require(marker and all(receipt[k] == v for k, v in binding.items()), 'Wrong reader retirement receipt')
        else:
            desired = state['grants']
            old_digest = None
            if marker:
                private(old_path)
                old_bytes = old_path.read_bytes()
                old = json.loads(old_bytes)
                old_digest = hashlib.sha256(old_bytes).hexdigest()
                owner = old['owner']
                require(all(old[k] == v for k, v in binding.items()) and owner in (ROOT, SCHEMA)
                        and old['original'] in (BASE, LOCAL, MANAGED) and old['original'] != old['target']
                        and old['target'] in ((LOCAL,) if owner == ROOT else (LOCAL, MANAGED))
                        and (not reader or (reader['owner'] == owner and sha in (old['original'], old['target'], BASE))),
                        'Old reader receipt does not match the server, database, definer or definition')
                saved = grant_sets(old['grants'])
                # Complete the old helper's EXECUTE grants even if it stopped immediately after DROP.
                expected = {k: set(v) for k, v in saved.items()}
                required = ([SCHEMA] if owner == SCHEMA else []) + (['nexion_app@%'] if state['app'] else [])
                for account in required:
                    key = '@'.join(part.encode().hex().upper() for part in account.split('@'))
                    expected.setdefault(key, set()).add('Execute')
                if sha == old['original']:
                    require(live == saved, 'Original reader grants have changed since the old receipt')
                else:
                    require(subset(live, expected), 'Unexpected reader grants during old recovery')
                desired = {k: ','.join(p for p in ('Execute', 'Alter Routine', 'Grant') if p in rights)
                           for k, rights in expected.items()}
            receipt = binding | {'owner': owner, 'source': sha, 'sourceGrants': state['grants'], 'grants': desired,
                                 'legacy': old_digest}
            write_receipt(path, receipt)
        owner = receipt['owner']
        require(owner in (ROOT, SCHEMA) and (not reader or reader['owner'] == owner) and
                sha in (receipt['source'], BASE, None), 'Reader definer or definition changed during retirement')
        grants = grant_sets(receipt['grants'])
        require((live == grant_sets(receipt['sourceGrants']) if sha not in (None, BASE) else subset(live, grants)),
                'Unexpected reader grants during retirement')
        if receipt['legacy']:
            private(old_path)
            require(hashlib.sha256(old_path.read_bytes()).hexdigest() == receipt['legacy'], 'Old recovery receipt changed')
        else:
            old_path = None
        qualified = identifier(state['db']) + '.' + identifier(READER)
        accounts = [tuple(p.encode().hex().upper() for p in account.split('@')) for account in (ROOT, owner)]
        for key, rights in grants.items():
            user, host = key.split('@')
            accounts.append((user, host))
            grantee = '@'.join(identifier(bytes.fromhex(p).decode()) for p in (user, host))
            option = ' WITH GRANT OPTION' if 'Grant' in rights else ''
            after += f"GRANT {', '.join(sorted(rights - {'Grant'}))} ON FUNCTION {qualified} TO {grantee}{option};\n"
        for user, host in set(accounts):
            before += guard(f"EXISTS(SELECT 1 FROM mysql.user WHERE HEX(User)='{user}' AND HEX(Host)='{host}')",
                            'H4_READER_ACCOUNT_MISSING')
        if not marker:
            before += (f"CREATE DEFINER=`root`@`localhost` FUNCTION {MARKER}() RETURNS CHAR(32) DETERMINISTIC "
                       f"NO SQL SQL SECURITY INVOKER RETURN '{token}';\n")
        if reader and sha != BASE:
            before += f'DROP FUNCTION {qualified};\n'
        after += guard(f"{ACL}={json_sql(receipt['grants'])}", 'H4_READER_GRANTS_NOT_RESTORED')

    # Reuse all archived shape and readiness checks, substituting only the verified original definer.
    sql = raw.decode().replace('DEFINER=CURRENT_USER()', f"BINARY DEFINER='{owner}'")
    sql = sql.replace('CREATE DEFINER=CURRENT_USER', 'CREATE DEFINER=' + '@'.join(map(identifier, owner.split('@'))))
    sql = sql.replace('SET @fund_schema_reader_expected_body=', before + 'SET @fund_schema_reader_expected_body=', 1)
    # A restricted definer needs its original EXECUTE grant to run the final readiness check.
    sql = sql.replace('SET @fund_schema_reader_ready=', after + 'SET @fund_schema_reader_ready=', 1)
    if path:
        sql = sql.replace('SELECT RELEASE_LOCK(', f'DROP FUNCTION {MARKER};\nSELECT RELEASE_LOCK(', 1)
    subprocess.run([*mysql, '--batch', '--skip-reconnect'], input=sql.encode(), check=True)
    if old_path:
        old_path.unlink()
    if path:
        path.unlink()


if __name__ == '__main__':
    upgrade(sys.argv[1:])

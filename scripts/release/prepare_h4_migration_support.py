"""Prepare a reviewed candidate only; never installs or changes the live release gate."""
import argparse
import hashlib
import re
from pathlib import Path

H4_FILE = "20261025_h4_event_rules.sql"
TRIGGER = "nx_wallet_h4_holding_observation"
SCHEMA_FILE = "20261011_support_fund_schema_reader.sql"
SCHEMA_SHA256 = "e953d9df7afb43c1683289153dbcae73c5e28df14d196106df2ec663fd0fc425"


def schema_bodies(raw):
    raw = raw.replace(b'\r\n', b'\n')  # Git checkout line endings; the reviewed archive bytes are LF.
    if hashlib.sha256(raw).hexdigest() != SCHEMA_SHA256:
        raise ValueError('REVIEWED_SCHEMA_HASH_MISMATCH')
    match = re.search(r"SET @fund_schema_reader_expected_body='((?:[^']|'')*)';", raw.decode('utf-8-sig'))
    if not match:
        raise ValueError('REVIEWED_SCHEMA_BODY_MISSING')
    baseline = match[1].replace("''", "'")
    before = "AND EVENT_OBJECT_TABLE='nx_user_wallet')=4\n AND (SELECT COUNT(*)"
    after = ("AND EVENT_OBJECT_TABLE='nx_user_wallet'\n"
             " AND NOT (TRIGGER_NAME='nx_wallet_h4_holding_observation'\n"
             " AND ACTION_TIMING='AFTER' AND EVENT_MANIPULATION='UPDATE' AND ACTION_ORIENTATION='ROW'\n"
             " AND DEFINER='nexion_app@%'))=4\n AND (SELECT COUNT(*)")
    if baseline.count(before) != 1:
        raise ValueError('REVIEWED_SCHEMA_INVENTORY_ANCHOR_CHANGED')
    create_body = raw.decode('utf-8-sig').split('-- FUNCTION_BODY_BEGIN\n', 1)[1].split('-- FUNCTION_BODY_END', 1)[0].rstrip()
    if create_body.count(before) != 1:
        raise ValueError('REVIEWED_SCHEMA_CREATE_ANCHOR_CHANGED')
    # ROUTINE_DEFINITION normalizes nested literals; it is metadata, not executable SQL.
    return baseline, baseline.replace(before, after, 1), create_body.replace(before, after, 1)

TEMPLATE = '''
# One reviewed H4 program. Other stored programs still use the existing rejection gate.
H4_FILE = {filename!r}
H4_SHA256 = {checksum!r}
H4_PREFIX = {prefix!r}
H4_BODY = {body!r}
H4_TRIGGER = {trigger!r}
H4_DEFINER = 'nexion_app@%'
H4_SCHEMA_BASELINE = {schema_baseline!r}
H4_SCHEMA_BODY = {schema_body!r}
H4_SCHEMA_CREATE_BODY = {schema_create_body!r}
H4_METADATA_SQL = (
    "SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('name',TRIGGER_NAME,'body',ACTION_STATEMENT,"
    "'definer',DEFINER,'event',EVENT_MANIPULATION,'timing',ACTION_TIMING,"
    "'table',EVENT_OBJECT_TABLE,'orientation',ACTION_ORIENTATION,'order',ACTION_ORDER,"
    "'journal_order',(SELECT ACTION_ORDER FROM information_schema.TRIGGERS j WHERE j.TRIGGER_SCHEMA=DATABASE() "
    "AND j.TRIGGER_NAME='nx_support_wallet_journal_au_v1'))),JSON_ARRAY()) "
    "FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() "
    "AND TRIGGER_NAME='nx_wallet_h4_holding_observation';")

def h4_prepare(pending):
    matches = [data for name, data in pending if name == H4_FILE]
    if not matches:
        return False
    require(len(matches) == 1 and digest(matches[0]) == H4_SHA256,
            'MIGRATION_H4_FIXED_SOURCE_CHANGED')
    return True

def h4_enabled():
    trusted(HOST_CONFIG)
    config = json.loads(HOST_CONFIG.read_bytes())
    require(isinstance(config, dict), 'MIGRATION_HOST_CONFIG_REJECTED')
    value = config.get('allow_fixed_h4_trigger_install', False)
    require(type(value) is bool, 'MIGRATION_HOST_CONFIG_REJECTED')
    return value

def h4_verify(root_password, required=False):
    rows = probe('root', root_password, H4_METADATA_SQL)
    require(isinstance(rows, list), 'MIGRATION_H4_METADATA_REJECTED')
    if not rows:
        require(not required, 'MIGRATION_H4_TRIGGER_MISSING')
        return False
    require(len(rows) == 1 and isinstance(rows[0], dict), 'MIGRATION_H4_TRIGGER_SHAPE_CHANGED')
    row = rows[0]
    require(row.get('name') == H4_TRIGGER and row.get('definer') == H4_DEFINER
            and row.get('event') == 'UPDATE' and row.get('timing') == 'AFTER'
            and row.get('table') == 'nx_user_wallet' and row.get('orientation') == 'ROW'
            and row.get('body', '').strip() == H4_BODY.strip()
            and row.get('order') == 2 and row.get('journal_order') == 1,
            'MIGRATION_H4_TRIGGER_SHAPE_CHANGED')
    return True

def h4_schema_verify(root_password, context, allow_baseline=False):
    rows = probe('root', root_password,
        "SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('name',ROUTINE_NAME,'body',ROUTINE_DEFINITION,'definer',DEFINER,"
        "'mode',SQL_MODE,'charset',CHARACTER_SET_CLIENT,'collation',COLLATION_CONNECTION,'type',ROUTINE_TYPE,"
        "'return',DTD_IDENTIFIER,'deterministic',IS_DETERMINISTIC,'access',SQL_DATA_ACCESS,'security',SECURITY_TYPE,"
        "'args',(SELECT COUNT(*) FROM information_schema.PARAMETERS WHERE SPECIFIC_SCHEMA=DATABASE() "
        "AND SPECIFIC_NAME='nx_support_fund_schema_ready_v1' AND ORDINAL_POSITION>0))),JSON_ARRAY()) "
        "FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE() AND ROUTINE_NAME='nx_support_fund_schema_ready_v1';")
    require(isinstance(rows, list) and len(rows) == 1 and isinstance(rows[0], dict),
            'MIGRATION_H4_SUPPORT_FIRST_INSTALL_REQUIRED')
    row = rows[0]
    require(all(row.get(key) == value for key, value in
                {{'name': 'nx_support_fund_schema_ready_v1', 'definer': SUPPORT_SCHEMA,
                  'type': 'FUNCTION', 'return': 'tinyint unsigned', 'deterministic': 'NO',
                  'access': 'READS SQL DATA', 'security': 'DEFINER', 'args': 0}}.items())
            and all(row.get(key) == context[key] for key in ('mode', 'charset', 'collation'))
            and (row.get('body') == H4_SCHEMA_BODY or (allow_baseline and row.get('body') == H4_SCHEMA_BASELINE)),
            'MIGRATION_H4_SUPPORT_SCHEMA_CHANGED')
    return row['body']

def h4_schema_install(root_password, folder, context, journal):
    current = h4_schema_verify(root_password, context, allow_baseline=True)
    if current != H4_SCHEMA_BODY:
        journal['h4_segment'] = 'SUPPORT_SCHEMA_REBUILD'
        save(ROOT / 'active.json', journal)
        sql = ('DROP FUNCTION `nexion`.`nx_support_fund_schema_ready_v1`;\\n'
               'CREATE DEFINER=`nexion_support_schema`@`localhost` FUNCTION '
               '`nexion`.`nx_support_fund_schema_ready_v1`() RETURNS TINYINT UNSIGNED\\n'
               'READS SQL DATA SQL SECURITY DEFINER\\n' + H4_SCHEMA_CREATE_BODY + ';\\n').encode()
        execute_sql(H4_FILE + '.schema-reader', support_session_settings(context) + sql,
                    folder, 'root', root_password)
    # Always restore both grants, including a resumed call after CREATE succeeded but GRANT failed.
    journal['h4_segment'] = 'SUPPORT_SCHEMA_EXECUTE_GRANTS'
    save(ROOT / 'active.json', journal)
    for index, sql in enumerate(SUPPORT_EXECUTE_GRANTS):
        execute_sql(H4_FILE + '.schema-grant-' + str(index), sql.encode(), folder, 'root', root_password)
    h4_schema_verify(root_password, context)
    schema_grants = set(probe('root', root_password, 'SHOW GRANTS FOR ' + SUPPORT_GRANTEE + ';', as_json=False))
    expected = {{'GRANT USAGE ON *.* TO `nexion_support_schema`@`localhost`'}}
    expected.update(support_show_grant(sql) for sql in (*SUPPORT_METADATA_GRANTS, SUPPORT_EXECUTE_GRANTS[0]))
    require(schema_grants == expected, 'MIGRATION_H4_SCHEMA_GRANTS_CHANGED')
    require(support_show_grant(SUPPORT_EXECUTE_GRANTS[1]) in probe('root', root_password,
            "SHOW GRANTS FOR 'nexion_app'@'%';", as_json=False), 'MIGRATION_H4_APP_EXECUTE_GRANT_MISSING')

def h4_schema_ready(root_password):
    require(probe('root', root_password, 'SELECT nx_support_fund_schema_ready_v1();', as_json=False) == ['1'],
            'MIGRATION_H4_SUPPORT_SCHEMA_NOT_READY')

def h4_preflight(user, password, root_password):
    require(h4_enabled(), 'MIGRATION_H4_PRIVILEGED_INSTALL_NOT_APPROVED_BEFORE_HOLD')
    app = server_context(user, password)
    require(user == 'nexion_app' and app['principal'] == H4_DEFINER
            and app['version'].startswith('8.4.'), 'MIGRATION_H4_APP_CONTEXT_REJECTED')
    account_definer(app['principal'], user)
    privileges = grant_privileges(probe(user, password, 'SHOW GRANTS FOR CURRENT_USER();',
                                       as_json=False), '`nexion`.*')
    require('ALL PRIVILEGES' in privileges or {{'SELECT', 'INSERT', 'UPDATE', 'CREATE', 'TRIGGER'}} <= privileges,
            'MIGRATION_H4_APP_CAPABILITIES_MISSING')
    root = server_context('root', root_password)
    require(root['principal'].startswith('root@') and all(root[k] == app[k]
            for k in ('version', 'mode', 'charset', 'collation', 'log_bin', 'trust')),
            'MIGRATION_H4_ROOT_CONTEXT_REJECTED')
    privileges = grant_privileges(probe('root', root_password, 'SHOW GRANTS FOR CURRENT_USER();',
                                       as_json=False), '*.*')
    require(('ALL PRIVILEGES' in privileges or {{'SUPER', 'TRIGGER', 'CREATE ROUTINE', 'ALTER ROUTINE', 'EXECUTE'}} <= privileges)
            and 'SET_ANY_DEFINER' in privileges and any('WITH GRANT OPTION' in row for row in
                probe('root', root_password, 'SHOW GRANTS FOR CURRENT_USER();', as_json=False)),
            'MIGRATION_H4_ROOT_CAPABILITIES_MISSING')
    h4_schema_verify(root_password, app, allow_baseline=True)
    h4_verify(root_password)
    return app

def execute_h4_file(data, folder, user, password, root_password, context, journal):
    require(digest(data) == H4_SHA256, 'MIGRATION_H4_FIXED_SOURCE_CHANGED')
    current = h4_preflight(user, password, root_password)
    require(current == context, 'MIGRATION_H4_CONTEXT_CHANGED')
    journal['h4_segment'] = 'TABLES_AND_DISABLED_DRAFTS'
    save(ROOT / 'active.json', journal)
    execute_sql(H4_FILE + '.prefix', H4_PREFIX, folder, user, password)
    h4_schema_install(root_password, folder, context, journal)
    h4_schema_ready(root_password)
    journal['h4_segment'] = 'FIXED_TRIGGER_INSTALL'
    save(ROOT / 'active.json', journal)
    template = ('DELIMITER $$\\nCREATE DEFINER=`nexion_app`@`%` TRIGGER IF NOT EXISTS '
                '`nexion`.`nx_wallet_h4_holding_observation`\\n'
                'AFTER UPDATE ON `nexion`.`nx_user_wallet` FOR EACH ROW\\n'
                + H4_BODY + '$$\\nDELIMITER ;\\n').encode()
    if not h4_verify(root_password):
        with (folder / (H4_FILE + '.trigger.stdout')).open('xb') as out, \\
                (folder / (H4_FILE + '.trigger.stderr')).open('xb') as err:
            result = subprocess.run(mysql_args('root'),
                                    input=b'SET SESSION lock_wait_timeout=30; SET SESSION innodb_lock_wait_timeout=30;\\n' + template,
                                    stdout=out, stderr=err, timeout=180,
                                    env={{**os.environ, 'MYSQL_PWD': root_password}})
        require(result.returncode == 0, 'MIGRATION_H4_FIXED_TRIGGER_INSTALL_FAILED')
    h4_verify(root_password, required=True)
    h4_schema_ready(root_password)
    journal['h4_trigger'] = {{'name': H4_TRIGGER, 'definer': H4_DEFINER,
                             'source_sha256': H4_SHA256, 'template_sha256': digest(template),
                             'verified': True}}
    journal.pop('h4_segment', None)
    save(ROOT / 'active.json', journal)

'''


def candidate(source, sql, expected):
    checksum = hashlib.sha256(sql).hexdigest()
    if checksum != expected:
        raise ValueError('REVIEWED_SQL_HASH_MISMATCH')
    text = sql.decode('utf-8-sig')
    header = 'DELIMITER $$\nCREATE TRIGGER IF NOT EXISTS ' + TRIGGER + '\nAFTER UPDATE ON nx_user_wallet FOR EACH ROW\n'
    if text.count(header) != 1 or not text.endswith('END$$\nDELIMITER ;\n'):
        raise ValueError('REVIEWED_TRIGGER_SHAPE_MISMATCH')
    prefix, body = text.split(header)
    body = body.removesuffix('$$\nDELIMITER ;\n')
    schema_baseline, schema_body, schema_create_body = schema_bodies((Path(__file__).resolve().parents[1] / 'migrations' / SCHEMA_FILE).read_bytes())
    extra = TEMPLATE.format(filename=H4_FILE, checksum=checksum, prefix=prefix.encode(), body=body, trigger=TRIGGER,
                            schema_baseline=schema_baseline, schema_body=schema_body, schema_create_body=schema_create_body)
    edits = [
        ('def migration_preflight(pending, user, password, root_password):', extra + 'def migration_preflight(pending, user, password, root_password):'),
        ('    cregis_parts = cregis_prepare(pending)\n', '    cregis_parts = cregis_prepare(pending)\n    h4_pending = h4_prepare(pending)\n'),
        ('    scoped_account(user, password)\n    trigger_context = migration_preflight(', '    scoped_account(user, password)\n    h4_context = h4_preflight(user, password, root_password) if h4_pending else None\n    trigger_context = migration_preflight('),
        ('and (not cregis_parts or name not in CREGIS_PINS)],', 'and (not cregis_parts or name not in CREGIS_PINS) and (not h4_pending or name != H4_FILE)],'),
        ("            elif name == EARNINGS_FILE and trigger_context['root_install']:\n", "            elif h4_pending and name == H4_FILE:\n                execute_h4_file(data, folder, user, password, root_password, h4_context, journal)\n            elif name == EARNINGS_FILE and trigger_context['root_install']:\n"),
    ]
    for before, after in edits:
        if source.count(before) != 1:
            raise ValueError('LIVE_SOURCE_ANCHOR_CHANGED')
        source = source.replace(before, after, 1)
    compile(source, 'database_migrations.h4-candidate.py', 'exec')
    return source


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--sql', type=Path, required=True)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    with args.output.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(candidate(args.source.read_text(), args.sql.read_bytes(), args.sha256))
    print('CANDIDATE_PREPARED_NOT_INSTALLED')

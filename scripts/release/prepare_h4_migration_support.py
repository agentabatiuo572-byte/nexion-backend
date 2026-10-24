"""Prepare a reviewed candidate only; never installs or changes the live release gate."""
import argparse
import hashlib
from pathlib import Path

H4_FILE = "20261025_h4_event_rules.sql"
TRIGGER = "nx_wallet_h4_holding_observation"

TEMPLATE = '''
# One reviewed H4 program. Other stored programs still use the existing rejection gate.
H4_FILE = {filename!r}
H4_SHA256 = {checksum!r}
H4_PREFIX = {prefix!r}
H4_BODY = {body!r}
H4_TRIGGER = {trigger!r}
H4_DEFINER = 'nexion_app@%'
H4_METADATA_SQL = (
    "SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('name',TRIGGER_NAME,'body',ACTION_STATEMENT,"
    "'definer',DEFINER,'event',EVENT_MANIPULATION,'timing',ACTION_TIMING,"
    "'table',EVENT_OBJECT_TABLE,'orientation',ACTION_ORIENTATION)),JSON_ARRAY()) "
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
            and row.get('body', '').strip() == H4_BODY.strip(),
            'MIGRATION_H4_TRIGGER_SHAPE_CHANGED')
    return True

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
    require(('ALL PRIVILEGES' in privileges or {{'SUPER', 'TRIGGER'}} <= privileges)
            and 'SET_ANY_DEFINER' in privileges, 'MIGRATION_H4_ROOT_CAPABILITIES_MISSING')
    h4_verify(root_password)
    return app

def execute_h4_file(data, folder, user, password, root_password, context, journal):
    require(digest(data) == H4_SHA256, 'MIGRATION_H4_FIXED_SOURCE_CHANGED')
    current = h4_preflight(user, password, root_password)
    require(current == context, 'MIGRATION_H4_CONTEXT_CHANGED')
    journal['h4_segment'] = 'TABLES_AND_DISABLED_DRAFTS'
    save(ROOT / 'active.json', journal)
    execute_sql(H4_FILE + '.prefix', H4_PREFIX, folder, user, password)
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
    extra = TEMPLATE.format(filename=H4_FILE, checksum=checksum, prefix=prefix.encode(), body=body, trigger=TRIGGER)
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

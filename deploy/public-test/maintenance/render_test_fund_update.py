"""Render the explicitly scoped TEST maintenance SQL; never connects to a database.

Run generated SQL with mysql --batch --binary-mode, without --force. DDL commits
implicitly: stop the TEST backend and retain the reviewed full backup first.
This directory is outside the automatic scripts/migrations catalog.
"""
import argparse
import json
from pathlib import Path
import re
import uuid


HERE = Path(__file__).resolve().parent
SOURCE = json.loads((HERE / 'test_fund_update_source.json').read_text(encoding='utf-8'))
TEST_UUID = '123d8594-ab52-11f1-b635-f661104b171d'
FUNCTION = 'nx_support_fund_schema_ready_v1'
UPDATE_TRIGGERS = ('nx_support_fund_operation_bu_v1', 'nx_support_fund_leg_bu_v1', 'nx_support_fund_party_bu_v1')
CONJUNCTION = '\n          AND\n'
SQL_LITERAL = re.compile(r"'(?:''|[^'])*'")


def quoted(value):
    return "'" + value.replace("'", "''") + "'"


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError('FIXED_SOURCE_CLAUSE_CHANGED:' + old[:100])
    return text.replace(old, new, 1)


def metadata_body(create):
    body = create[create.index('RETURN ('):]
    # MySQL ROUTINE_DEFINITION decodes each doubled quote within SQL literals.
    return SQL_LITERAL.sub(lambda m: "'" + m[0][1:-1].replace("''", "'") + "'", body)


def absent_trigger(name):
    return "((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()\n AND TRIGGER_NAME=" + quoted(name) + ")=0)"


def trigger_matches(trigger):
    values = {'TRIGGER_NAME': trigger['name'], 'EVENT_OBJECT_TABLE': trigger['table'],
              'ACTION_TIMING': trigger['timing'], 'EVENT_MANIPULATION': trigger['event'],
              'ACTION_ORIENTATION': 'ROW', 'DEFINER': trigger['definer'],
              'ACTION_STATEMENT': trigger['statement']}
    return "((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()\n AND " + \
        '\n AND '.join('BINARY ' + key + '=BINARY ' + quoted(value) for key, value in values.items()) + \
        '\n AND ACTION_ORDER=' + str(trigger['order']) + ')=1)'


def updated_create():
    create = SOURCE['routineCreate']
    if metadata_body(create) != SOURCE['routineDefinition']:
        raise ValueError('SHOW_CREATE_AND_ROUTINE_DEFINITION_DISAGREE')
    for name in UPDATE_TRIGGERS[:2]:
        pattern = r"\(\(SELECT COUNT\(\*\) FROM information_schema\.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE\(\)\n AND TRIGGER_NAME='" + name + r"'[\s\S]*?\)=1\)"
        matches = list(re.finditer(pattern, create))
        if len(matches) != 1:
            raise ValueError('FIXED_UPDATE_TRIGGER_CLAUSE_CHANGED:' + name)
        create = create[:matches[0].start()] + absent_trigger(name) + create[matches[0].end():]
    create = replace_once(create,
        "AND EVENT_OBJECT_TABLE IN ('nx_support_fund_operation','nx_support_fund_operation_leg'))=4)",
        "AND EVENT_OBJECT_TABLE IN ('nx_support_fund_operation','nx_support_fund_operation_leg'))=2)")
    create = replace_once(create,
        "AND EVENT_OBJECT_TABLE='nx_support_fund_operation_party')=2\n AND (SELECT COUNT(*)",
        "AND EVENT_OBJECT_TABLE='nx_support_fund_operation_party')=1\n AND (SELECT COUNT(*)")
    create = replace_once(create,
        "AND ((TRIGGER_NAME='nx_support_fund_party_bu_v1' AND EVENT_MANIPULATION='UPDATE')\n OR (TRIGGER_NAME='nx_support_fund_party_bd_v1' AND EVENT_MANIPULATION='DELETE'))",
        "AND (TRIGGER_NAME='nx_support_fund_party_bd_v1' AND EVENT_MANIPULATION='DELETE')")
    create = replace_once(create,
        "BINARY 'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''SUPPORT_FUND_RECORD_IMMUTABLE''')=2)",
        "BINARY 'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''SUPPORT_FUND_RECORD_IMMUTABLE''')=1)\n          AND\n          " + absent_trigger(UPDATE_TRIGGERS[2]))
    # H4 is an already installed fifth wallet trigger, not a tolerated unknown.
    create = replace_once(create, "AND EVENT_OBJECT_TABLE='nx_user_wallet')=4\n AND (SELECT COUNT(*)",
                          "AND EVENT_OBJECT_TABLE='nx_user_wallet')=5\n AND (SELECT COUNT(*)")
    h4 = next(t for t in SOURCE['triggers'] if t['name'] == 'nx_wallet_h4_holding_observation')
    create = replace_once(create, "'nx_support_wallet_journal_ai_v1','nx_support_wallet_journal_bu_v1','nx_support_wallet_journal_au_v1'))=4)",
        "'nx_support_wallet_journal_ai_v1','nx_support_wallet_journal_bu_v1','nx_support_wallet_journal_au_v1','nx_wallet_h4_holding_observation'))=5)" +
        CONJUNCTION + '          ' + trigger_matches(h4))
    return create


def condition(create):
    return create[create.index('RETURN (') + len('RETURN ('):].rstrip()[:-1]


def preflight_condition(create):
    value = condition(create)
    for name in UPDATE_TRIGGERS:
        trigger = next(t for t in SOURCE['triggers'] if t['name'] == name)
        value = replace_once(value, absent_trigger(name), '(' + absent_trigger(name) + ' OR ' + trigger_matches(trigger) + ')')
    # Resume a partially completed DDL run only when every surviving target BU
    # is still the exact original; extra triggers continue to fail readiness.
    value = replace_once(value,
        "AND EVENT_OBJECT_TABLE IN ('nx_support_fund_operation','nx_support_fund_operation_leg'))=2)",
        "AND EVENT_OBJECT_TABLE IN ('nx_support_fund_operation','nx_support_fund_operation_leg'))=2+" +
        "(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME IN ('nx_support_fund_operation_bu_v1','nx_support_fund_leg_bu_v1')))")
    value = replace_once(value, "AND EVENT_OBJECT_TABLE='nx_support_fund_operation_party')=1\n AND (SELECT COUNT(*)",
        "AND EVENT_OBJECT_TABLE='nx_support_fund_operation_party')=1+(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME='nx_support_fund_party_bu_v1')\n AND (SELECT COUNT(*)")
    return value


def assertion(expression, reason):
    # A failed PREPARE aborts normal mysql batch input before subsequent DDL.
    return "SET @uvel_test_sql=IF(COALESCE((" + expression + "),0),'DO 0','FAIL " + reason + "');\n" + \
        'PREPARE uvel_test_stmt FROM @uvel_test_sql; EXECUTE uvel_test_stmt; DEALLOCATE PREPARE uvel_test_stmt;\n'


def target_header(clone_uuid=None, rehearsal=False):
    if rehearsal != bool(clone_uuid):
        raise ValueError('REHEARSAL_AND_CLONE_UUID_MUST_BE_SUPPLIED_TOGETHER')
    expected = TEST_UUID
    if rehearsal:
        expected = str(uuid.UUID(clone_uuid))
        if expected == TEST_UUID:
            raise ValueError('REHEARSAL_MUST_NOT_TARGET_LIVE_TEST')
    return '-- ' + ('NETWORK-ISOLATED CLONE REHEARSAL' if rehearsal else 'PERMANENT TEST ONLY') + '\n' + \
        '-- No --force: the client must stop on the first SQL error.\n' + \
        assertion('BINARY DATABASE()=BINARY \'nexion\' AND BINARY @@server_uuid=BINARY ' + quoted(expected), 'TEST_FUND_TARGET_MISMATCH') + \
        'SET SESSION sql_mode=' + quoted(SOURCE['sqlMode']) + ';\nSET NAMES ' + SOURCE['charset'] + ' COLLATE ' + SOURCE['collation'] + ';\n'


def fund_sql():
    create = updated_create()
    expected = metadata_body(create)
    old = quoted(SOURCE['routineDefinition'])
    new = quoted(expected)
    routine = "(SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE() AND ROUTINE_NAME='" + FUNCTION + "' AND ROUTINE_TYPE='FUNCTION' AND DTD_IDENTIFIER='tinyint unsigned' AND IS_DETERMINISTIC='NO' AND SQL_DATA_ACCESS='READS SQL DATA' AND SECURITY_TYPE='DEFINER' AND DEFINER='nexion_support_schema@localhost' AND (BINARY ROUTINE_DEFINITION=BINARY " + old + ' OR BINARY ROUTINE_DEFINITION=BINARY ' + new + '))=1'
    sql = assertion(routine, 'TEST_FUND_ROUTINE_SOURCE_CHANGED')
    sql += assertion(preflight_condition(create), 'TEST_FUND_UNEXPECTED_SCHEMA_DRIFT')
    sql += '\n'.join('DROP TRIGGER IF EXISTS `' + name + '`;' for name in UPDATE_TRIGGERS) + '\n'
    sql += 'DROP FUNCTION `' + FUNCTION + '`;\nDELIMITER $$\n' + create + '$$\nDELIMITER ;\n'
    for privilege in SOURCE['routinePrivileges']:
        sql += 'GRANT EXECUTE ON FUNCTION `nexion`.`' + FUNCTION + '` TO ' + quoted(privilege['user']) + '@' + quoted(privilege['host']) + ';\n'
    sql += assertion("(SELECT BINARY ROUTINE_DEFINITION=BINARY " + new + " FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE() AND ROUTINE_NAME='" + FUNCTION + "')", 'TEST_FUND_ROUTINE_POSTCHECK')
    sql += assertion(FUNCTION + '()=1', 'TEST_FUND_SCHEMA_NOT_READY')
    sql += "SELECT 'TEST_FUND_UPDATE_ALLOWED_DELETE_PROTECTED' AS result, " + FUNCTION + '() AS schema_ready;\n'
    return sql


def check_clause(check, phase):
    original = check['clause'].replace("\\'", "'")
    if original.count("_utf8mb4'NEX'") != 1:
        raise ValueError('FIXED_ASSET_CHECK_CHANGED:' + check['name'])
    target = "_utf8mb4'NEX',_utf8mb4'UVT'" if phase == 'asset-expand' else "_utf8mb4'UVT'"
    return original.replace("_utf8mb4'NEX'", target)


def asset_sql(phase):
    sql = ''
    for check in SOURCE['assetChecks']:
        before = check['clause'].replace("\\'", "'") if phase == 'asset-expand' else check_clause(check, 'asset-expand')
        after = check_clause(check, phase)
        lookup = "SELECT cc.CHECK_CLAUSE FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.CHECK_CONSTRAINTS cc ON tc.CONSTRAINT_SCHEMA=cc.CONSTRAINT_SCHEMA AND tc.CONSTRAINT_NAME=cc.CONSTRAINT_NAME WHERE tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.TABLE_NAME=" + quoted(check['table']) + ' AND tc.CONSTRAINT_NAME=' + quoted(check['name']) + " AND tc.CONSTRAINT_TYPE='CHECK' AND tc.ENFORCED='YES'"
        sql += assertion('BINARY REPLACE((' + lookup + "),CONCAT(CHAR(92),CHAR(39)),CHAR(39))=BINARY " + quoted(before), 'TEST_ASSET_CHECK_SOURCE_CHANGED')
        if phase == 'asset-finalize':
            sql += assertion('(SELECT COUNT(*) FROM `' + check['table'] + "` WHERE asset='NEX')=0", 'TEST_ASSET_NEX_ROWS_REMAIN')
        sql += 'ALTER TABLE `' + check['table'] + '` DROP CHECK `' + check['name'] + '`, ADD CONSTRAINT `' + check['name'] + '` CHECK ' + after + ' ENFORCED;\n'
    return sql


def diagnosis_sql():
    sql = ''
    for label, expression in [('ORIGINAL', condition(SOURCE['routineCreate'])), ('MAINTENANCE_PREFLIGHT', preflight_condition(updated_create()))]:
        # These are the canonical top-level guard separators; embedded trigger
        # bodies have different indentation and remain a single predicate.
        clauses = expression.strip()[len('SELECT ('):].rsplit(')', 1)[0].split(CONJUNCTION)
        for number, clause in enumerate(clauses, 1):
            sql += 'SELECT ' + quoted(label) + ' AS contract, ' + str(number) + ' AS clause_no, COALESCE((' + clause + '),0) AS ready;\n'
    return sql


def render(stage, clone_uuid=None, rehearsal=False):
    header = target_header(clone_uuid, rehearsal)
    if stage == 'fund-update':
        return header + fund_sql()
    if stage in {'asset-expand', 'asset-finalize'}:
        return header + asset_sql(stage)
    if stage == 'diagnose':
        return header + diagnosis_sql()
    raise ValueError('UNKNOWN_MAINTENANCE_STAGE')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage', choices=['diagnose', 'fund-update', 'asset-expand', 'asset-finalize'], required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--rehearsal', action='store_true')
    parser.add_argument('--clone-server-uuid')
    args = parser.parse_args()
    args.output.write_text(render(args.stage, args.clone_server_uuid, args.rehearsal), encoding='utf-8', newline='\n')
    print(json.dumps({'sqlFile': str(args.output), 'stage': args.stage, 'databaseWritePerformed': False, 'rehearsal': args.rehearsal}))


if __name__ == '__main__':
    main()

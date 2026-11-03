"""Render fixed-target TEST H4 retirement SQL; never connects or reads credentials.

Diagnose first, then render --stage retire with the reported --reader. Run with
mysql --batch --binary-mode, without --force, while the backend is stopped.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

import render_test_fund_update as fund


ROOT = Path(__file__).resolve().parents[3]
TRIGGER = 'nx_wallet_h4_holding_observation'
RETIREMENT = ROOT / 'scripts/migrations/20261104_h4_holding_retire.sql'
WALLET_COUNT = "AND EVENT_OBJECT_TABLE='nx_user_wallet')=4\n AND (SELECT COUNT(*)"


def readers():
    original = fund.SOURCE['routineCreate']
    raw = (ROOT / 'scripts/migrations/20261011_support_fund_schema_reader.sql').read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != 'e953d9df7afb43c1683289153dbcae73c5e28df14d196106df2ec663fd0fc425':
        raise ValueError('FIXED_SCHEMA_SOURCE_CHANGED')
    baseline = raw.decode().split('-- FUNCTION_BODY_BEGIN\n', 1)[1].split('-- FUNCTION_BODY_END', 1)[0].rstrip().removesuffix(';')
    if fund.metadata_body(original) != fund.metadata_body(baseline):
        raise ValueError('FIXED_SCHEMA_SNAPSHOT_CHANGED')
    candidate = fund.replace_once(original, WALLET_COUNT,
        "AND EVENT_OBJECT_TABLE='nx_user_wallet'\n"
        " AND NOT (TRIGGER_NAME='nx_wallet_h4_holding_observation'\n"
        " AND ACTION_TIMING='AFTER' AND EVENT_MANIPULATION='UPDATE' AND ACTION_ORIENTATION='ROW'\n"
        " AND DEFINER='nexion_app@%'))=4\n AND (SELECT COUNT(*)")
    maintained = fund.updated_create()
    legacy = fund.replace_once(maintained, WALLET_COUNT,
        "AND EVENT_OBJECT_TABLE='nx_user_wallet')=5\n AND (SELECT COUNT(*)")
    h4 = next(t for t in fund.SOURCE['triggers'] if t['name'] == TRIGGER)
    legacy = fund.replace_once(legacy,
        "'nx_support_wallet_journal_ai_v1','nx_support_wallet_journal_bu_v1','nx_support_wallet_journal_au_v1'))=4)",
        "'nx_support_wallet_journal_ai_v1','nx_support_wallet_journal_bu_v1','nx_support_wallet_journal_au_v1','nx_wallet_h4_holding_observation'))=5)" +
        fund.CONJUNCTION + '          ' + fund.trigger_matches(h4))
    return {'original': (original, original), 'bug001': (candidate, original),
            'fund-update': (legacy, maintained)}


def render(stage, reader=None, clone_uuid=None, rehearsal=False, clone_database=None):
    if clone_database and not rehearsal:
        raise ValueError('CLONE_DATABASE_REQUIRES_REHEARSAL')
    database = clone_database or 'nexion'
    if clone_database and not re.fullmatch(r'nexion_h4(?:_[a-z0-9_]+)?', database):
        raise ValueError('CLONE_DATABASE_REJECTED')
    header = fund.target_header(clone_uuid, rehearsal).replace(
        "BINARY DATABASE()=BINARY 'nexion'", 'BINARY DATABASE()=BINARY ' + fund.quoted(database))
    variants = readers()
    lookup = "SELECT ROUTINE_DEFINITION FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE() AND ROUTINE_NAME='" + fund.FUNCTION + "'"
    h4_count = "(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME='" + TRIGGER + "')"
    sql = header + 'SET @h4_reader_body=(' + lookup + ');\n'
    if stage == 'diagnose':
        sql += 'SELECT CASE\n'
        for name, (before, after) in variants.items():
            sql += ' WHEN BINARY @h4_reader_body=BINARY ' + fund.quoted(fund.metadata_body(before)) + ' THEN ' + fund.quoted(name) + '\n'
            if name == 'fund-update':
                sql += ' WHEN BINARY @h4_reader_body=BINARY ' + fund.quoted(fund.metadata_body(after)) + " THEN 'fund-update'\n"
        sql += " ELSE 'UNKNOWN_REFUSED' END AS reader, " + h4_count + ' AS h4_trigger_count,\n'
        sql += "(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE='nx_user_wallet') AS wallet_trigger_count;\n"
        return sql
    if stage != 'retire' or reader not in variants:
        raise ValueError('DIAGNOSE_THEN_SELECT_READER')
    before, create = variants[reader]
    expected = fund.metadata_body(create)
    # The only output differences are removal of H4 inventory predicates.
    # fund-update retains all three BU-missing predicates exactly.
    sql += fund.assertion("(SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE() AND ROUTINE_NAME='" + fund.FUNCTION +
        "' AND ROUTINE_TYPE='FUNCTION' AND DTD_IDENTIFIER='tinyint unsigned' AND IS_DETERMINISTIC='NO' AND SQL_DATA_ACCESS='READS SQL DATA' AND SECURITY_TYPE='DEFINER' AND DEFINER='nexion_support_schema@localhost'" +
        ' AND BINARY SQL_MODE=BINARY ' + fund.quoted(fund.SOURCE['sqlMode']) +
        ' AND CHARACTER_SET_CLIENT=' + fund.quoted(fund.SOURCE['charset']) +
        ' AND COLLATION_CONNECTION=' + fund.quoted(fund.SOURCE['collation']) +
        ' AND (BINARY ROUTINE_DEFINITION=BINARY ' + fund.quoted(fund.metadata_body(before)) +
        ' OR BINARY ROUTINE_DEFINITION=BINARY ' + fund.quoted(expected) + '))=1' +
        " AND (SELECT COUNT(*) FROM information_schema.PARAMETERS WHERE SPECIFIC_SCHEMA=DATABASE() AND SPECIFIC_NAME='" + fund.FUNCTION + "' AND ORDINAL_POSITION>0)=0",
        'H4_READER_SOURCE_CHANGED')
    h4 = next(t for t in fund.SOURCE['triggers'] if t['name'] == TRIGGER)
    preflight = fund.replace_once(fund.condition(create), WALLET_COUNT,
        "AND EVENT_OBJECT_TABLE='nx_user_wallet')=4+" + h4_count + '\n AND (SELECT COUNT(*)')
    sql += fund.assertion('(' + preflight + ') AND (' + h4_count + '=0 OR ' + fund.trigger_matches(h4) + ')',
                          'H4_UNEXPECTED_SCHEMA_DRIFT')
    grants = fund.SOURCE['routinePrivileges']
    privilege_scope = " FROM mysql.procs_priv WHERE Db=DATABASE() AND Routine_name='" + fund.FUNCTION + "' AND Routine_type='FUNCTION'"
    grant_conditions = ['(SELECT COUNT(*)' + privilege_scope + ')=' + str(len(grants))]
    for grant in grants:
        grant_conditions.append('(SELECT COUNT(*)' + privilege_scope + ' AND User=' + fund.quoted(grant['user']) +
            ' AND Host=' + fund.quoted(grant['host']) + " AND Proc_priv='Execute')=1")
    sql += fund.assertion(' AND '.join(grant_conditions), 'H4_EXECUTE_GRANTS_CHANGED')
    sql += RETIREMENT.read_text(encoding='utf-8') + '\n'
    sql += 'DROP FUNCTION `' + fund.FUNCTION + '`;\nDELIMITER $$\n' + create + '$$\nDELIMITER ;\n'
    for grant in grants:
        sql += 'GRANT EXECUTE ON FUNCTION `' + database + '`.`' + fund.FUNCTION + '` TO ' + fund.quoted(grant['user']) + '@' + fund.quoted(grant['host']) + ';\n'
    sql += fund.assertion(' AND '.join(grant_conditions), 'H4_EXECUTE_GRANTS_NOT_RESTORED')
    sql += fund.assertion('BINARY (' + lookup + ')=BINARY ' + fund.quoted(expected), 'H4_READER_POSTCHECK')
    sql += fund.assertion(fund.FUNCTION + '()=1', 'H4_SCHEMA_NOT_READY')
    sql += "SELECT 'H4_RETIRED' AS result, " + fund.FUNCTION + "() AS schema_ready, (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE='nx_user_wallet') AS wallet_triggers;\n"
    return sql


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage', choices=['diagnose', 'retire'], required=True)
    parser.add_argument('--reader', choices=['original', 'bug001', 'fund-update'])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--rehearsal', action='store_true')
    parser.add_argument('--clone-server-uuid')
    parser.add_argument('--clone-database')
    args = parser.parse_args()
    args.output.write_text(render(args.stage, args.reader, args.clone_server_uuid,
        args.rehearsal, args.clone_database), encoding='utf-8')
    print(json.dumps({'sqlFile': str(args.output), 'stage': args.stage, 'reader': args.reader,
                      'databaseWritePerformed': False, 'rehearsal': args.rehearsal}))


if __name__ == '__main__':
    main()

"""Real MySQL fixed-installer test against a caller-created isolated container.

The private JSON contains root/app/container for this disposable fixture only.
No Docker/service lifecycle, host configuration, business data, or network calls.
Run as the fixture owner. Source directory contains deploy/public-test and the
four unchanged scripts/migrations files. Results are isolated-test evidence.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess


WALLET = """
CREATE TABLE nx_user_wallet (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL,
 usdt_available DECIMAL(18,6) NOT NULL DEFAULT 0,
 nex_available DECIMAL(18,6) NOT NULL DEFAULT 0,
 pending_withdraw DECIMAL(18,6) NOT NULL DEFAULT 0,
 cregis_risk_held DECIMAL(18,6) NOT NULL DEFAULT 0,
 version BIGINT NOT NULL DEFAULT 0, sandbox TINYINT NOT NULL DEFAULT 0,
 is_deleted TINYINT NOT NULL DEFAULT 0, created_at DATETIME NOT NULL,
 updated_at DATETIME NOT NULL,
 earnings_usdt_debited DECIMAL(30,6) NOT NULL DEFAULT 0,
 earnings_nex_debited DECIMAL(30,6) NOT NULL DEFAULT 0
) ENGINE=InnoDB;
INSERT INTO nx_user_wallet(user_id,usdt_available,created_at,updated_at)
 VALUES(1,10,'2026-10-01 00:00:00','2026-10-01 00:00:00');
"""


def run(private_json, source_dir, output, sha):
    private = json.loads(private_json.read_text())
    root_password, app_password = private['root'], private['app']
    spec = importlib.util.spec_from_file_location('fund_install_candidate',
        source_dir / 'deploy/public-test/database_migrations.py')
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    output.mkdir(parents=True, exist_ok=False)
    m.CONTAINER = private['container']
    m.ROOT, m.BACKUPS = output / 'migrations', output / 'backups'
    m.ROOT.mkdir(); m.BACKUPS.mkdir()
    m.GUARD, m.PERMIT = output / 'fixture-guard.conf', output / 'fixture-permit'
    m.GUARD.write_text(m.GUARD_TEXT)
    m.save(m.ROOT / 'state.json', {'version': 1, 'database': 'nexion', 'scripts': {}})
    # The caller supplied a new container/database and the same default account
    # grants as TEST. This fixture never changes app/root permissions.
    m.execute_sql('fixture-wallet', WALLET.encode(), output, 'nexion_app', app_password)
    m.execute_sql('fixture-backup-content',
        b"CREATE TABLE isolated_backup_content(payload TEXT);"
        b"INSERT INTO isolated_backup_content VALUES(REPEAT('x',16000));",
        output, 'nexion_app', app_password)
    context = m.server_context('nexion_app', app_password)
    m.execute_sql('fixture-earnings', m.fixed_earnings_template(context['principal'], 'nexion_app'),
                  output, 'root', root_password)
    sources = {name: (source_dir / 'scripts/migrations' / name).read_text(encoding='utf-8').encode()
               for name in m.SUPPORT_PINS}
    # Only external dependencies are redirected: no backend process exists in
    # this container. The original apply, backup, SQL, journal, and completion run.
    m.download_catalog = lambda _sha: sources
    m.credentials = lambda: ('nexion_app', app_password, root_password)
    def stop_fixture():
        m.revoke_candidate_start()
        m.save(m.ROOT / 'START_BLOCKED', {'reason': 'ISOLATED_DATABASE_MIGRATION_HOLD'})
    m.stop_backend = stop_fixture
    m.start_previous = lambda: (m.ROOT / 'START_BLOCKED').unlink(missing_ok=True)
    receipt = m.apply(sha)
    assert receipt['phase'] == 'SQL_APPLIED'
    assert receipt['completed'] == list(m.SUPPORT_PROGRAMS)
    assert m.verify_backup(Path(receipt['backup']['path'])) == receipt['backup']
    assert (m.ROOT / 'START_BLOCKED').exists()
    def app_scalar(sql):
        return m.probe('nexion_app', app_password, sql, as_json=False)
    assert app_scalar('SELECT VERSION();') == ['8.4.11']
    assert app_scalar('SELECT nx_support_fund_schema_ready_v1();') == ['1']
    assert app_scalar("SELECT ROUTINE_DEFINITION IS NULL FROM information_schema.ROUTINES "
                      "WHERE ROUTINE_SCHEMA=DATABASE() AND ROUTINE_NAME='nx_support_fund_schema_ready_v1';") == ['1']
    assert app_scalar('SELECT usdt_available,updated_at,fund_revision FROM nx_user_wallet WHERE user_id=1;') == [
        '10.000000\t2026-10-01 00:00:00\t0']
    assert app_scalar('SELECT COUNT(*) FROM nx_support_wallet_journal;') == ['0']
    assert app_scalar("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() "
                      "AND TRIGGER_NAME LIKE 'nx_support_%';") == ['9']
    m.execute_sql('fixture-birth', b"INSERT INTO nx_user_wallet(user_id,created_at,updated_at) VALUES(2,NOW(),NOW());",
                  output, 'nexion_app', app_password)
    assert app_scalar('SELECT COUNT(*) FROM nx_support_wallet_journal;') == ['1']
    m.execute_sql('fixture-receipt', b"""
INSERT INTO nx_support_fund_operation(op_key,business_type,phase,source_table,source_key,
 source_environment,ordered_events,source_snapshot,source_proof,receipt_sha256,protocol_version)
 VALUES('isolated-test','TEST','TEST','isolated_fixture','1','PRODUCTION',JSON_ARRAY(),
 JSON_OBJECT('fixture',true),JSON_OBJECT('fixture',true),REPEAT('0',64),'isolated-test');
INSERT INTO nx_support_fund_operation_leg(operation_id,wallet_incarnation,revision)
 SELECT 1,fund_incarnation,0 FROM nx_user_wallet WHERE user_id=2;
INSERT INTO nx_support_fund_operation_party(operation_id,customer_id) VALUES(1,2);
""", output, 'nexion_app', app_password)
    for table in ('nx_support_fund_operation', 'nx_support_fund_operation_leg', 'nx_support_fund_operation_party'):
        column = 'id' if table == 'nx_support_fund_operation' else 'operation_id'
        for sql in ('UPDATE '+table+' SET '+column+'='+column+';', 'DELETE FROM '+table+';'):
            result = subprocess.run(m.mysql_args('nexion_app'), input=sql.encode(),
                capture_output=True, timeout=30, env={**os.environ, 'MYSQL_PWD': app_password})
            assert result.returncode != 0 and b'SUPPORT_FUND_RECORD_IMMUTABLE' in result.stderr
        assert app_scalar('SELECT COUNT(*) FROM '+table+';') == ['1']
    m.execute_sql('fixture-drift', b'CREATE DEFINER=`nexion_app`@`%` TRIGGER isolated_extra '
                  b'BEFORE INSERT ON nx_support_wallet_journal FOR EACH ROW SET @isolated_extra=1;',
                  output, 'root', root_password)
    assert app_scalar('SELECT nx_support_fund_schema_ready_v1();') == ['0']
    m.execute_sql('fixture-restore', b'DROP TRIGGER isolated_extra;', output, 'root', root_password)
    assert app_scalar('SELECT nx_support_fund_schema_ready_v1();') == ['1']
    m.allow_candidate_start()
    m.complete_release()
    assert m.apply(sha) is None
    assert not (m.ROOT / 'START_BLOCKED').exists() and not m.PERMIT.exists()
    result = {'status': 'PASS', 'classification': 'ISOLATED_MYSQL_FIXTURE_NOT_PUBLIC_TEST_ACCEPTANCE',
              'mysql_version': context['version'], 'source_sha': sha,
              'runner_sha256': m.digest((source_dir / 'deploy/public-test/database_migrations.py').read_bytes()),
              'source_hashes': {name: m.digest(raw) for name, raw in sources.items()},
              'order': receipt['completed'], 'real_backup_verified': True, 'journal_completion_verified': True,
              'app_has_no_routine_body_visibility': True, 'locked_definer_reader_ready': True,
              'fund_triggers': 9, 'immutable_update_delete_controls': 6, 'trigger_drift_detected': True,
              'legacy_balance_and_timestamp_preserved': True, 'no_historical_birth_backfill': True,
              'new_wallet_journal_trigger_executed': True}
    (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--private-json', type=Path, required=True)
    parser.add_argument('--source-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--sha', required=True)
    args = parser.parse_args()
    run(args.private_json.resolve(), args.source_dir.resolve(), args.output.resolve(), args.sha)

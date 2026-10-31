import hashlib
import json
import unittest
from types import SimpleNamespace
from pathlib import Path
from prepare_h4_migration_support import TEMPLATE, H4_FILE, TRIGGER, SCHEMA_FILE, candidate, schema_bodies


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


class FixedMigrationTests(unittest.TestCase):
    def setUp(self):
        self.body = 'BEGIN\n  SET @unused=1;\nEND'
        self.prefix = b'CREATE TABLE example(id INT);\n'
        self.sql = self.prefix + ('DELIMITER $$\nCREATE TRIGGER IF NOT EXISTS ' + TRIGGER
                    + '\nAFTER UPDATE ON nx_user_wallet FOR EACH ROW\n' + self.body + '$$\nDELIMITER ;\n').encode()
        self.sha = hashlib.sha256(self.sql).hexdigest()
        self.rows = []
        self.config = {}
        self.baseline, self.schema, self.create_body = schema_bodies((Path(__file__).resolve().parents[1] / 'migrations' / SCHEMA_FILE).read_bytes())
        self.ns = dict(require=require, json=json, digest=lambda b: hashlib.sha256(b).hexdigest(),
                       trusted=lambda p: None, probe=lambda *a: self.rows,
                       HOST_CONFIG=SimpleNamespace(read_bytes=lambda: json.dumps(self.config).encode()))
        exec(TEMPLATE.format(filename=H4_FILE, checksum=self.sha, prefix=self.prefix,
                             body=self.body, trigger=TRIGGER, schema_baseline=self.baseline, schema_body=self.schema, schema_create_body=self.create_body), self.ns)

    def test_only_reviewed_file_bytes_are_accepted(self):
        self.assertFalse(self.ns['h4_prepare']([('other.sql', b'CREATE TRIGGER arbitrary')]))
        self.assertTrue(self.ns['h4_prepare']([(H4_FILE, self.sql)]))
        with self.assertRaisesRegex(RuntimeError, 'SOURCE_CHANGED'):
            self.ns['h4_prepare']([(H4_FILE, self.sql + b'\n')])

    def test_separate_host_approval_defaults_off(self):
        self.assertFalse(self.ns['h4_enabled']())
        self.config['allow_fixed_h4_trigger_install'] = True
        self.assertTrue(self.ns['h4_enabled']())
        self.config['allow_fixed_h4_trigger_install'] = 'true'
        with self.assertRaises(RuntimeError):
            self.ns['h4_enabled']()

    def test_missing_trigger_is_not_verified(self):
        self.assertFalse(self.ns['h4_verify']('secret'))
        with self.assertRaisesRegex(RuntimeError, 'MISSING'):
            self.ns['h4_verify']('secret', required=True)

    def test_existing_trigger_requires_exact_body_and_app_definer(self):
        expected = dict(name=TRIGGER, body=self.body, definer='nexion_app@%',
                        event='UPDATE', timing='AFTER', table='nx_user_wallet', orientation='ROW', order=2, journal_order=1)
        self.rows.append(expected.copy())
        self.assertTrue(self.ns['h4_verify']('secret'))
        for key, value in [('body', self.body + ' -- changed'), ('definer', 'root@localhost'),
                           ('timing', 'BEFORE'), ('event', 'INSERT'), ('table', 'other'), ('orientation', 'STATEMENT'),
                           ('order', 1), ('journal_order', 2)]:
            self.rows[0] = {**expected, key: value}
            with self.assertRaisesRegex(RuntimeError, 'SHAPE_CHANGED'):
                self.ns['h4_verify']('secret')

    def test_schema_variant_changes_only_the_wallet_trigger_inventory(self):
        start = self.baseline.index("AND EVENT_OBJECT_TABLE='nx_user_wallet')=4")
        end = start + len("AND EVENT_OBJECT_TABLE='nx_user_wallet')=4")
        allowance = ("AND EVENT_OBJECT_TABLE='nx_user_wallet'\n"
                     " AND NOT (TRIGGER_NAME='nx_wallet_h4_holding_observation'\n"
                     " AND ACTION_TIMING='AFTER' AND EVENT_MANIPULATION='UPDATE' AND ACTION_ORIENTATION='ROW'\n"
                     " AND DEFINER='nexion_app@%'))=4")
        self.assertEqual(self.schema, self.baseline[:start] + allowance + self.baseline[end:])
        raw = (Path(__file__).resolve().parents[1] / 'migrations' / SCHEMA_FILE).read_text(encoding='utf-8')
        original_create = raw.split('-- FUNCTION_BODY_BEGIN\n', 1)[1].split('-- FUNCTION_BODY_END', 1)[0].rstrip()
        self.assertEqual(self.create_body, original_create.replace(self.baseline[start:end], allowance, 1))
        with self.assertRaisesRegex(ValueError, 'HASH_MISMATCH'):
            schema_bodies(b'changed')

    def test_candidate_compiles_against_current_release_gate_without_changing_its_pins(self):
        source = (Path(__file__).resolve().parents[2] / 'deploy/public-test/database_migrations.py').read_text(encoding='utf-8')
        prepared = candidate(source, self.sql, self.sha)
        compile(prepared, 'candidate.py', 'exec')
        self.assertIn("h4_context = h4_preflight(user, password, root_password) if h4_pending else None", prepared)
        self.assertIn("execute_h4_file(data, folder, user, password, root_password, h4_context, journal)", prepared)
        self.assertIn("'wallet_triggers': 1", prepared)  # Funds must be installed in an earlier batch.

    def schema_row(self, body):
        return dict(name='nx_support_fund_schema_ready_v1', body=body, definer='nexion_support_schema@localhost',
                    mode='STRICT_TRANS_TABLES', charset='utf8mb4', collation='utf8mb4_0900_ai_ci', type='FUNCTION',
                    **{'return': 'tinyint unsigned'}, deterministic='NO', access='READS SQL DATA', security='DEFINER', args=0)

    def test_schema_first_install_and_unknown_shapes_fail_closed(self):
        self.ns['SUPPORT_SCHEMA'] = 'nexion_support_schema@localhost'
        context = dict(mode='STRICT_TRANS_TABLES', charset='utf8mb4', collation='utf8mb4_0900_ai_ci')
        with self.assertRaisesRegex(RuntimeError, 'FIRST_INSTALL_REQUIRED'):
            self.ns['h4_schema_verify']('', context, True)
        self.rows[:] = [self.schema_row(self.baseline)]
        self.assertEqual(self.ns['h4_schema_verify']('', context, True), self.baseline)
        with self.assertRaisesRegex(RuntimeError, 'SCHEMA_CHANGED'):
            self.ns['h4_schema_verify']('', context)
        for field, value in [('body', self.schema + ' '), ('definer', 'root@localhost'), ('args', 1)]:
            self.rows[:] = [{**self.schema_row(self.schema), field: value}]
            with self.assertRaisesRegex(RuntimeError, 'SCHEMA_CHANGED'):
                self.ns['h4_schema_verify']('', context)

    def test_resumed_install_restores_both_execute_grants_even_when_function_already_changed(self):
        calls = []
        self.ns.update(SUPPORT_SCHEMA='nexion_support_schema@localhost',
                       SUPPORT_EXECUTE_GRANTS=('grant-schema;', 'grant-app;'),
                       SUPPORT_METADATA_GRANTS=(), SUPPORT_GRANTEE="'nexion_support_schema'@'localhost'",
                       support_show_grant=lambda sql: sql,
                       ROOT=Path('unused'), save=lambda *a: None,
                       execute_sql=lambda name, sql, *args: calls.append(sql))
        self.rows[:] = [self.schema_row(self.schema)]
        self.ns['probe'] = lambda *args, **kwargs: (
            ['GRANT USAGE ON *.* TO `nexion_support_schema`@`localhost`', 'grant-schema;']
            if 'SHOW GRANTS FOR \'nexion_support_schema\'' in args[2]
            else ['grant-app;'] if 'SHOW GRANTS' in args[2] else self.rows)
        context = dict(mode='STRICT_TRANS_TABLES', charset='utf8mb4', collation='utf8mb4_0900_ai_ci')
        self.ns['h4_schema_install']('', Path('unused'), context, {})
        self.assertEqual(calls, [b'grant-schema;', b'grant-app;'])

    def test_preparation_refuses_drift_or_unreviewed_sql(self):
        with self.assertRaisesRegex(ValueError, 'HASH_MISMATCH'):
            candidate('unused', self.sql + b'\n', self.sha)
        with self.assertRaisesRegex(ValueError, 'ANCHOR_CHANGED'):
            candidate('unexpected host source', self.sql, self.sha)


if __name__ == '__main__':
    unittest.main()

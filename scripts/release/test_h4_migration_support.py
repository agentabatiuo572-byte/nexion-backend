import hashlib
import json
import unittest
from types import SimpleNamespace
from prepare_h4_migration_support import TEMPLATE, H4_FILE, TRIGGER, candidate


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
        self.ns = dict(require=require, json=json, digest=lambda b: hashlib.sha256(b).hexdigest(),
                       trusted=lambda p: None, probe=lambda *a: self.rows,
                       HOST_CONFIG=SimpleNamespace(read_bytes=lambda: json.dumps(self.config).encode()))
        exec(TEMPLATE.format(filename=H4_FILE, checksum=self.sha, prefix=self.prefix,
                             body=self.body, trigger=TRIGGER), self.ns)

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
                        event='UPDATE', timing='AFTER', table='nx_user_wallet', orientation='ROW')
        self.rows.append(expected.copy())
        self.assertTrue(self.ns['h4_verify']('secret'))
        for key, value in [('body', self.body + ' -- changed'), ('definer', 'root@localhost'),
                           ('timing', 'BEFORE'), ('event', 'INSERT'), ('table', 'other'), ('orientation', 'STATEMENT')]:
            self.rows[0] = {**expected, key: value}
            with self.assertRaisesRegex(RuntimeError, 'SHAPE_CHANGED'):
                self.ns['h4_verify']('secret')

    def test_preparation_refuses_drift_or_unreviewed_sql(self):
        with self.assertRaisesRegex(ValueError, 'HASH_MISMATCH'):
            candidate('unused', self.sql + b'\n', self.sha)
        with self.assertRaisesRegex(ValueError, 'ANCHOR_CHANGED'):
            candidate('unexpected host source', self.sql, self.sha)


if __name__ == '__main__':
    unittest.main()

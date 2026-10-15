"""Execute the forward repair against the observed metadata split in memory.

SQLite executes the portable UPDATE statements; only START TRANSACTION becomes
BEGIN. This is a local SQL regression, not evidence of a MySQL server run.
"""
import sqlite3
import unittest
from pathlib import Path

MIGRATION = Path(__file__).parent / 'migrations/20261016_g1_g7_event_property_revision_forward.sql'
CONTRACTS = {
    'staking.opened': (221, 62, 99, [
        ('position_no', 'id'), ('tier_key', 'string'), ('product_code', 'id'),
        ('amount_usdt', 'number'), ('apy_pct', 'number'), ('term_days', 'number'),
        ('unlock_at', 'timestamp'), ('wallet_balance_usdt', 'number')]),
    'wallet.reinvest': (224, 72, 98, [
        ('position_no', 'id'), ('product', 'enum'), ('amount_usdt', 'number'),
        ('apy_pct', 'number'), ('term_days', 'number'), ('unlock_at', 'timestamp'),
        ('ticket_count', 'number'), ('nurture_multiplier', 'number'),
        ('wallet_balance_usdt', 'number')]),
}


class EventPropertyRevisionTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.executescript('''
            CREATE TABLE nx_event_schema_registry(id INTEGER PRIMARY KEY,event_name TEXT,
              current_revision INTEGER,status TEXT,is_deleted INTEGER);
            CREATE TABLE nx_event_schema_property(schema_id INTEGER,property_name TEXT,
              property_type TEXT,pii INTEGER,required_field INTEGER,registry_revision INTEGER,
              is_deleted INTEGER,updated_at TEXT DEFAULT '2026-07-22',
              PRIMARY KEY(schema_id,property_name));
        ''')
        for event, (sid, old, current, fields) in CONTRACTS.items():
            self.db.execute('INSERT INTO nx_event_schema_registry VALUES(?,?,?,?,0)',
                            (sid, event, current, 'ACTIVE'))
            for field, kind in fields:
                self.db.execute('INSERT INTO nx_event_schema_property VALUES(?,?,?,0,1,?,0,?)',
                                (sid, field, kind, old, '2026-07-22'))
            for field in ('h1_reinvest_multiplier', 'effective_nurture_multiplier'):
                self.db.execute('INSERT INTO nx_event_schema_property VALUES(?,?,?,0,?,?,0,?)',
                                (sid, field, 'number', int(event == 'wallet.reinvest'), current, '2026-07-22'))
        self.db.commit()

    def apply(self):
        sql = MIGRATION.read_text(encoding='utf-8').replace('START TRANSACTION;', 'BEGIN;')
        self.db.executescript(sql)

    def visible(self, sid):
        return self.db.execute('''SELECT p.property_name FROM nx_event_schema_property p
            JOIN nx_event_schema_registry s ON s.id=p.schema_id
            WHERE s.id=? AND s.status='ACTIVE' AND s.is_deleted=0 AND p.is_deleted=0
              AND p.registry_revision=s.current_revision ORDER BY p.property_name''', (sid,)).fetchall()

    def test_observed_split_becomes_complete_and_replay_is_noop(self):
        for sid, old, current, fields in CONTRACTS.values():
            self.assertEqual(len(self.visible(sid)), 2)
        before = self.db.execute('SELECT schema_id,property_name,property_type,pii,required_field,is_deleted FROM nx_event_schema_property').fetchall()
        self.apply()
        for sid, old, current, fields in CONTRACTS.values():
            self.assertEqual({r[0] for r in self.visible(sid)},
                             {f for f, _ in fields} | {'h1_reinvest_multiplier', 'effective_nurture_multiplier'})
        self.assertEqual(before, self.db.execute('SELECT schema_id,property_name,property_type,pii,required_field,is_deleted FROM nx_event_schema_property').fetchall())
        repaired = self.db.execute('SELECT * FROM nx_event_schema_property').fetchall()
        self.apply()
        self.assertEqual(repaired, self.db.execute('SELECT * FROM nx_event_schema_property').fetchall())

    def test_governed_deleted_changed_or_future_properties_are_preserved(self):
        protected = []
        for sid, old, current, fields in CONTRACTS.values():
            changes = [('is_deleted', 1, fields[0][0]), ('property_type', 'json', fields[1][0]),
                       ('required_field', 0, fields[2][0]), ('pii', 1, fields[3][0]),
                       ('registry_revision', current + 1, fields[4][0])]
            for column, value, field in changes:
                self.db.execute(f'UPDATE nx_event_schema_property SET {column}=? WHERE schema_id=? AND property_name=?', (value, sid, field))
                protected.append((sid, field))
            self.db.execute('INSERT INTO nx_event_schema_property VALUES(?,?,?,0,0,?,0,?)',
                            (sid, 'unrelated_legacy', 'string', old, '2026-07-22'))
            protected.append((sid, 'unrelated_legacy'))
        self.db.commit()
        before = [self.db.execute('SELECT * FROM nx_event_schema_property WHERE schema_id=? AND property_name=?', key).fetchone() for key in protected]
        self.apply()
        self.assertEqual(before, [self.db.execute('SELECT * FROM nx_event_schema_property WHERE schema_id=? AND property_name=?', key).fetchone() for key in protected])

    def test_newer_inactive_or_deleted_schemas_are_not_changed(self):
        for column, value in [('current_revision', 20261017), ('status', 'RETIRED'), ('is_deleted', 1)]:
            with self.subTest(column=column):
                self.db.execute(f'UPDATE nx_event_schema_registry SET {column}=?', (value,))
                self.db.commit()
                before = self.db.execute('SELECT * FROM nx_event_schema_property').fetchall()
                self.apply()
                self.assertEqual(before, self.db.execute('SELECT * FROM nx_event_schema_property').fetchall())
                for sid, old, current, fields in CONTRACTS.values():
                    self.db.execute("UPDATE nx_event_schema_registry SET current_revision=?,status='ACTIVE',is_deleted=0 WHERE id=?", (current, sid))
                self.db.commit()


if __name__ == '__main__':
    unittest.main()

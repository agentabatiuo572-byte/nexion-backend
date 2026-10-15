"""Run production staking mapper SQL in SQLite memory, not a MySQL acceptance.

Only MyBatis parameters and FOR UPDATE are adapted; NOW is a fixture function.
The companion FundsHistorySnapshotMySqlTest covers actual MySQL row operations.
"""
import re
import sqlite3
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1] / 'src/main/java/ffdd/opsconsole/market/mapper/AppStakingMapper.java'


def statements():
    pattern = r'@(Select|Update)\((""".*?"""|"[^"\n]*")\)\s+[\w<>]+\s+(\w+)\('
    result = {}
    for _, literal, name in re.findall(pattern, SOURCE.read_text(encoding='utf-8'), re.S):
        sql = literal[3:-3] if literal.startswith('"""') else literal[1:-1]
        result[name] = re.sub(r'#\{(\w+)\}', r':\1', sql).replace('FOR UPDATE', '')
    return result


class StakingProductScopeTest(unittest.TestCase):
    def setUp(self):
        self.sql = statements()
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.row_factory = sqlite3.Row
        self.db.create_function('NOW', 0, lambda: '2026-10-16 00:00:00')
        self.db.executescript('''
            CREATE TABLE nx_staking_position(id INTEGER PRIMARY KEY,user_id INTEGER,position_no TEXT,
              product_id INTEGER,product_code TEXT,product_name TEXT,amount_usdt NUMERIC,
              apy_bps NUMERIC,early_penalty_bps NUMERIC,term_days INTEGER,locked_at TEXT,unlock_at TEXT,
              estimated_interest_usdt NUMERIC,status TEXT,claimed_at TEXT,early_withdrawn_at TEXT,
              is_deleted INTEGER,created_at TEXT,updated_at TEXT);
            CREATE TABLE nx_staking_product(id INTEGER,product_code TEXT,name TEXT,asset TEXT,
              term_days INTEGER,apy_bps NUMERIC,early_penalty_bps NUMERIC,min_amount NUMERIC,
              status TEXT,is_deleted INTEGER);
        ''')
        for index, code in enumerate(['USDT_30D', 'USDT_90D', 'USDT_180D', 'usdt_365d',
                                      'REPURCHASE_90D', 'USDT_30D_LEGACY', 'USDT_45D'], 1):
            self.db.execute('INSERT INTO nx_staking_product VALUES (?,?,?,\'USDT\',30,1000,500,20,\'ACTIVE\',0)',
                            (index, code, code))
            self.add(index, code)
        self.add(35, 'USDT_30D', status='EARLY_WITHDRAWN', amount=20)
        self.add(36, 'REPURCHASE_90D', amount=100)
        self.add(37, 'USDT_30D', user=2)
        self.add(38, 'USDT_30D', deleted=1)

    def add(self, identity, code, user=1, deleted=0, status='ACTIVE', amount=20):
        self.db.execute('''INSERT INTO nx_staking_position VALUES (?,?,?,1,?,?,?,1000,500,30,
            '2026-09-01','2026-10-01',1,?,NULL,NULL,?,'2026-09-01',NULL)''',
            (identity, user, 'STK-' + str(identity), code, code, amount, status, deleted))

    def run_sql(self, name, **values):
        return self.db.execute(self.sql[name], dict(userId=1, now='2026-10-16', **values))

    def test_mixed_history_pagination_totals_and_summary_source(self):
        # Deleted G1 IDs retain the original high-water contract; G7 IDs do not set it.
        self.add(100, 'REPURCHASE_90D')
        self.assertEqual(38, self.run_sql('maxIssuedHistoryId').fetchone()[0])
        self.assertEqual(5, self.run_sql('countUserPositions').fetchone()[0])
        self.assertEqual(5, self.run_sql('countUserPositionsAt', snapshotId=38).fetchone()[0])
        first = self.run_sql('listUserPositionsAt', snapshotId=38, offset=0, limit=2).fetchall()
        self.add(101, 'USDT_30D')
        second = self.run_sql('listUserPositionsAt', snapshotId=38, offset=2, limit=10).fetchall()
        self.assertEqual([35, 4, 3, 2, 1], [r['id'] for r in first + second])
        self.assertEqual('EARLY_WITHDRAWN', first[0]['status'])
        self.assertEqual(80, sum(r['amountUsdt'] for r in first + second if r['status'] == 'ACTIVE'))
        self.assertEqual(5, self.run_sql('countUserPositionsAt', snapshotId=38).fetchone()[0])
        self.assertEqual(6, self.run_sql('countUserPositions').fetchone()[0])
        self.assertEqual(4, len(self.run_sql('listCanonicalProducts').fetchall()))

    def test_product_and_position_locks_exclude_other_domains_and_owners(self):
        for code in ('repurchase90d', 'usdt45d', 'usdt30dlegacy'):
            self.assertIsNone(self.run_sql('lockProductByTier', tierKey=code).fetchone())
        self.assertIsNotNone(self.run_sql('lockProductByTier', tierKey='usdt30d').fetchone())
        for identity in (5, 6, 7, 36, 37, 38):
            self.assertIsNone(self.run_sql('lockUserPosition', positionNo='STK-' + str(identity)).fetchone())
        self.assertIsNotNone(self.run_sql('lockUserPosition', positionNo='STK-35').fetchone())

    def test_maturity_and_claim_cas_never_mutate_repurchase_or_noncanonical_positions(self):
        self.assertEqual(4, self.run_sql('matureDuePositions').rowcount)
        for identity in (5, 6, 7, 36, 37, 38):
            self.assertEqual(0, self.run_sql('markClaimed', id=identity).rowcount)
        self.assertEqual(1, self.run_sql('markClaimed', id=1).rowcount)
        self.assertEqual(0, self.run_sql('markClaimed', id=1).rowcount)
        self.assertEqual('ACTIVE', self.db.execute('SELECT status FROM nx_staking_position WHERE id=36').fetchone()[0])

    def test_early_exit_cas_is_scoped_and_preserves_state_checks(self):
        self.db.execute("UPDATE nx_staking_position SET unlock_at='2027-01-01'")
        for identity in (5, 6, 7, 36, 37, 38, 35):
            self.assertEqual(0, self.run_sql('markEarlyWithdrawn', id=identity).rowcount)
        self.assertEqual(1, self.run_sql('markEarlyWithdrawn', id=2).rowcount)
        self.assertEqual(0, self.run_sql('markEarlyWithdrawn', id=2).rowcount)
        self.assertEqual('ACTIVE', self.db.execute('SELECT status FROM nx_staking_position WHERE id=36').fetchone()[0])


if __name__ == '__main__':
    unittest.main(verbosity=2)

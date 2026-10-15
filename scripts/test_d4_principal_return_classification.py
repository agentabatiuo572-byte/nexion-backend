"""Execute both production D4 CASE expressions in SQLite memory (not MySQL).

The existing MyBatis contract test separately renders complete count/page SQL
and checks identical user, business-number, date and status predicates.
"""
import re
import sqlite3
import unittest
from pathlib import Path

MAPPER = Path(__file__).resolve().parents[1] / 'src/main/java/ffdd/opsconsole/treasury/mapper/TreasuryLedgerMapper.java'
CASES = [
    ('STAKING_EARLY_WITHDRAW', 'IN', 19, 'refund'),
    ('REPURCHASE_EARLY_WITHDRAW', 'IN', 85, 'refund'),
    (' staking_early_withdraw ', ' in ', 4.5, 'refund'),
    ('STAKING_EARLY_WITHDRAW', 'OUT', 2, 'withdraw'),
    ('REPURCHASE_EARLY_WITHDRAW', 'CREDIT', 3, 'withdraw'),
    ('REPURCHASE_EARLY_WITHDRAW', None, 3, 'withdraw'),
    ('REPURCHASE_EARLY_WITHDRAW', '', 3, 'withdraw'),
    ('WITHDRAWAL', 'OUT', 50, 'withdraw'),
    ('PAYOUT', 'OUT', 20, 'withdraw'),
    ('WITHDRAW_FEE_OFFSET_REFUND', 'IN', 5, 'refund'),
    ('CHARGEBACK_RECOVERY', 'IN', 6, 'refund'),
    ('STAKING_EARLY_WITHDRAW_REVERSAL', 'OUT', 7, 'refund'),
    ('STAKING_EARLY_WITHDRAW_EXTRA', 'IN', 8, 'withdraw'),
    ('OTHER_REPURCHASE_EARLY_WITHDRAW', 'IN', 9, 'withdraw'),
    ('DEPOSIT', 'IN', 10, 'topup'),
    ('COMPUTE_TASK_REWARD', 'IN', 11, 'earning'),
    ('DAILY_CHECK_IN', 'IN', 12, 'bonus'),
    ('ORDER_PURCHASE', 'OUT', 13, 'purchase'),
    (None, 'IN', 1, 'earning'),
]


class PrincipalReturnClassificationTest(unittest.TestCase):
    def setUp(self):
        self.expressions = re.findall(r'AND \((CASE.*?END)\) = #\{type\}', MAPPER.read_text(encoding='utf-8'), re.S)
        self.assertEqual(2, len(self.expressions))
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.execute('CREATE TABLE ledger(id INTEGER PRIMARY KEY,biz_type TEXT,direction TEXT,amount NUMERIC)')
        self.db.executemany('INSERT INTO ledger VALUES (?,?,?,?)',
                            [(i, *row[:3]) for i, row in enumerate(CASES, 1)])

    def test_count_and_page_classification_of_exact_types_directions_and_existing_categories(self):
        for index, expression in enumerate(self.expressions):
            actual = self.db.execute(f'SELECT {expression} FROM ledger l ORDER BY id').fetchall()
            self.assertEqual([row[3] for row in CASES], [row[0] for row in actual], f'production CASE {index}')

    def test_filter_counts_pages_and_signed_totals_follow_the_same_refund_scope(self):
        for expression in self.expressions:
            for category in ('refund', 'withdraw'):
                expected = [i for i, row in enumerate(CASES, 1) if row[3] == category]
                actual = self.db.execute(f'SELECT id FROM ledger l WHERE ({expression})=? ORDER BY id', (category,)).fetchall()
                self.assertEqual(expected, [row[0] for row in actual])
                count = self.db.execute(f'SELECT COUNT(*) FROM ledger l WHERE ({expression})=?', (category,)).fetchone()[0]
                self.assertEqual(len(expected), count)
                page = self.db.execute(f'SELECT id FROM ledger l WHERE ({expression})=? ORDER BY id LIMIT 2 OFFSET 1', (category,)).fetchall()
                self.assertEqual(expected[1:3], [row[0] for row in page])
            refund_sum = self.db.execute(f"SELECT SUM(CASE WHEN UPPER(TRIM(direction))='IN' THEN amount ELSE -amount END) FROM ledger l WHERE ({expression})='refund'").fetchone()[0]
            self.assertEqual(112.5, refund_sum)


if __name__ == '__main__':
    unittest.main(verbosity=2)

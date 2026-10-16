"""Exercise the actual F3 assignment SELECT statements in isolated SQLite memory."""
import re
import sqlite3
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1] / "src/main/java/ffdd/opsconsole/team/mapper/BinaryCommissionSettlementMapper.java"


def statements():
    source = SOURCE.read_text(encoding="utf-8")
    scope = re.search(r'ASSIGNMENT_READ_SCOPE = """(.*?)""";', source, re.S).group(1)
    result = {}
    for expression, name in re.findall(r'@Select\((.*?)\)\s+(?:long|List<AssignmentMemberRow>)\s+(countAssignmentMembers|listAssignmentMembers)\(', source, re.S):
        # Only literal Java strings plus the common scope; execute the production SQL.
        expression = expression[expression.rfind("@Select(") + 8:] if "@Select(" in expression else expression
        chunks = re.findall(r'"""(.*?)"""|"([^"\n]*)"|(ASSIGNMENT_READ_SCOPE)', expression, re.S)
        sql = "".join(block or literal or scope for block, literal, common in chunks)
        result[name] = re.sub(r'#\{(\w+)\}', r':\1', sql)
    return result


class AssignmentReadSqlTest(unittest.TestCase):
    def setUp(self):
        self.sql = statements()
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
            CREATE TABLE nx_user(id INTEGER PRIMARY KEY,status TEXT,is_deleted INTEGER);
            CREATE TABLE nx_team_member(id INTEGER PRIMARY KEY,user_id INTEGER,member_user_id INTEGER,level INTEGER,is_deleted INTEGER);
            CREATE TABLE nx_binary_leg_assignment(id INTEGER PRIMARY KEY,owner_user_id INTEGER,member_user_id INTEGER,leg TEXT,assigned_by TEXT,assigned_at TEXT,UNIQUE(owner_user_id,member_user_id));
            INSERT INTO nx_user VALUES (1,'ACTIVE',0),(2,'ACTIVE',0),(3,'ACTIVE',0),(4,'ACTIVE',0),(5,'ACTIVE',0),(6,'ACTIVE',0),(7,'INACTIVE',0),(8,'ACTIVE',1),(9,'ACTIVE',0),(10,'ACTIVE',0);
            INSERT INTO nx_team_member VALUES (1,1,2,1,0),(2,1,3,1,0),(3,1,4,1,0),(4,1,2,1,0),(5,1,5,2,0),(6,1,6,1,1),(7,1,7,1,0),(8,1,8,1,0),(9,10,9,1,0);
            INSERT INTO nx_binary_leg_assignment VALUES (1,1,2,'A','operator','2026-10-16 00:00:00'),(2,1,3,'B','SYSTEM_AUTO_PLACEMENT','2026-10-16 00:01:00'),(3,10,4,'A','other','2026-10-16 00:02:00');
        """)

    def query(self, status="ALL", member=None, owner=1, offset=0, limit=20):
        args = dict(ownerUserId=owner, memberUserId=member, status=status, offset=offset, limit=limit)
        total = self.db.execute(self.sql["countAssignmentMembers"], args).fetchone()[0]
        rows = self.db.execute(self.sql["listAssignmentMembers"], args).fetchall()
        return total, [dict(row) for row in rows]

    def test_count_page_same_scope_and_no_duplicate_team_links(self):
        before = self.db.total_changes
        count, first = self.query(limit=2)
        _, second = self.query(offset=2, limit=2)
        self.assertEqual(3, count)
        self.assertEqual([2, 3, 4], [r["memberUserId"] for r in first + second])
        self.assertEqual(before, self.db.total_changes)

    def test_assignment_origin_and_unassigned_do_not_borrow_another_owner(self):
        total, assigned = self.query("ASSIGNED")
        self.assertEqual(2, total)
        self.assertEqual(["MANUAL", "AUTO"], [r["assignmentSource"] for r in assigned])
        self.assertEqual(["A", "B"], [r["leg"] for r in assigned])
        total, pending = self.query("UNASSIGNED")
        self.assertEqual(1, total)
        self.assertEqual(4, pending[0]["memberUserId"])
        for name in ["leg", "assignedBy", "assignedAt", "assignmentSource"]:
            self.assertIsNone(pending[0][name])

    def test_exact_member_recovery_and_other_owner_isolation(self):
        self.assertEqual((0, []), self.query(member=9))
        self.assertEqual(2, self.query(member=2)[1][0]["memberUserId"])
        self.assertEqual((0, []), self.query("UNASSIGNED", member=2))
        self.assertEqual(9, self.query(owner=10)[1][0]["memberUserId"])
        self.assertEqual((0, []), self.query(member=999))

    def test_inactive_deleted_owner_and_relationship_changes_are_excluded(self):
        self.db.execute("UPDATE nx_user SET status='INACTIVE' WHERE id=1")
        self.assertEqual((0, []), self.query())
        self.db.execute("UPDATE nx_user SET status='ACTIVE',is_deleted=1 WHERE id=1")
        self.assertEqual((0, []), self.query())
        self.db.execute("UPDATE nx_user SET is_deleted=0 WHERE id=1")
        self.db.execute("UPDATE nx_team_member SET is_deleted=1 WHERE user_id=1 AND member_user_id=2")
        self.assertEqual((0, []), self.query(member=2))


if __name__ == "__main__":
    unittest.main()

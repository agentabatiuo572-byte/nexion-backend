"""Execute the frozen weekly mapper's read predicates in SQLite; not MySQL lock proof."""
import pathlib
import re
import sqlite3
import unittest

SOURCE = (pathlib.Path(__file__).resolve().parents[1] / "src/main/java/ffdd/opsconsole/growth/mapper/WeeklyQuestSnapshotMapper.java").read_text(encoding="utf-8")


def query(method):
    matches = re.findall(r'@Select\("""(.*?)"""\)\s+[\w<>, ?]+\s+(\w+)\(', SOURCE, re.S)
    sql = dict((name, sql) for sql, name in matches)[method]
    sql = re.sub(r"#\{(\w+)\}", r":\1", sql)
    return sql.replace("FOR SHARE", "").replace("FOR UPDATE", "")


class WeeklySnapshotReads(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
          CREATE TABLE nx_growth_weekly_definition(instance_key TEXT,eligible_from TEXT,eligible_until TEXT,created_at TEXT);
          CREATE TABLE nx_growth_weekly_definition_item(instance_key TEXT,source_mission_id INTEGER,quest_code TEXT,layer TEXT,reward_points INTEGER);
          CREATE TABLE nx_growth_weekly_definition_binding(instance_key TEXT,source_mission_id INTEGER,source_binding_id INTEGER,binding_code TEXT,producer TEXT,event_type TEXT,user_id_field TEXT);
          CREATE TABLE nx_mission(id INTEGER,mission_code TEXT,mission_type TEXT,status INTEGER,reward_points INTEGER,is_deleted INTEGER DEFAULT 0);
          CREATE TABLE nx_user_mission(user_id INTEGER,mission_id INTEGER,instance_key TEXT,mission_status TEXT,is_deleted INTEGER,created_at TEXT);
          INSERT INTO nx_growth_weekly_definition VALUES('WEEK:2026-W42','2026-10-12','2026-10-19','2026-10-16');
          INSERT INTO nx_growth_weekly_definition_item VALUES('WEEK:2026-W42',1,'browse','WEEKLY_T2',50);
          INSERT INTO nx_growth_weekly_definition_binding VALUES('WEEK:2026-W42',1,11,'original','SYSTEM','BROWSE','user_id');
          INSERT INTO nx_mission VALUES(1,'browse','WEEKLY_T2',0,999,0),(2,'legacy','WEEKLY_T2',0,10,0),(3,'new','WEEKLY_T2',1,900,0);
          INSERT INTO nx_user_mission VALUES(42,1,'WEEK:2026-W42','CLAIMABLE',0,'2026-10-15');
        """)

    def rows(self, method, **params):
        return [dict(row) for row in self.db.execute(query(method), params)]

    def test_paused_live_definition_does_not_change_frozen_reward_or_binding(self):
        reward = self.rows("lockReward", key="WEEK:2026-W42", code="browse", userId=42)
        self.assertEqual(50, reward[0]["rewardNex"])
        binding = self.rows("bindings", key="WEEK:2026-W42", eventType="BROWSE")
        self.assertEqual("original", binding[0]["bindingCode"])
        self.assertEqual([], self.rows("bindings", key="WEEK:2026-W43", eventType="BROWSE"))

    def test_claim_scope_and_half_open_event_window(self):
        self.assertEqual([], self.rows("lockReward", key="WEEK:2026-W42", code="browse", userId=43))
        self.assertEqual([], self.rows("lockReward", key="WEEK:2026-W41", code="browse", userId=42))
        self.assertEqual(1, len(self.rows("mission", key="WEEK:2026-W42", code="browse", occurredAt="2026-10-12")))
        self.assertEqual([], self.rows("mission", key="WEEK:2026-W42", code="browse", occurredAt="2026-10-19"))
        self.db.execute("UPDATE nx_user_mission SET mission_status='CLAIMED'")
        self.assertEqual([], self.rows("lockReward", key="WEEK:2026-W42", code="browse", userId=42))

    def test_legacy_only_existing_completed_progress_not_new_missing_members(self):
        self.db.executescript("""
          INSERT INTO nx_user_mission VALUES(42,2,'WEEK:2026-W42','CLAIMABLE',0,'2026-10-15');
          INSERT INTO nx_user_mission VALUES(42,3,'WEEK:2026-W42','CLAIMABLE',0,'2026-10-17');
        """)
        self.assertEqual([{"mission_code":"legacy"}], self.rows("legacyProgressCodes", key="WEEK:2026-W42", userId=42))
        self.assertEqual([{"mission_id":2}], self.rows("legacyClaimMission",key="WEEK:2026-W42",userId=42,code="legacy"))
        self.db.execute("UPDATE nx_user_mission SET instance_key='WEEK:2026-W41' WHERE mission_id=2")
        self.assertEqual([], self.rows("legacyClaimMission",key="WEEK:2026-W42",userId=42,code="legacy"))
        self.db.execute("UPDATE nx_user_mission SET mission_status='PENDING' WHERE mission_id=2")
        self.assertEqual([], self.rows("legacyProgressCodes", key="WEEK:2026-W42", userId=42))


if __name__ == "__main__":
    unittest.main()

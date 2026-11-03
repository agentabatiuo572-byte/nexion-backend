-- H3 weekly quest name: "commission" -> "team reward". 20260722_h3_canonical_event_listener.sql
-- seeded this English name; no localized name exists, so the App shows it in every language.
-- Rerunnable; rewrites the row only while it still carries the byte-exact seeded name (BINARY
-- compare: the column collation ignores case and punctuation width), so an
-- operator rename is kept. Weekly snapshots already taken keep the name they were issued with.
SET NAMES utf8mb4;

UPDATE nx_mission
   SET mission_name = 'Unlock a team reward',
       updated_at = NOW()
 WHERE mission_code = 'H3_COMMISSION_UNLOCKED'
   AND is_deleted = 0
   AND CAST(mission_name AS BINARY) = CAST('Unlock a commission reward' AS BINARY);

-- Approved calendar version: growth/team business boundaries from 2026-10-19 use
-- Asia/Ho_Chi_Minh. DATETIME storage and transport remain Asia/Shanghai (+08).
-- Apply with the new application before 2026-10-19 00:00 +08, after preflight:
-- W42 must exist with from=2026-10-12 00:00; W43 must not already be published.
-- Capture W42 items/bindings/user progress and ledger readbacks before and after.
-- Re-running updates neither a completed transition nor any reward/claim.
SET NAMES utf8mb4;
START TRANSACTION;
INSERT INTO nx_admin_operation_mutex(lock_key,updated_at)
VALUES('H3_CONFIG',NOW()) ON DUPLICATE KEY UPDATE updated_at=updated_at;
SELECT lock_key FROM nx_admin_operation_mutex WHERE lock_key='H3_CONFIG' FOR UPDATE;
UPDATE nx_growth_weekly_definition
   SET eligible_until='2026-10-19 01:00:00'
 WHERE instance_key='WEEK:2026-W42'
   AND eligible_from='2026-10-12 00:00:00'
   AND eligible_until='2026-10-19 00:00:00';
SELECT ROW_COUNT() AS transitioned_week_headers;
-- Business configuration must go through the normal PC/A2 flow, not this SQL:
-- F3 team.ui.F.binary.gvResetCron = 每月 1 日 00:00 Asia/Ho_Chi_Minh
-- F4 team.ui.F.pool.settleCron = 0 0 0 * * MON (or an approved time after closure).
-- Existing F4 configVersion/maker-checker audit must be preserved by that flow.
SELECT instance_key,eligible_from,eligible_until,quest_bonus_multiplier,rhythm_month,required_task_count
  FROM nx_growth_weekly_definition WHERE instance_key IN ('WEEK:2026-W42','WEEK:2026-W43');
SELECT config_key,config_value FROM nx_config_item
 WHERE config_key IN ('team.ui.F.binary.gvResetCron','team.ui.F.pool.settleCron','team.ui.F.pool.configVersion')
   AND status=1 AND is_deleted=0;
COMMIT;

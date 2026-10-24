-- TEST: user-authorized V0-V8 ladder and H9 display configuration.
-- Run with abort-on-error and preserve an external before-image backup first.
-- Existing V9-V12 accounts require an explicit migration decision; never silently downgrade them.
START TRANSACTION;
CREATE TEMPORARY TABLE uvel_v8_preflight (higher_members BIGINT CHECK (higher_members = 0));
INSERT INTO uvel_v8_preflight
SELECT COUNT(*) FROM nx_team_member m JOIN nx_user u ON u.id = m.member_user_id AND u.is_deleted = 0
 WHERE m.is_deleted = 0 AND UPPER(TRIM(m.v_rank)) IN ('V9','V10','V11','V12');

UPDATE nx_v_rank_config SET status = 0, updated_at = NOW()
 WHERE rank_code IN ('V9','V10','V11','V12') AND is_deleted = 0 AND status = 1;

-- First installation only: never overwrite later operator edits or restart an enabled counter.
UPDATE nx_config_item
 SET config_value = JSON_SET(config_value,
       '$.homeOnlineCurrent', 28432, '$.homeOnlineStepMin', 1, '$.homeOnlineStepMax', 3,
       '$.homePaidCurrent', 130532, '$.homePaidStepMin', 1.10, '$.homePaidStepMax', 5.50,
       '$.homeDisplayEnabled', CAST('false' AS JSON),
       '$.homeDisplayAnchorAt', CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)),
     updated_at = NOW()
 WHERE config_key = 'growth.public_stats.values' AND is_deleted = 0 AND status = 1
   AND JSON_CONTAINS_PATH(config_value, 'one', '$.homeDisplayEnabled') = 0;
SET @home_display_installed = ROW_COUNT();
UPDATE nx_config_item SET config_value = CAST(config_value AS UNSIGNED) + 1, updated_at = NOW()
 WHERE config_key = 'growth.public_stats.version' AND is_deleted = 0 AND status = 1
   AND @home_display_installed > 0;
COMMIT;
DROP TEMPORARY TABLE uvel_v8_preflight;

SELECT rank_code, status FROM nx_v_rank_config WHERE is_deleted = 0 ORDER BY sort_order;
SELECT config_key, config_value FROM nx_config_item
 WHERE config_key IN ('growth.public_stats.values','growth.public_stats.version') AND is_deleted = 0;

-- Only a baseline that began with an empty schema gets these business calendar defaults.
-- When storage uses +07, preserve the instants of the earlier +08 seed writes.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

START TRANSACTION;

SET @lc2_tz_session_minutes = TIMESTAMPDIFF(MINUTE, UTC_TIMESTAMP(), NOW());
SET @lc2_tz_seed_begin = NULL;
SELECT DATE_ADD(created_at, INTERVAL (480 - @lc2_tz_session_minutes) MINUTE)
  INTO @lc2_tz_seed_begin
  FROM nx_schema_migration_state
 WHERE migration_key = '20261121_lc2_storage_timezone' AND phase = 'FRESH'
 FOR UPDATE;
SET @lc2_tz_fresh = @lc2_tz_seed_begin IS NOT NULL;
SET @lc2_tz_minutes = 480 - @lc2_tz_session_minutes;
SET @lc2_tz_shift = @lc2_tz_fresh AND @lc2_tz_session_minutes = 420;

UPDATE nx_business_calendar
   SET zone_id = 'Asia/Ho_Chi_Minh', country_code = 'VN', offset_minutes = 420,
       reason = '首装默认按越南时间切分'
 WHERE @lc2_tz_fresh AND track = 'LEGACY' AND effective_date = '1970-01-01'
   AND operator = 'migration:r1' AND zone_id = 'Asia/Shanghai' AND is_deleted = 0;

-- The historical switch instant was the earlier midnight in Shanghai.
UPDATE nx_business_calendar
   SET effective_storage_at = DATE_SUB(effective_storage_at, INTERVAL @lc2_tz_minutes MINUTE)
 WHERE @lc2_tz_shift AND track = 'GROWTH_TEAM' AND effective_date = '2026-10-19'
   AND operator = 'migration:r1' AND effective_storage_at = '2026-10-19 00:00:00'
   AND zone_id = 'Asia/Ho_Chi_Minh' AND is_deleted = 0;

UPDATE nx_config_item
   SET config_value = '每月 1 日 00:00 Asia/Ho_Chi_Minh', updated_at = updated_at
 WHERE @lc2_tz_fresh AND config_key = 'team.ui.F.binary.gvResetCron'
   AND config_value = '每月 1 日 00:00 UTC' AND is_deleted = 0;
UPDATE nx_config_item
   SET config_value = '每日 00:00 Asia/Ho_Chi_Minh 越南时间自动推进', updated_at = updated_at
 WHERE @lc2_tz_fresh AND config_key = 'wallet.nex_market.control.schedule'
   AND config_value = '每日 00:00 Asia/Ho_Chi_Minh 自动推进' AND is_deleted = 0;

UPDATE nx_growth_weekly_participation_rollout
   SET effective_at = DATE_SUB(effective_at, INTERVAL @lc2_tz_minutes MINUTE),
       created_at = DATE_SUB(created_at, INTERVAL @lc2_tz_minutes MINUTE),
       updated_at = DATE_SUB(updated_at, INTERVAL @lc2_tz_minutes MINUTE)
 WHERE @lc2_tz_shift AND id = 1;
UPDATE nx_growth_weekly_exchange_referral_rollout
   SET effective_at = DATE_SUB(effective_at, INTERVAL @lc2_tz_minutes MINUTE),
       created_at = DATE_SUB(created_at, INTERVAL @lc2_tz_minutes MINUTE),
       updated_at = DATE_SUB(updated_at, INTERVAL @lc2_tz_minutes MINUTE)
 WHERE @lc2_tz_shift AND id = 1;
UPDATE nx_business_notification_control
   SET baseline_at = DATE_SUB(baseline_at, INTERVAL @lc2_tz_minutes MINUTE)
 WHERE @lc2_tz_shift AND id = 1;
UPDATE nx_native_push_cursor
   SET updated_at = DATE_SUB(updated_at, INTERVAL @lc2_tz_minutes MINUTE)
 WHERE @lc2_tz_shift AND id = 1;

-- All related policy versions must retain their relative ordering and microsecond precision.
SET @lc2_tz_pool_at = (SELECT effective_at FROM nx_sim_task_pool_cutover WHERE id = 1 AND @lc2_tz_shift);
SET @lc2_tz_phone_at = (SELECT MIN(effective_at) FROM nx_sim_task_type_version
                        WHERE @lc2_tz_shift AND version = 1 AND pool_code = 'PHONE'
                          AND task_type_id LIKE 'PHONE-LAUNCH-%');
SET @lc2_tz_catalog_at = (SELECT MIN(effective_at) FROM nx_sim_task_type_version
                          WHERE @lc2_tz_shift AND version = 1 AND task_type_id LIKE 'RULE-EN-%'
                            AND effective_at >= @lc2_tz_seed_begin);
SET @lc2_tz_class_at = (SELECT MAX(effective_at) FROM nx_sim_product_task_policy_version
                        WHERE @lc2_tz_shift AND allowed_pool_codes IS NULL AND allowed_task_classes IS NOT NULL
                          AND effective_at >= @lc2_tz_seed_begin);

UPDATE nx_sim_task_pool_cutover
   SET effective_at = DATE_SUB(effective_at, INTERVAL @lc2_tz_minutes MINUTE)
 WHERE @lc2_tz_shift AND id = 1;
UPDATE nx_sim_task_type_version
   SET effective_at = DATE_SUB(effective_at, INTERVAL @lc2_tz_minutes MINUTE)
 WHERE @lc2_tz_shift AND effective_at IN (@lc2_tz_pool_at, @lc2_tz_phone_at, @lc2_tz_catalog_at);
UPDATE nx_sim_product_task_policy_version
   SET effective_at = DATE_SUB(effective_at, INTERVAL @lc2_tz_minutes MINUTE)
 WHERE @lc2_tz_shift AND effective_at IN (@lc2_tz_pool_at, @lc2_tz_catalog_at, @lc2_tz_class_at);
UPDATE nx_sim_e3_version
   SET effective_at = DATE_SUB(effective_at, INTERVAL @lc2_tz_minutes MINUTE)
 WHERE @lc2_tz_shift AND version = 1 AND effective_at = @lc2_tz_phone_at;

UPDATE nx_admin_device_task
   SET created_at = DATE_SUB(created_at, INTERVAL @lc2_tz_minutes MINUTE), updated_at = updated_at
 WHERE @lc2_tz_shift AND (
       (task_id LIKE 'PHONE-LAUNCH-%' AND created_at = CAST(@lc2_tz_phone_at AS DATETIME)) OR
       (task_id LIKE 'RULE-EN-%' AND created_at = CAST(@lc2_tz_catalog_at AS DATETIME)));
UPDATE nx_admin_device_task
   SET updated_at = DATE_SUB(updated_at, INTERVAL @lc2_tz_minutes MINUTE)
 WHERE @lc2_tz_shift
   AND updated_at IN (CAST(@lc2_tz_phone_at AS DATETIME), CAST(@lc2_tz_catalog_at AS DATETIME));
UPDATE nx_product
   SET updated_at = DATE_SUB(updated_at, INTERVAL @lc2_tz_minutes MINUTE)
 WHERE @lc2_tz_shift
   AND updated_at IN (CAST(@lc2_tz_catalog_at AS DATETIME), CAST(@lc2_tz_class_at AS DATETIME));

UPDATE nx_schema_migration_state
   SET phase = 'FINAL'
 WHERE @lc2_tz_fresh AND migration_key = '20261121_lc2_storage_timezone' AND phase = 'FRESH';

COMMIT;

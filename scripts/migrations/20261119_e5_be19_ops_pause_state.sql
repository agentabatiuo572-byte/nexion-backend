-- E5-BE-19: operator pause gets its own state columns on the device runtime row,
-- so telemetry pauses (PHONE_OFFLINE / PHONE_LOW_BATTERY) and business pauses
-- (refund, deactivate, trade-in...) no longer collide with operator pause/resume.
-- Idempotent for old and new databases.

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device_runtime'
                 AND COLUMN_NAME = 'ops_paused') = 0,
  'ALTER TABLE nx_user_device_runtime ADD COLUMN ops_paused TINYINT NOT NULL DEFAULT 0 AFTER paused_reason',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device_runtime'
                 AND COLUMN_NAME = 'ops_pause_reason') = 0,
  'ALTER TABLE nx_user_device_runtime ADD COLUMN ops_pause_reason VARCHAR(128) NULL AFTER ops_paused',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
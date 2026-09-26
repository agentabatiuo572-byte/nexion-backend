-- Phone task active time excludes low-battery, offline, and missing-heartbeat intervals.
-- Rollback after reverting application readers: drop paused_seconds and paused_at.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_task'
                  AND COLUMN_NAME = 'paused_at') = 0,
  'ALTER TABLE nx_compute_task ADD COLUMN paused_at DATETIME NULL AFTER proof_consumed_at', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_task'
                  AND COLUMN_NAME = 'paused_seconds') = 0,
  'ALTER TABLE nx_compute_task ADD COLUMN paused_seconds BIGINT NOT NULL DEFAULT 0 AFTER paused_at', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

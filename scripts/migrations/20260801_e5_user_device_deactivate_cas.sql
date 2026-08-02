-- E5 App owner deactivation uses an explicit optimistic version for stale-write rejection.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device'
                  AND COLUMN_NAME = 'row_version') = 0,
  'ALTER TABLE nx_user_device ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0 AFTER pending_deactivate',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

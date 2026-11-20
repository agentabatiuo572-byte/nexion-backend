-- C5-BE-05 forward: persist the lock kind captured at trigger time so unlock and
-- statistics use the stored kind instead of re-deriving it from the current threshold.
-- Legacy rows keep lock_kind=NULL; Java treats NULL/empty as UNKNOWN and counts it as LONG.

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_login_guard' AND COLUMN_NAME='lock_kind')=0,
  'ALTER TABLE nx_user_login_guard ADD COLUMN lock_kind VARCHAR(16) NULL AFTER failed_count',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

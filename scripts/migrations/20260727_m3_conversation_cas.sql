-- M3 conversation header CAS revision. Safe to rerun.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'nx_conversation'
      AND COLUMN_NAME = 'version') = 0,
  'ALTER TABLE nx_conversation ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER last_message_at',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

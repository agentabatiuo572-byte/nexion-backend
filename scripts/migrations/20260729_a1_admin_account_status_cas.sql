-- A-002: account status commands must have a monotonic, atomic CAS token.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin' AND COLUMN_NAME = 'version') = 0,
  'ALTER TABLE nx_admin ADD COLUMN version BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

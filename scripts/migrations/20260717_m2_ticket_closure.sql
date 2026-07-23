-- M2 ticket closure: reversible archive state independent from resolved/closed.
SET @schema_name = DATABASE();

SET @add_ticket_archived = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE nx_support_ticket ADD COLUMN archived TINYINT NOT NULL DEFAULT 0 AFTER closed_at',
    'SELECT 1')
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'nx_support_ticket' AND COLUMN_NAME = 'archived');
PREPARE stmt FROM @add_ticket_archived; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_ticket_archived_at = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE nx_support_ticket ADD COLUMN archived_at DATETIME NULL AFTER archived',
    'SELECT 1')
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'nx_support_ticket' AND COLUMN_NAME = 'archived_at');
PREPARE stmt FROM @add_ticket_archived_at; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_ticket_archive_index = (
  SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_support_ticket_archive ON nx_support_ticket (archived, archived_at, status)',
    'SELECT 1')
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'nx_support_ticket' AND INDEX_NAME = 'idx_support_ticket_archive');
PREPARE stmt FROM @add_ticket_archive_index; EXECUTE stmt; DEALLOCATE PREPARE stmt;

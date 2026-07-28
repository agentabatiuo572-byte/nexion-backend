-- M2 authoritative ticket CAS. Every write compares the user's visible status/version.
SET @schema_name = DATABASE();

SET @add_ticket_version = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE nx_support_ticket ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER archived_at',
    'SELECT 1')
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = @schema_name
   AND TABLE_NAME = 'nx_support_ticket'
   AND COLUMN_NAME = 'version');
PREPARE stmt FROM @add_ticket_version;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

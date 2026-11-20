-- H3 mission content lifecycle: only rows created after this migration can prove never opened.
-- Existing rows and rows inserted outside the canonical create path stay UNKNOWN and are never
-- treated as drafts. The Java create path writes DRAFT; a successful paused->active transition
-- writes OPENED. This is not inferred from the current paused status.

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'nx_mission'
      AND COLUMN_NAME = 'content_state') = 0,
  'ALTER TABLE nx_mission ADD COLUMN content_state VARCHAR(16) NOT NULL DEFAULT ''UNKNOWN'' AFTER status',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE nx_mission
   SET content_state = 'UNKNOWN'
 WHERE content_state IS NULL OR TRIM(content_state) = '';

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'nx_mission'
      AND COLUMN_NAME = 'content_state'
      AND (IS_NULLABLE <> 'NO' OR COLUMN_DEFAULT <> 'UNKNOWN' OR CHARACTER_MAXIMUM_LENGTH <> 16)) > 0,
  'ALTER TABLE nx_mission MODIFY COLUMN content_state VARCHAR(16) NOT NULL DEFAULT ''UNKNOWN''',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

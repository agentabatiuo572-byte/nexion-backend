-- H4 PC CMS -> App canonical event projection.
SET @h4_has_cta_href = (
  SELECT COUNT(*)
    FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'nx_event_quest'
     AND COLUMN_NAME = 'cta_href'
);
SET @h4_add_cta_href = IF(
  @h4_has_cta_href = 0,
  'ALTER TABLE nx_event_quest ADD COLUMN cta_href VARCHAR(255) NOT NULL DEFAULT '''' AFTER geo_scope',
  'SELECT 1'
);
PREPARE h4_stmt FROM @h4_add_cta_href;
EXECUTE h4_stmt;
DEALLOCATE PREPARE h4_stmt;

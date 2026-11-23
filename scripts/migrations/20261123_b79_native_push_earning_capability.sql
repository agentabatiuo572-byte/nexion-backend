-- B79-BE-06: new packages report their IANA zone and capabilities. Missing or
-- malformed values stay ineligible without changing the binding generation.
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_native_push_installation' AND COLUMN_NAME='time_zone')=0,
  'ALTER TABLE nx_native_push_installation ADD COLUMN time_zone VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_native_push_installation' AND COLUMN_NAME='capabilities')=0,
  'ALTER TABLE nx_native_push_installation ADD COLUMN capabilities VARCHAR(2048) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''[]''',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

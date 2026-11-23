-- B79-BE-05: account-scoped earning push cadence. Missing/legacy rows mean DAILY.
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_user_preference' AND COLUMN_NAME='earning_push')=0,
  'ALTER TABLE nx_user_preference ADD COLUMN earning_push VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''DAILY'' AFTER notify_system',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

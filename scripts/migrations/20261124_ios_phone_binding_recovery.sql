-- Existing bindings can enroll a recovery key the next time their bound iPhone connects.
SET @ios_phone_recovery_column = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_phone_binding' AND COLUMN_NAME = 'recovery_key_hash'
);
SET @ios_phone_recovery_sql = IF(@ios_phone_recovery_column = 0,
  'ALTER TABLE nx_phone_binding ADD COLUMN recovery_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL',
  'DO 0');
PREPARE ios_phone_recovery_stmt FROM @ios_phone_recovery_sql;
EXECUTE ios_phone_recovery_stmt;
DEALLOCATE PREPARE ios_phone_recovery_stmt;

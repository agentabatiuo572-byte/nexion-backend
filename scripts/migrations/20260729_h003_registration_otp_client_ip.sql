-- H-003: historical nx_user_registration_otp tables were created before
-- client_ip became a K1 rate-limit input. Keep existing rows explicit as
-- unknown, then require every new OTP write to provide an address.
SET @has_client_ip := (
  SELECT COUNT(*)
    FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'nx_user_registration_otp'
     AND COLUMN_NAME = 'client_ip'
);
SET @sql := IF(@has_client_ip = 0,
  'ALTER TABLE nx_user_registration_otp ADD COLUMN client_ip VARCHAR(64) NOT NULL DEFAULT ''unknown'' AFTER phone',
  'SELECT 1');
PREPARE h003_stmt FROM @sql;
EXECUTE h003_stmt;
DEALLOCATE PREPARE h003_stmt;

-- The default only backfills legacy rows; runtime inserts always bind client_ip.
ALTER TABLE nx_user_registration_otp MODIFY COLUMN client_ip VARCHAR(64) NOT NULL;

SET @has_client_ip_index := (
  SELECT COUNT(*)
    FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'nx_user_registration_otp'
     AND INDEX_NAME = 'idx_user_registration_otp_ip'
);
SET @sql := IF(@has_client_ip_index = 0,
  'ALTER TABLE nx_user_registration_otp ADD INDEX idx_user_registration_otp_ip (client_ip,created_at)',
  'SELECT 1');
PREPARE h003_index_stmt FROM @sql;
EXECUTE h003_index_stmt;
DEALLOCATE PREPARE h003_index_stmt;

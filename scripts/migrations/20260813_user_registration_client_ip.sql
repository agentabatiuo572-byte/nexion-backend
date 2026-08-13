-- MyBatis-Plus selects every mapped UserEntity column during registration and login.
-- Older databases predate nx_user.client_ip, so add and backfill it before the
-- application serves authentication traffic. Runtime registration always writes
-- the authoritative OTP client IP; legacy rows remain explicitly "unknown".
SET @has_user_client_ip := (
  SELECT COUNT(*)
    FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'nx_user'
     AND COLUMN_NAME = 'client_ip'
);
SET @sql := IF(
  @has_user_client_ip = 0,
  'ALTER TABLE nx_user ADD COLUMN client_ip VARCHAR(64) NOT NULL DEFAULT ''unknown'' AFTER phone',
  'SELECT 1'
);
PREPARE user_registration_client_ip_stmt FROM @sql;
EXECUTE user_registration_client_ip_stmt;
DEALLOCATE PREPARE user_registration_client_ip_stmt;

-- Remove the temporary upgrade default so every new identity must supply its
-- registration-derived client IP instead of silently weakening K1 rate limits.
ALTER TABLE nx_user MODIFY COLUMN client_ip VARCHAR(64) NOT NULL;

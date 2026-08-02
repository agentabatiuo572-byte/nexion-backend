-- Expired PROCESSING is an unknown result after a crash, not a retryable command.
-- Serialize DDL across same-database candidate instances, then verify the index
-- before allowing a FORCE INDEX recovery query to run.
SET @idempotency_expiry_recovery_lock_name = CONCAT(
    'nx:idemexp:', LEFT(SHA2(COALESCE(DATABASE(), ''), 256), 32)
);
SET @idempotency_expiry_recovery_lock_name_length = CHAR_LENGTH(@idempotency_expiry_recovery_lock_name);
SELECT GET_LOCK(@idempotency_expiry_recovery_lock_name, 30)
  INTO @idempotency_expiry_recovery_lock;
SET @idempotency_expiry_recovery_lock_guard_sql = IF(
    @idempotency_expiry_recovery_lock = 1,
    'SELECT 1',
    'FAIL IDEMPOTENCY_EXPIRY_RECOVERY_LOCK_NOT_ACQUIRED'
);
PREPARE idempotency_expiry_recovery_lock_guard_stmt
   FROM @idempotency_expiry_recovery_lock_guard_sql;
EXECUTE idempotency_expiry_recovery_lock_guard_stmt;
DEALLOCATE PREPARE idempotency_expiry_recovery_lock_guard_stmt;
SET @idempotency_expiry_recovery_index_exists = EXISTS(
    SELECT 1
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'nx_admin_idempotency_record'
       AND INDEX_NAME = 'idx_admin_idem_status_expires_deleted'
     GROUP BY INDEX_NAME
    HAVING GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'status,expires_at,is_deleted'
);
SET @idempotency_expiry_recovery_index_sql = IF(
    @idempotency_expiry_recovery_lock = 1
    AND @idempotency_expiry_recovery_index_exists = 0,
    'ALTER TABLE nx_admin_idempotency_record ADD INDEX idx_admin_idem_status_expires_deleted (status, expires_at, is_deleted)',
    'SELECT 1'
);
PREPARE idempotency_expiry_recovery_index_stmt FROM @idempotency_expiry_recovery_index_sql;
EXECUTE idempotency_expiry_recovery_index_stmt;
DEALLOCATE PREPARE idempotency_expiry_recovery_index_stmt;
SELECT RELEASE_LOCK(@idempotency_expiry_recovery_lock_name);

SET @idempotency_expiry_recovery_index_verified = EXISTS(
    SELECT 1
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'nx_admin_idempotency_record'
       AND INDEX_NAME = 'idx_admin_idem_status_expires_deleted'
     GROUP BY INDEX_NAME
    HAVING GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'status,expires_at,is_deleted'
);
SET @idempotency_expiry_recovery_index_guard_sql = IF(
    @idempotency_expiry_recovery_index_verified = 1,
    'SELECT 1',
    'FAIL IDEMPOTENCY_EXPIRY_RECOVERY_INDEX_MISSING'
);
PREPARE idempotency_expiry_recovery_index_guard_stmt
   FROM @idempotency_expiry_recovery_index_guard_sql;
EXECUTE idempotency_expiry_recovery_index_guard_stmt;
DEALLOCATE PREPARE idempotency_expiry_recovery_index_guard_stmt;
SELECT @idempotency_expiry_recovery_index_verified
  AS admin_idempotency_expiry_recovery_index_verified;

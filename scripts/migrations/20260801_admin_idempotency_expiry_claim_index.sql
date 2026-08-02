-- Scheduler recovery claims rows in the exact predicate/order used by the
-- non-blocking FOR UPDATE SKIP LOCKED query.  Keep this independent from the
-- older compatibility index so existing request-path plans remain stable.
SET @idempotency_expiry_claim_lock_name = CONCAT(
    'nx:idemclaim:', LEFT(SHA2(COALESCE(DATABASE(), ''), 256), 30)
);
SELECT GET_LOCK(@idempotency_expiry_claim_lock_name, 30)
  INTO @idempotency_expiry_claim_lock;
SET @idempotency_expiry_claim_lock_guard_sql = IF(
    @idempotency_expiry_claim_lock = 1,
    'SELECT 1',
    'FAIL IDEMPOTENCY_EXPIRY_CLAIM_LOCK_NOT_ACQUIRED'
);
PREPARE idempotency_expiry_claim_lock_guard_stmt
   FROM @idempotency_expiry_claim_lock_guard_sql;
EXECUTE idempotency_expiry_claim_lock_guard_stmt;
DEALLOCATE PREPARE idempotency_expiry_claim_lock_guard_stmt;
SET @idempotency_expiry_claim_index_exists = EXISTS(
    SELECT 1
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'nx_admin_idempotency_record'
       AND INDEX_NAME = 'idx_admin_idem_expiry_claim'
     GROUP BY INDEX_NAME
    HAVING GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'status,is_deleted,expires_at,id'
);
SET @idempotency_expiry_claim_index_sql = IF(
    @idempotency_expiry_claim_lock = 1
    AND @idempotency_expiry_claim_index_exists = 0,
    'ALTER TABLE nx_admin_idempotency_record ADD INDEX idx_admin_idem_expiry_claim (status, is_deleted, expires_at, id)',
    'SELECT 1'
);
PREPARE idempotency_expiry_claim_index_stmt FROM @idempotency_expiry_claim_index_sql;
EXECUTE idempotency_expiry_claim_index_stmt;
DEALLOCATE PREPARE idempotency_expiry_claim_index_stmt;
SELECT RELEASE_LOCK(@idempotency_expiry_claim_lock_name);

SET @idempotency_expiry_claim_index_verified = EXISTS(
    SELECT 1
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'nx_admin_idempotency_record'
       AND INDEX_NAME = 'idx_admin_idem_expiry_claim'
     GROUP BY INDEX_NAME
    HAVING GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'status,is_deleted,expires_at,id'
);
SET @idempotency_expiry_claim_index_guard_sql = IF(
    @idempotency_expiry_claim_index_verified = 1,
    'SELECT 1',
    'FAIL IDEMPOTENCY_EXPIRY_CLAIM_INDEX_MISSING'
);
PREPARE idempotency_expiry_claim_index_guard_stmt
   FROM @idempotency_expiry_claim_index_guard_sql;
EXECUTE idempotency_expiry_claim_index_guard_stmt;
DEALLOCATE PREPARE idempotency_expiry_claim_index_guard_stmt;
SELECT @idempotency_expiry_claim_index_verified
  AS admin_idempotency_expiry_claim_index_verified;

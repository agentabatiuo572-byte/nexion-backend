-- A1 admin MFA binding state. Apply before enabling the MFA login flow.
SET @schema_name = DATABASE();

SET @add_secret = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE nx_admin_account_state ADD COLUMN tfa_secret_encrypted VARCHAR(1024) NULL AFTER tfa_required',
        'SELECT 1')
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name
       AND TABLE_NAME = 'nx_admin_account_state'
       AND COLUMN_NAME = 'tfa_secret_encrypted'
);
PREPARE stmt FROM @add_secret;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- A1 security contract: login lock is enforced, and account lifecycle is disable-only.
UPDATE nx_admin_security_baseline
   SET baseline_value = '5 times / 15min + 15 times / 24h',
       locked = 1,
       description = '5 failures lock 15min; 15 failures per 24h lock 24h'
 WHERE baseline_key = 'lock'
   AND is_deleted = 0;

UPDATE nx_admin_permission
   SET status = 0,
       is_deleted = 1,
       updated_at = NOW()
 WHERE permission_code = 'platform_a1_account_delete';

SET @add_bound = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE nx_admin_account_state ADD COLUMN tfa_bound_at DATETIME NULL AFTER tfa_secret_encrypted',
        'SELECT 1')
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name
       AND TABLE_NAME = 'nx_admin_account_state'
       AND COLUMN_NAME = 'tfa_bound_at'
);
PREPARE stmt FROM @add_bound;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

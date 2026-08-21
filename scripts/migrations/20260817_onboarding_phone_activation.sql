-- Phone onboarding completion is separate from phone compute activation.
-- CALIBRATED never grants task/reward eligibility; ACTIVE is only an onboarding
-- binding fact and device settlement still requires nx_user_device to be active.
SET @onboarding_add_user_device_id = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_onboarding_calibration' AND COLUMN_NAME='user_device_id')=0,
  'ALTER TABLE nx_onboarding_calibration ADD COLUMN user_device_id BIGINT NULL AFTER device_id',
  'SELECT 1');
PREPARE onboarding_activation_stmt FROM @onboarding_add_user_device_id; EXECUTE onboarding_activation_stmt; DEALLOCATE PREPARE onboarding_activation_stmt;

SET @onboarding_add_activation_status = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_onboarding_calibration' AND COLUMN_NAME='activation_status')=0,
  'ALTER TABLE nx_onboarding_calibration ADD COLUMN activation_status VARCHAR(16) NOT NULL DEFAULT ''CALIBRATED'' AFTER request_hash',
  'SELECT 1');
PREPARE onboarding_activation_stmt FROM @onboarding_add_activation_status; EXECUTE onboarding_activation_stmt; DEALLOCATE PREPARE onboarding_activation_stmt;

SET @onboarding_add_activation_key = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_onboarding_calibration' AND COLUMN_NAME='activation_idempotency_key')=0,
  'ALTER TABLE nx_onboarding_calibration ADD COLUMN activation_idempotency_key VARCHAR(128) NULL AFTER activation_status',
  'SELECT 1');
PREPARE onboarding_activation_stmt FROM @onboarding_add_activation_key; EXECUTE onboarding_activation_stmt; DEALLOCATE PREPARE onboarding_activation_stmt;

SET @onboarding_add_activation_hash = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_onboarding_calibration' AND COLUMN_NAME='activation_request_hash')=0,
  'ALTER TABLE nx_onboarding_calibration ADD COLUMN activation_request_hash CHAR(64) NULL AFTER activation_idempotency_key',
  'SELECT 1');
PREPARE onboarding_activation_stmt FROM @onboarding_add_activation_hash; EXECUTE onboarding_activation_stmt; DEALLOCATE PREPARE onboarding_activation_stmt;

SET @onboarding_add_user_device_index = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_onboarding_calibration' AND INDEX_NAME='idx_onboarding_calibration_user_device')=0,
  'ALTER TABLE nx_onboarding_calibration ADD INDEX idx_onboarding_calibration_user_device (user_device_id,activation_status)',
  'SELECT 1');
PREPARE onboarding_activation_stmt FROM @onboarding_add_user_device_index; EXECUTE onboarding_activation_stmt; DEALLOCATE PREPARE onboarding_activation_stmt;

UPDATE nx_onboarding_calibration
   SET activation_status='CALIBRATED'
 WHERE activation_status IS NULL OR activation_status NOT IN ('CALIBRATED','ACTIVE','DEFERRED');

SET @onboarding_add_activation_check = IF(
  (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_onboarding_calibration' AND CONSTRAINT_NAME='chk_onboarding_calibration_activation')=0,
  'ALTER TABLE nx_onboarding_calibration ADD CONSTRAINT chk_onboarding_calibration_activation CHECK (activation_status IN (''CALIBRATED'',''ACTIVE'',''DEFERRED''))',
  'SELECT 1');
PREPARE onboarding_activation_stmt FROM @onboarding_add_activation_check; EXECUTE onboarding_activation_stmt; DEALLOCATE PREPARE onboarding_activation_stmt;

-- The canonical inventory row must carry the same physical environment and
-- acceptance RunID as its calibration authority.  User/device hashes alone
-- are logical separation; these columns make accidental cross-rail reads
-- impossible to express without an explicit bad predicate.
SET @onboarding_add_device_environment = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_user_device' AND COLUMN_NAME='source_environment')=0,
  'ALTER TABLE nx_user_device ADD COLUMN source_environment VARCHAR(16) NOT NULL DEFAULT ''PRODUCTION'' AFTER source_channel',
  'SELECT 1');
PREPARE onboarding_activation_stmt FROM @onboarding_add_device_environment; EXECUTE onboarding_activation_stmt; DEALLOCATE PREPARE onboarding_activation_stmt;

SET @onboarding_add_device_run_id = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_user_device' AND COLUMN_NAME='run_id')=0,
  'ALTER TABLE nx_user_device ADD COLUMN run_id VARCHAR(96) NOT NULL DEFAULT '''' AFTER source_environment',
  'SELECT 1');
PREPARE onboarding_activation_stmt FROM @onboarding_add_device_run_id; EXECUTE onboarding_activation_stmt; DEALLOCATE PREPARE onboarding_activation_stmt;

-- Backfill rows created by an earlier build from their already-scoped
-- calibration record before enforcing the scope shape.
UPDATE nx_user_device d
JOIN nx_onboarding_calibration c
  ON c.user_device_id=d.id AND c.user_id=d.user_id AND c.is_deleted=0
SET d.source_environment=c.source_environment,
    d.run_id=c.run_id,
    d.updated_at=NOW(6)
WHERE d.source_channel='ONBOARDING' AND d.is_deleted=0;

SET @onboarding_add_device_scope_index = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_user_device' AND INDEX_NAME='idx_user_device_scope')=0,
  'ALTER TABLE nx_user_device ADD INDEX idx_user_device_scope (user_id,source_environment,run_id,is_deleted)',
  'SELECT 1');
PREPARE onboarding_activation_stmt FROM @onboarding_add_device_scope_index; EXECUTE onboarding_activation_stmt; DEALLOCATE PREPARE onboarding_activation_stmt;

SET @onboarding_add_device_environment_check = IF(
  (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_user_device' AND CONSTRAINT_NAME='chk_user_device_environment')=0,
  'ALTER TABLE nx_user_device ADD CONSTRAINT chk_user_device_environment CHECK (source_environment IN (''PRODUCTION'',''SANDBOX''))',
  'SELECT 1');
PREPARE onboarding_activation_stmt FROM @onboarding_add_device_environment_check; EXECUTE onboarding_activation_stmt; DEALLOCATE PREPARE onboarding_activation_stmt;

SET @onboarding_add_device_scope_check = IF(
  (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_user_device' AND CONSTRAINT_NAME='chk_user_device_scope')=0,
  'ALTER TABLE nx_user_device ADD CONSTRAINT chk_user_device_scope CHECK ((source_environment=''PRODUCTION'' AND run_id='''') OR (source_environment=''SANDBOX'' AND CHAR_LENGTH(run_id) BETWEEN 8 AND 96))',
  'SELECT 1');
PREPARE onboarding_activation_stmt FROM @onboarding_add_device_scope_check; EXECUTE onboarding_activation_stmt; DEALLOCATE PREPARE onboarding_activation_stmt;

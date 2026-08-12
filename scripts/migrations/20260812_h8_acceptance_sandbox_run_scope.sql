-- H8 acceptance facts are RunID-scoped. The controlled startup runner applies
-- this idempotently before an acceptance runtime accepts traffic.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
               AND TABLE_NAME='nx_h8_sandbox_referral_settlement' AND COLUMN_NAME='run_id')=0,
  'ALTER TABLE nx_h8_sandbox_referral_settlement ADD COLUMN run_id VARCHAR(64) NOT NULL DEFAULT ''legacy-run'' AFTER idempotency_key', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
               AND TABLE_NAME='nx_h8_sandbox_referral_settlement' AND INDEX_NAME='uk_h8_sandbox_referral_idempotency')>0,
  'ALTER TABLE nx_h8_sandbox_referral_settlement DROP INDEX uk_h8_sandbox_referral_idempotency', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
               AND TABLE_NAME='nx_h8_sandbox_referral_settlement' AND INDEX_NAME='uk_h8_sandbox_referral_run_idempotency')=0,
  'ALTER TABLE nx_h8_sandbox_referral_settlement ADD UNIQUE KEY uk_h8_sandbox_referral_run_idempotency (run_id, idempotency_key)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_h8_sandbox_referral_command (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  response_json LONGTEXT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_h8_sandbox_command_run_key (run_id, idempotency_key),
  KEY idx_h8_sandbox_command_run_created (run_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_h8_sandbox_referral_audit (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id VARCHAR(64) NOT NULL,
  action VARCHAR(128) NOT NULL,
  resource_id VARCHAR(128) NOT NULL,
  actor VARCHAR(128) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  detail_json LONGTEXT NOT NULL,
  source VARCHAR(32) NOT NULL,
  source_environment VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_h8_sandbox_audit_run_key (run_id, idempotency_key),
  KEY idx_h8_sandbox_audit_run_created (run_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
               AND TABLE_NAME='nx_h8_sandbox_referral_settlement' AND INDEX_NAME='uk_h8_sandbox_referral_invited')>0,
  'ALTER TABLE nx_h8_sandbox_referral_settlement DROP INDEX uk_h8_sandbox_referral_invited', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
               AND TABLE_NAME='nx_h8_sandbox_referral_settlement' AND INDEX_NAME='uk_h8_sandbox_referral_run_invited')=0,
  'ALTER TABLE nx_h8_sandbox_referral_settlement ADD UNIQUE KEY uk_h8_sandbox_referral_run_invited (run_id, invited_user_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
               AND TABLE_NAME='nx_h8_sandbox_referral_settlement' AND INDEX_NAME='idx_h8_sandbox_referral_run_created')=0,
  'ALTER TABLE nx_h8_sandbox_referral_settlement ADD KEY idx_h8_sandbox_referral_run_created (run_id, created_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
               AND TABLE_NAME='nx_h8_sandbox_referral_ledger' AND COLUMN_NAME='run_id')=0,
  'ALTER TABLE nx_h8_sandbox_referral_ledger ADD COLUMN run_id VARCHAR(64) NOT NULL DEFAULT ''legacy-run'' AFTER settlement_no', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
               AND TABLE_NAME='nx_h8_sandbox_referral_ledger' AND INDEX_NAME='uk_h8_sandbox_referral_ledger_fact')>0,
  'ALTER TABLE nx_h8_sandbox_referral_ledger DROP INDEX uk_h8_sandbox_referral_ledger_fact', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
               AND TABLE_NAME='nx_h8_sandbox_referral_ledger' AND INDEX_NAME='uk_h8_sandbox_referral_ledger_fact')=0,
  'ALTER TABLE nx_h8_sandbox_referral_ledger ADD UNIQUE KEY uk_h8_sandbox_referral_ledger_fact (run_id, settlement_no, user_id, asset)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
               AND TABLE_NAME='nx_h8_sandbox_referral_ledger' AND INDEX_NAME='idx_h8_sandbox_referral_ledger_run_user_time')=0,
  'ALTER TABLE nx_h8_sandbox_referral_ledger ADD KEY idx_h8_sandbox_referral_ledger_run_user_time (run_id, user_id, created_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @d5_due_column_sql = IF(
  EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d5_payout_due_at'),
  'SELECT 1',
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d5_payout_due_at DATETIME NULL COMMENT ''D5 SLA deadline after H1 hold'''
);
PREPARE d5_due_column_stmt FROM @d5_due_column_sql;
EXECUTE d5_due_column_stmt;
DEALLOCATE PREPARE d5_due_column_stmt;

SET @d5_payout_source_sql = IF(
  EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d5_payout_source'),
  'SELECT 1','ALTER TABLE nx_withdrawal_order ADD COLUMN d5_payout_source VARCHAR(16) NULL AFTER d5_payout_due_at');
PREPARE d5_payout_source_stmt FROM @d5_payout_source_sql; EXECUTE d5_payout_source_stmt; DEALLOCATE PREPARE d5_payout_source_stmt;

SET @d5_provider_cid_sql = IF(
  EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d5_provider_cid'),
  'SELECT 1','ALTER TABLE nx_withdrawal_order ADD COLUMN d5_provider_cid BIGINT NULL AFTER d5_payout_source');
PREPARE d5_provider_cid_stmt FROM @d5_provider_cid_sql; EXECUTE d5_provider_cid_stmt; DEALLOCATE PREPARE d5_provider_cid_stmt;

SET @d5_provider_idem_sql = IF(
  EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d5_provider_idempotency_key'),
  'SELECT 1','ALTER TABLE nx_withdrawal_order ADD COLUMN d5_provider_idempotency_key VARCHAR(128) NULL AFTER d5_provider_cid');
PREPARE d5_provider_idem_stmt FROM @d5_provider_idem_sql; EXECUTE d5_provider_idem_stmt; DEALLOCATE PREPARE d5_provider_idem_stmt;

SET @d5_payout_lease_sql = IF(
  EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d5_payout_lease_until'),
  'SELECT 1','ALTER TABLE nx_withdrawal_order ADD COLUMN d5_payout_lease_until DATETIME NULL AFTER d5_provider_idempotency_key');
PREPARE d5_payout_lease_stmt FROM @d5_payout_lease_sql; EXECUTE d5_payout_lease_stmt; DEALLOCATE PREPARE d5_payout_lease_stmt;

SET @d5_provider_idem_index_sql = IF(
  EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND INDEX_NAME='uk_withdrawal_provider_idempotency'),
  'SELECT 1','ALTER TABLE nx_withdrawal_order ADD UNIQUE KEY uk_withdrawal_provider_idempotency (d5_provider_idempotency_key)');
PREPARE d5_provider_idem_index_stmt FROM @d5_provider_idem_index_sql; EXECUTE d5_provider_idem_index_stmt; DEALLOCATE PREPARE d5_provider_idem_index_stmt;

CREATE TABLE IF NOT EXISTS nx_withdrawal_payout_ledger (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_no VARCHAR(96) NOT NULL,
  withdrawal_no VARCHAR(96) NOT NULL,
  provider_cid BIGINT NOT NULL,
  event_type VARCHAR(24) NOT NULL,
  status VARCHAR(24) NOT NULL,
  source VARCHAR(16) NOT NULL,
  amount_usdt DECIMAL(18,6) NOT NULL,
  txid VARCHAR(128) NULL,
  payload_hash CHAR(64) NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_withdrawal_payout_event (event_no),
  KEY idx_withdrawal_payout_order (withdrawal_no,id),
  KEY idx_withdrawal_payout_provider (source,provider_cid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_withdrawal_payout_callback_inbox (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_no VARCHAR(96) NOT NULL,
  withdrawal_no VARCHAR(96) NOT NULL,
  provider_cid BIGINT NOT NULL,
  provider_status TINYINT NOT NULL,
  txid VARCHAR(128) NULL,
  payload_hash CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME NOT NULL,
  lease_until DATETIME NULL,
  last_error VARCHAR(120) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_withdrawal_callback_event (event_no),
  KEY idx_withdrawal_callback_retry (status,next_attempt_at,lease_until,id),
  KEY idx_withdrawal_callback_order (withdrawal_no,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @payment_method_source_env_sql = IF(
  EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_wallet_bank_card' AND COLUMN_NAME='source_environment'),
  'SELECT 1','ALTER TABLE nx_wallet_bank_card ADD COLUMN source_environment VARCHAR(16) NOT NULL DEFAULT ''PRODUCTION'' AFTER is_default');
PREPARE payment_method_source_env_stmt FROM @payment_method_source_env_sql; EXECUTE payment_method_source_env_stmt; DEALLOCATE PREPARE payment_method_source_env_stmt;

SET @payment_method_version_sql = IF(
  EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_wallet_bank_card' AND COLUMN_NAME='version'),
  'SELECT 1','ALTER TABLE nx_wallet_bank_card ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER source_environment');
PREPARE payment_method_version_stmt FROM @payment_method_version_sql; EXECUTE payment_method_version_stmt; DEALLOCATE PREPARE payment_method_version_stmt;

CREATE TABLE IF NOT EXISTS nx_payout_vnd_sandbox_order (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id VARCHAR(64) NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  amount_vnd DECIMAL(24,2) NOT NULL,
  bank_code VARCHAR(20) NOT NULL,
  account_no_masked VARCHAR(64) NOT NULL,
  account_name VARCHAR(80) NOT NULL,
  status VARCHAR(20) NOT NULL,
  source VARCHAR(16) NOT NULL DEFAULT 'mock',
  idempotency_key VARCHAR(128) NOT NULL,
  reason VARCHAR(200) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_payout_vnd_sandbox_run_order (run_id, order_no),
  UNIQUE KEY uk_payout_vnd_sandbox_run_idem (run_id, user_id, idempotency_key),
  KEY idx_payout_vnd_sandbox_run_user (run_id, user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @payout_vnd_order_run_sql = IF(
  EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payout_vnd_sandbox_order' AND COLUMN_NAME='run_id'),
  'SELECT 1','ALTER TABLE nx_payout_vnd_sandbox_order ADD COLUMN run_id VARCHAR(64) NOT NULL DEFAULT ''legacy-run'' AFTER id');
PREPARE payout_vnd_order_run_stmt FROM @payout_vnd_order_run_sql; EXECUTE payout_vnd_order_run_stmt; DEALLOCATE PREPARE payout_vnd_order_run_stmt;

CREATE TABLE IF NOT EXISTS nx_payout_vnd_sandbox_ledger (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id VARCHAR(64) NOT NULL,
  event_id VARCHAR(80) NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  direction VARCHAR(8) NOT NULL,
  amount_vnd DECIMAL(24,2) NOT NULL,
  status VARCHAR(20) NOT NULL,
  source VARCHAR(16) NOT NULL DEFAULT 'mock',
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_payout_vnd_sandbox_run_event (run_id, event_id),
  UNIQUE KEY uk_payout_vnd_sandbox_run_order_ledger (run_id, order_no),
  KEY idx_payout_vnd_sandbox_ledger_run_user (run_id, user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @payout_vnd_ledger_run_sql = IF(
  EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payout_vnd_sandbox_ledger' AND COLUMN_NAME='run_id'),
  'SELECT 1','ALTER TABLE nx_payout_vnd_sandbox_ledger ADD COLUMN run_id VARCHAR(64) NOT NULL DEFAULT ''legacy-run'' AFTER id');
PREPARE payout_vnd_ledger_run_stmt FROM @payout_vnd_ledger_run_sql; EXECUTE payout_vnd_ledger_run_stmt; DEALLOCATE PREPARE payout_vnd_ledger_run_stmt;

SET @sql = IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payout_vnd_sandbox_order' AND INDEX_NAME='uk_payout_vnd_sandbox_order_no'), 'ALTER TABLE nx_payout_vnd_sandbox_order DROP INDEX uk_payout_vnd_sandbox_order_no', 'SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payout_vnd_sandbox_order' AND INDEX_NAME='uk_payout_vnd_sandbox_idem'), 'ALTER TABLE nx_payout_vnd_sandbox_order DROP INDEX uk_payout_vnd_sandbox_idem', 'SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payout_vnd_sandbox_order' AND INDEX_NAME='idx_payout_vnd_sandbox_user'), 'ALTER TABLE nx_payout_vnd_sandbox_order DROP INDEX idx_payout_vnd_sandbox_user', 'SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payout_vnd_sandbox_order' AND INDEX_NAME='uk_payout_vnd_sandbox_run_order'), 'SELECT 1', 'ALTER TABLE nx_payout_vnd_sandbox_order ADD UNIQUE KEY uk_payout_vnd_sandbox_run_order (run_id, order_no)'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payout_vnd_sandbox_order' AND INDEX_NAME='uk_payout_vnd_sandbox_run_idem'), 'SELECT 1', 'ALTER TABLE nx_payout_vnd_sandbox_order ADD UNIQUE KEY uk_payout_vnd_sandbox_run_idem (run_id, user_id, idempotency_key)'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payout_vnd_sandbox_order' AND INDEX_NAME='idx_payout_vnd_sandbox_run_user'), 'SELECT 1', 'CREATE INDEX idx_payout_vnd_sandbox_run_user ON nx_payout_vnd_sandbox_order(run_id, user_id, id)'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payout_vnd_sandbox_ledger' AND INDEX_NAME='uk_payout_vnd_sandbox_event'), 'ALTER TABLE nx_payout_vnd_sandbox_ledger DROP INDEX uk_payout_vnd_sandbox_event', 'SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payout_vnd_sandbox_ledger' AND INDEX_NAME='uk_payout_vnd_sandbox_ledger_order'), 'ALTER TABLE nx_payout_vnd_sandbox_ledger DROP INDEX uk_payout_vnd_sandbox_ledger_order', 'SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payout_vnd_sandbox_ledger' AND INDEX_NAME='idx_payout_vnd_sandbox_ledger_user'), 'ALTER TABLE nx_payout_vnd_sandbox_ledger DROP INDEX idx_payout_vnd_sandbox_ledger_user', 'SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payout_vnd_sandbox_ledger' AND INDEX_NAME='uk_payout_vnd_sandbox_run_event'), 'SELECT 1', 'ALTER TABLE nx_payout_vnd_sandbox_ledger ADD UNIQUE KEY uk_payout_vnd_sandbox_run_event (run_id, event_id)'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payout_vnd_sandbox_ledger' AND INDEX_NAME='uk_payout_vnd_sandbox_run_order_ledger'), 'SELECT 1', 'ALTER TABLE nx_payout_vnd_sandbox_ledger ADD UNIQUE KEY uk_payout_vnd_sandbox_run_order_ledger (run_id, order_no)'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payout_vnd_sandbox_ledger' AND INDEX_NAME='idx_payout_vnd_sandbox_ledger_run_user'), 'SELECT 1', 'CREATE INDEX idx_payout_vnd_sandbox_ledger_run_user ON nx_payout_vnd_sandbox_ledger(run_id, user_id, id)'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_payment_method_revoke_command (
  id BIGINT NOT NULL AUTO_INCREMENT,
  command_no VARCHAR(64) NOT NULL,
  payment_method_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  provider_token VARCHAR(255) NULL,
  status VARCHAR(20) NOT NULL,
  source VARCHAR(16) NOT NULL,
  source_environment VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME NULL,
  lease_until DATETIME NULL,
  deadline_at DATETIME NULL,
  provider_receipt VARCHAR(255) NULL,
  last_error VARCHAR(120) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_payment_method_revoke_command (command_no),
  UNIQUE KEY uk_payment_method_revoke_method (payment_method_id),
  KEY idx_payment_method_revoke_retry (status,next_attempt_at,lease_until,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @pmr_lease_column_sql = IF(
  EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payment_method_revoke_command' AND COLUMN_NAME='lease_until'),
  'SELECT 1',
  'ALTER TABLE nx_payment_method_revoke_command ADD COLUMN lease_until DATETIME NULL AFTER next_attempt_at'
);
PREPARE pmr_lease_column_stmt FROM @pmr_lease_column_sql;
EXECUTE pmr_lease_column_stmt;
DEALLOCATE PREPARE pmr_lease_column_stmt;

SET @pmr_source_env_sql = IF(
  EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_payment_method_revoke_command' AND COLUMN_NAME='source_environment'),
  'SELECT 1',
  'ALTER TABLE nx_payment_method_revoke_command ADD COLUMN source_environment VARCHAR(16) NOT NULL DEFAULT ''PRODUCTION'' AFTER source'
);
PREPARE pmr_source_env_stmt FROM @pmr_source_env_sql;
EXECUTE pmr_source_env_stmt;
DEALLOCATE PREPARE pmr_source_env_stmt;

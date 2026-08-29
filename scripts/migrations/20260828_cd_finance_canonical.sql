-- Canonical C/D finance prerequisites extracted from the retired mixed
-- finance/Sandbox migration. This file must never create an isolated rail.
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

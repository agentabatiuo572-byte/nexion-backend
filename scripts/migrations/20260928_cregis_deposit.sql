-- Preserve the raw transaction hash while distinguishing multiple Transfer logs in one transaction.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'nx_deposit_order' AND COLUMN_NAME = 'chain_log_index') = 0,
  'ALTER TABLE nx_deposit_order ADD COLUMN chain_log_index INT NOT NULL DEFAULT 0 AFTER chain_tx_hash',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'nx_deposit_order' AND INDEX_NAME = 'uk_deposit_chain_tx_asset_log') = 0,
  'ALTER TABLE nx_deposit_order ADD UNIQUE KEY uk_deposit_chain_tx_asset_log (chain_tx_hash,asset,chain_log_index)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'nx_deposit_order' AND INDEX_NAME = 'uk_deposit_chain_tx_asset') > 0,
  'ALTER TABLE nx_deposit_order DROP INDEX uk_deposit_chain_tx_asset',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_cregis_deposit_address (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NULL,
  project_id BIGINT NOT NULL,
  chain_id VARCHAR(16) NOT NULL,
  address VARCHAR(64) NULL,
  request_id VARCHAR(64) NOT NULL,
  state VARCHAR(24) NOT NULL,
  creation_block BIGINT NULL,
  allocation_block BIGINT NULL,
  allocation_hash VARCHAR(66) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_cregis_address_user (user_id, chain_id),
  UNIQUE KEY uk_cregis_address_value (project_id, chain_id, address),
  UNIQUE KEY uk_cregis_address_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_cregis_deposit_address' AND COLUMN_NAME='user_id' AND IS_NULLABLE='NO') > 0,
  'ALTER TABLE nx_cregis_deposit_address MODIFY user_id BIGINT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_cregis_deposit_address' AND COLUMN_NAME='creation_block') = 0,
  'ALTER TABLE nx_cregis_deposit_address ADD COLUMN creation_block BIGINT NULL AFTER state', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_cregis_deposit_delivery (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  payload_sha256 CHAR(64) NOT NULL,
  raw_json TEXT NOT NULL,
  cid BIGINT NULL,
  txid VARCHAR(66) NULL,
  address VARCHAR(64) NULL,
  gross_amount DECIMAL(18,6) NULL,
  accepted TINYINT NOT NULL,
  reason VARCHAR(64) NOT NULL,
  last_error VARCHAR(64) NULL,
  processed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_cregis_delivery_pending (accepted, processed_at, id),
  KEY idx_cregis_delivery_cid (cid,accepted),
  KEY idx_cregis_delivery_hash (payload_sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_cregis_deposit_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  project_id BIGINT NOT NULL,
  cid BIGINT NOT NULL,
  txid VARCHAR(66) NOT NULL,
  log_index INT NOT NULL,
  address VARCHAR(64) NOT NULL,
  gross_amount DECIMAL(18,6) NOT NULL,
  fee_amount DECIMAL(18,6) NOT NULL,
  net_amount DECIMAL(18,6) NOT NULL,
  block_number BIGINT NOT NULL,
  block_hash VARCHAR(66) NOT NULL,
  confirmations INT NOT NULL,
  canonical_until BIGINT NULL,
  last_canonical_checked_block BIGINT NULL,
  last_canonical_checked_hash VARCHAR(66) NULL,
  status VARCHAR(24) NOT NULL,
  ledger_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  credited_at DATETIME NULL,
  UNIQUE KEY uk_cregis_event_log (project_id, txid, log_index),
  UNIQUE KEY uk_cregis_event_cid (project_id, cid),
  KEY idx_cregis_event_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_user_wallet' AND COLUMN_NAME='cregis_risk_held') = 0,
  'ALTER TABLE nx_user_wallet ADD COLUMN cregis_risk_held DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER usdt_available',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_cregis_deposit_event' AND COLUMN_NAME='canonical_until') = 0,
  'ALTER TABLE nx_cregis_deposit_event ADD COLUMN canonical_until BIGINT NULL AFTER confirmations', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_cregis_deposit_event' AND COLUMN_NAME='last_canonical_checked_block') = 0,
  'ALTER TABLE nx_cregis_deposit_event ADD COLUMN last_canonical_checked_block BIGINT NULL AFTER canonical_until', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_cregis_deposit_event' AND COLUMN_NAME='last_canonical_checked_hash') = 0,
  'ALTER TABLE nx_cregis_deposit_event ADD COLUMN last_canonical_checked_hash VARCHAR(66) NULL AFTER last_canonical_checked_block', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_cregis_deposit_incident (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id BIGINT NOT NULL,
  project_id BIGINT NOT NULL,
  cid BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  kind VARCHAR(32) NOT NULL,
  held_amount DECIMAL(18,6) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_cregis_incident_kind (event_id,kind),
  KEY idx_cregis_incident_user (user_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_cregis_chain_observation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  txid VARCHAR(66) NOT NULL,
  log_index INT NOT NULL,
  address VARCHAR(64) NOT NULL,
  raw_amount VARCHAR(80) NOT NULL,
  block_number BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  last_error VARCHAR(64) NULL,
  checked_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_cregis_observation_log (project_id,txid,log_index),
  KEY idx_cregis_observation_status (status,checked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_cregis_chain_cursor (
  id TINYINT PRIMARY KEY,
  next_block BIGINT NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_cregis_provision_gate (
  id TINYINT PRIMARY KEY,
  state VARCHAR(16) NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO nx_cregis_provision_gate(id,state) VALUES (1,'IDLE');

-- Apply after 20260928_cregis_deposit.sql while pay-in switches remain disabled.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_cregis_provision_gate' AND COLUMN_NAME='assign_enabled') = 0,
  'ALTER TABLE nx_cregis_provision_gate ADD COLUMN assign_enabled TINYINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_cregis_deposit_delivery' AND COLUMN_NAME='source_ip') = 0,
  'ALTER TABLE nx_cregis_deposit_delivery ADD COLUMN source_ip VARCHAR(45) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_cregis_deposit_delivery' AND COLUMN_NAME='signature_valid') = 0,
  'ALTER TABLE nx_cregis_deposit_delivery ADD COLUMN signature_valid TINYINT NULL DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_cregis_deposit_delivery' AND COLUMN_NAME='timestamp_valid') = 0,
  'ALTER TABLE nx_cregis_deposit_delivery ADD COLUMN timestamp_valid TINYINT NULL DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_cregis_deposit_delivery' AND COLUMN_NAME='ip_valid') = 0,
  'ALTER TABLE nx_cregis_deposit_delivery ADD COLUMN ip_valid TINYINT NULL DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_cregis_provision_gate' AND COLUMN_NAME='credit_enabled') = 0,
  'ALTER TABLE nx_cregis_provision_gate ADD COLUMN credit_enabled TINYINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_cregis_provision_gate' AND COLUMN_NAME='payout_enabled') = 0,
  'ALTER TABLE nx_cregis_provision_gate ADD COLUMN payout_enabled TINYINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_cregis_provision_gate' AND COLUMN_NAME='version') = 0,
  'ALTER TABLE nx_cregis_provision_gate ADD COLUMN version BIGINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_cregis_provision_gate' AND COLUMN_NAME='switch_reason') = 0,
  'ALTER TABLE nx_cregis_provision_gate ADD COLUMN switch_reason VARCHAR(255) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_cregis_reconcile_watermark (
  id TINYINT PRIMARY KEY,
  complete_through BIGINT NOT NULL,
  last_run_id CHAR(36) NULL,
  active_run_id CHAR(36) NULL,
  lease_until DATETIME NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_cregis_reconcile_watermark' AND COLUMN_NAME='active_run_id') = 0,
  'ALTER TABLE nx_cregis_reconcile_watermark ADD COLUMN active_run_id CHAR(36) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_cregis_reconcile_watermark' AND COLUMN_NAME='lease_until') = 0,
  'ALTER TABLE nx_cregis_reconcile_watermark ADD COLUMN lease_until DATETIME NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_cregis_reconcile_run (
  run_id CHAR(36) PRIMARY KEY,
  project_id BIGINT NOT NULL,
  window_start BIGINT NOT NULL,
  window_end BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  provider_total BIGINT NULL,
  row_count BIGINT NULL,
  unique_cid_count BIGINT NULL,
  full_row_hash CHAR(64) NULL,
  stable_passes INT NOT NULL DEFAULT 0,
  chain_cursor_block BIGINT NULL,
  chain_cursor_hash VARCHAR(66) NULL,
  failure_code VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at DATETIME NULL,
  KEY idx_cregis_reconcile_window (status,window_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_cregis_risk_alert (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  alert_key VARCHAR(128) NOT NULL,
  severity VARCHAR(2) NOT NULL,
  kind VARCHAR(48) NOT NULL,
  evidence VARCHAR(512) NOT NULL,
  resolved_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_cregis_alert_key (project_id,alert_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_cregis_risk_alert' AND COLUMN_NAME='resolved_at') = 0,
  'ALTER TABLE nx_cregis_risk_alert ADD COLUMN resolved_at DATETIME NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_cregis_review_case (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  event_id BIGINT NOT NULL,
  action VARCHAR(32) NOT NULL,
  reason VARCHAR(255) NOT NULL,
  evidence_hash CHAR(64) NOT NULL,
  maker_id BIGINT NOT NULL,
  checker_id BIGINT NULL,
  status VARCHAR(20) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  checked_at DATETIME NULL,
  CONSTRAINT chk_cregis_review_distinct CHECK (checker_id IS NULL OR checker_id <> maker_id),
  KEY idx_cregis_review_event (project_id,event_id,action,status),
  KEY idx_cregis_review_status (status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_cregis_switch_case (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  expected_version BIGINT NOT NULL,
  assign_enabled TINYINT NOT NULL,
  credit_enabled TINYINT NOT NULL,
  payout_enabled TINYINT NOT NULL DEFAULT 0,
  reason VARCHAR(255) NOT NULL,
  maker_id BIGINT NOT NULL,
  checker_id BIGINT NULL,
  status VARCHAR(20) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  checked_at DATETIME NULL,
  CONSTRAINT chk_cregis_switch_distinct CHECK (checker_id IS NULL OR checker_id <> maker_id),
  KEY idx_cregis_switch_status (project_id,status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

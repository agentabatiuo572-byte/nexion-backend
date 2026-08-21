-- Developer API credentials and webhook configuration.
-- Secrets are never persisted in plaintext. The ciphertext is encrypted with the deployment webhook key.
CREATE TABLE IF NOT EXISTS nx_developer_api_key (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  key_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  name VARCHAR(100) NOT NULL,
  key_hash CHAR(64) NOT NULL,
  key_prefix VARCHAR(32) NOT NULL,
  key_last4 CHAR(4) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'PRODUCTION',
  run_id VARCHAR(64) NOT NULL DEFAULT '',
  last_used_at DATETIME NULL,
  revoked_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_developer_api_key_id (key_id),
  UNIQUE KEY uk_developer_api_key_idem (user_id,source_environment,run_id,idempotency_key),
  UNIQUE KEY uk_developer_api_key_hash (key_hash),
  KEY idx_developer_api_key_owner (user_id,source_environment,run_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_developer_webhook (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  name VARCHAR(100) NOT NULL,
  url VARCHAR(2048) NOT NULL,
  events_json JSON NOT NULL,
  secret_hash CHAR(64) NOT NULL,
  secret_ciphertext TEXT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  delivery_status VARCHAR(32) NOT NULL DEFAULT 'NOT_DELIVERED',
  version BIGINT NOT NULL DEFAULT 0,
  secret_rotation_key VARCHAR(128) NULL,
  secret_rotation_hash CHAR(64) NULL,
  source_environment VARCHAR(16) NOT NULL DEFAULT 'PRODUCTION',
  run_id VARCHAR(64) NOT NULL DEFAULT '',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_developer_webhook_idem (user_id,source_environment,run_id,idempotency_key),
  KEY idx_developer_webhook_owner (user_id,source_environment,run_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_developer_webhook' AND COLUMN_NAME='version')=0,
  'ALTER TABLE nx_developer_webhook ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER delivery_status','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_developer_webhook' AND COLUMN_NAME='secret_rotation_key')=0,
  'ALTER TABLE nx_developer_webhook ADD COLUMN secret_rotation_key VARCHAR(128) NULL AFTER version','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_developer_webhook' AND COLUMN_NAME='secret_rotation_hash')=0,
  'ALTER TABLE nx_developer_webhook ADD COLUMN secret_rotation_hash CHAR(64) NULL AFTER secret_rotation_key','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_developer_webhook' AND COLUMN_NAME='secret_ciphertext')=0,
  'ALTER TABLE nx_developer_webhook ADD COLUMN secret_ciphertext TEXT NULL AFTER secret_hash','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_developer_webhook_delivery (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  webhook_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  source_environment VARCHAR(16) NOT NULL,
  run_id VARCHAR(64) NOT NULL DEFAULT '',
  event_id VARCHAR(128) NOT NULL,
  event_type VARCHAR(128) NOT NULL,
  payload_json JSON NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  attempt_count INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 5,
  last_status_code INT NULL,
  last_error VARCHAR(255) NULL,
  next_retry_at DATETIME NOT NULL,
  delivered_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_developer_webhook_delivery_event (webhook_id,event_id),
  KEY idx_developer_webhook_delivery_due (status,next_retry_at,id),
  KEY idx_developer_webhook_delivery_scope (user_id,source_environment,run_id,created_at),
  CONSTRAINT fk_developer_webhook_delivery_endpoint FOREIGN KEY (webhook_id) REFERENCES nx_developer_webhook(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

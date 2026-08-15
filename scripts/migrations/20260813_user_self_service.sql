CREATE TABLE IF NOT EXISTS nx_user_account_deletion_request (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  request_no VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  idempotency_key VARCHAR(160) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'REQUESTED',
  requested_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  reason VARCHAR(255) NULL,
  block_reason VARCHAR(255) NULL,
  reviewed_by BIGINT NULL,
  reviewed_at DATETIME NULL,
  cancelled_at DATETIME NULL,
  UNIQUE KEY uk_user_account_deletion_no (request_no),
  UNIQUE KEY uk_user_account_deletion_idempotency (user_id,idempotency_key),
  KEY idx_user_account_deletion_status (user_id,status,requested_at),
  CONSTRAINT chk_user_account_deletion_status
    CHECK (status IN ('REQUESTED','IN_REVIEW','BLOCKED','COMPLETED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Existing installations may already have the initial request-only table.
-- The API reads these lifecycle fields, so upgrade each column before the JVM
-- accepts authenticated security requests.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_account_deletion_request' AND COLUMN_NAME = 'version') = 0,
  'ALTER TABLE nx_user_account_deletion_request ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER is_deleted',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_account_deletion_request' AND COLUMN_NAME = 'reason') = 0,
  'ALTER TABLE nx_user_account_deletion_request ADD COLUMN reason VARCHAR(255) NULL AFTER version',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_account_deletion_request' AND COLUMN_NAME = 'block_reason') = 0,
  'ALTER TABLE nx_user_account_deletion_request ADD COLUMN block_reason VARCHAR(255) NULL AFTER reason',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_account_deletion_request' AND COLUMN_NAME = 'reviewed_by') = 0,
  'ALTER TABLE nx_user_account_deletion_request ADD COLUMN reviewed_by BIGINT NULL AFTER block_reason',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_account_deletion_request' AND COLUMN_NAME = 'reviewed_at') = 0,
  'ALTER TABLE nx_user_account_deletion_request ADD COLUMN reviewed_at DATETIME NULL AFTER reviewed_by',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_account_deletion_request' AND COLUMN_NAME = 'cancelled_at') = 0,
  'ALTER TABLE nx_user_account_deletion_request ADD COLUMN cancelled_at DATETIME NULL AFTER reviewed_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_account_deletion_request' AND INDEX_NAME = 'uk_user_account_deletion_user') > 0,
  'ALTER TABLE nx_user_account_deletion_request DROP INDEX uk_user_account_deletion_user',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_account_deletion_request' AND INDEX_NAME = 'idx_user_account_deletion_user_status') = 0,
  'ALTER TABLE nx_user_account_deletion_request ADD KEY idx_user_account_deletion_user_status (user_id,status,requested_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

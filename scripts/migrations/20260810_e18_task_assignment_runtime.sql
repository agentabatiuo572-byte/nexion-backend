-- OPS-E-18/E-19: durable app task assignment snapshots and server-authoritative device locks.
-- MySQL does not support MariaDB's conditional-column syntax. Keep the
-- startup migration repeatable by deriving every ALTER from information_schema.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_task' AND COLUMN_NAME = 'task_config_id') = 0,
  'ALTER TABLE nx_compute_task ADD COLUMN task_config_id VARCHAR(64) NULL AFTER task_type', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_task' AND COLUMN_NAME = 'task_name') = 0,
  'ALTER TABLE nx_compute_task ADD COLUMN task_name VARCHAR(128) NULL AFTER task_config_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_task' AND COLUMN_NAME = 'model_name') = 0,
  'ALTER TABLE nx_compute_task ADD COLUMN model_name VARCHAR(128) NULL AFTER task_name', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_task' AND COLUMN_NAME = 'reward_usdt') = 0,
  'ALTER TABLE nx_compute_task ADD COLUMN reward_usdt DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER model_name', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_task' AND COLUMN_NAME = 'required_seconds') = 0,
  'ALTER TABLE nx_compute_task ADD COLUMN required_seconds INT NOT NULL DEFAULT 60 AFTER reward_usdt', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_task' AND COLUMN_NAME = 'task_lock_minutes') = 0,
  'ALTER TABLE nx_compute_task ADD COLUMN task_lock_minutes INT NOT NULL DEFAULT 0 AFTER required_seconds', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_task' AND COLUMN_NAME = 'completion_nonce') = 0,
  'ALTER TABLE nx_compute_task ADD COLUMN completion_nonce CHAR(64) NULL AFTER task_lock_minutes', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_task' AND COLUMN_NAME = 'proof_expires_at') = 0,
  'ALTER TABLE nx_compute_task ADD COLUMN proof_expires_at DATETIME NULL AFTER completion_nonce', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_task' AND COLUMN_NAME = 'proof_consumed_at') = 0,
  'ALTER TABLE nx_compute_task ADD COLUMN proof_consumed_at DATETIME NULL AFTER proof_expires_at', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_task' AND COLUMN_NAME = 'source_environment') = 0,
  'ALTER TABLE nx_compute_task ADD COLUMN source_environment VARCHAR(16) NOT NULL DEFAULT ''PRODUCTION'' AFTER proof_consumed_at', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_receipt' AND COLUMN_NAME = 'source_environment') = 0,
  'ALTER TABLE nx_compute_receipt ADD COLUMN source_environment VARCHAR(16) NOT NULL DEFAULT ''PRODUCTION'' AFTER earning_status', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_compute_device_task_lock (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  user_device_id BIGINT NOT NULL,
  source_environment VARCHAR(16) NOT NULL DEFAULT 'PRODUCTION',
  lock_until DATETIME NOT NULL,
  last_task_no VARCHAR(96) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_compute_device_task_lock_device_env (user_device_id, source_environment),
  KEY idx_compute_device_task_lock_user (user_id, lock_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_device_task_lock'
    AND COLUMN_NAME = 'source_environment') = 0,
  'ALTER TABLE nx_compute_device_task_lock ADD COLUMN source_environment VARCHAR(16) NOT NULL DEFAULT ''PRODUCTION'' AFTER user_device_id',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_device_task_lock'
    AND INDEX_NAME = 'uk_compute_device_task_lock_device') > 0,
  'ALTER TABLE nx_compute_device_task_lock DROP INDEX uk_compute_device_task_lock_device',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_device_task_lock'
    AND INDEX_NAME = 'uk_compute_device_task_lock_device_env') = 0,
  'ALTER TABLE nx_compute_device_task_lock ADD UNIQUE KEY uk_compute_device_task_lock_device_env (user_device_id, source_environment)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_compute_sandbox_reward (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_no VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  user_device_id BIGINT NOT NULL,
  source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  run_id VARCHAR(96) NOT NULL,
  receipt_no VARCHAR(96) NOT NULL,
  simulated_reward_usdt DECIMAL(18,6) NOT NULL,
  proof_hash CHAR(64) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_compute_sandbox_reward_task (task_no),
  UNIQUE KEY uk_compute_sandbox_reward_receipt (receipt_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

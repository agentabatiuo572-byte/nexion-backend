CREATE TABLE IF NOT EXISTS nx_developer_access_request (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  request_no VARCHAR(32) NOT NULL,
  user_id BIGINT NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  company VARCHAR(120) NOT NULL,
  email VARCHAR(254) NOT NULL,
  use_case TEXT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'PRODUCTION',
  run_id VARCHAR(64) NOT NULL DEFAULT '',
  reviewer VARCHAR(128) NULL,
  review_reason VARCHAR(500) NULL,
  reviewed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_developer_access_request_no (request_no),
  UNIQUE KEY uk_developer_access_user_run_key (user_id,run_id,idempotency_key),
  KEY idx_developer_access_user_time (user_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_developer_access_request' AND COLUMN_NAME='source_environment')=0,
  'ALTER TABLE nx_developer_access_request ADD COLUMN source_environment VARCHAR(16) NOT NULL DEFAULT ''PRODUCTION'' AFTER status','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_developer_access_request' AND COLUMN_NAME='run_id')=0,
  'ALTER TABLE nx_developer_access_request ADD COLUMN run_id VARCHAR(64) NOT NULL DEFAULT '''' AFTER source_environment','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_developer_access_request' AND INDEX_NAME='uk_developer_access_user_key')>0,
  'ALTER TABLE nx_developer_access_request DROP INDEX uk_developer_access_user_key','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_developer_access_request' AND INDEX_NAME='uk_developer_access_user_run_key')=0,
  'ALTER TABLE nx_developer_access_request ADD UNIQUE KEY uk_developer_access_user_run_key(user_id,run_id,idempotency_key)','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Self-scoped, replay-safe ambassador applications for the UniApp.
-- MySQL 8 compatible and safe to replay on both fresh and upgraded schemas.

CREATE TABLE IF NOT EXISTS nx_team_ambassador_application (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  applicant_name VARCHAR(64) NOT NULL,
  region VARCHAR(64) NOT NULL,
  city VARCHAR(64) NULL,
  event_date DATE NULL,
  contact_method VARCHAR(128) NULL,
  application_reason VARCHAR(255) NULL,
  event_plan TEXT NULL,
  expected_attendees INT NOT NULL DEFAULT 0,
  current_rank VARCHAR(16) NOT NULL DEFAULT 'V0',
  requested_budget_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  kol_budget_pct DECIMAL(8,4) NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  source_environment VARCHAR(16) NOT NULL DEFAULT 'PRODUCTION',
  run_id VARCHAR(64) NOT NULL DEFAULT '',
  reviewer VARCHAR(64) NULL,
  review_reason VARCHAR(255) NULL,
  reviewed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_ambassador_app_idempotency (user_id, source_environment, run_id, idempotency_key),
  KEY idx_ambassador_status_time (status, created_at),
  KEY idx_ambassador_user (user_id, status),
  KEY idx_ambassador_user_scope (user_id, source_environment, run_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_team_ambassador_application' AND COLUMN_NAME='idempotency_key')=0,
  'ALTER TABLE nx_team_ambassador_application ADD COLUMN idempotency_key VARCHAR(128) NOT NULL DEFAULT '''' AFTER status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_team_ambassador_application' AND COLUMN_NAME='request_hash')=0,
  'ALTER TABLE nx_team_ambassador_application ADD COLUMN request_hash CHAR(64) NOT NULL DEFAULT '''' AFTER idempotency_key',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_team_ambassador_application' AND COLUMN_NAME='source_environment')=0,
  'ALTER TABLE nx_team_ambassador_application ADD COLUMN source_environment VARCHAR(16) NOT NULL DEFAULT ''PRODUCTION'' AFTER request_hash',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_team_ambassador_application' AND COLUMN_NAME='run_id')=0,
  'ALTER TABLE nx_team_ambassador_application ADD COLUMN run_id VARCHAR(64) NOT NULL DEFAULT '''' AFTER source_environment',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE nx_team_ambassador_application
   SET idempotency_key=CONCAT('legacy-',id)
 WHERE idempotency_key IS NULL OR idempotency_key='';
UPDATE nx_team_ambassador_application
   SET request_hash=SHA2(CONCAT('legacy:',id),256)
 WHERE request_hash IS NULL OR request_hash NOT REGEXP '^[0-9a-fA-F]{64}$';
UPDATE nx_team_ambassador_application
   SET source_environment='PRODUCTION'
 WHERE source_environment IS NULL OR source_environment NOT IN ('PRODUCTION','SANDBOX');
UPDATE nx_team_ambassador_application SET run_id='' WHERE run_id IS NULL;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_team_ambassador_application'
    AND INDEX_NAME='uk_ambassador_app_idempotency')=0,
  'ALTER TABLE nx_team_ambassador_application ADD UNIQUE INDEX uk_ambassador_app_idempotency (user_id,source_environment,run_id,idempotency_key)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_team_ambassador_application'
    AND INDEX_NAME='idx_ambassador_user_scope')=0,
  'ALTER TABLE nx_team_ambassador_application ADD INDEX idx_ambassador_user_scope (user_id,source_environment,run_id,status,created_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

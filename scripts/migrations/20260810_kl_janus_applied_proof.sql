CREATE TABLE IF NOT EXISTS nx_janus_applied_proof (
  id BIGINT NOT NULL AUTO_INCREMENT,
  proof_id VARCHAR(40) NOT NULL,
  executor_mode VARCHAR(16) NOT NULL,
  executor_id VARCHAR(64) NOT NULL,
  proof_nonce VARCHAR(64) NOT NULL,
  proof_hash CHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  sid VARCHAR(128) NOT NULL,
  device_id VARCHAR(128) NOT NULL,
  command_id VARCHAR(128) NOT NULL,
  command_version BIGINT NOT NULL,
  target_id VARCHAR(128) NOT NULL,
  target_version INT NULL,
  target_catalog_version BIGINT NULL,
  handoff_receipt VARCHAR(512) NOT NULL,
  proof_timestamp DATETIME(3) NOT NULL,
  earnings_consumed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_janus_applied_proof_id (proof_id),
  UNIQUE KEY uk_janus_applied_proof_nonce (executor_id, proof_nonce),
  KEY idx_janus_applied_proof_command (sid, command_id, command_version),
  KEY idx_janus_applied_proof_user (user_id, device_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_user' AND column_name='sandbox')=0,
  'ALTER TABLE nx_user ADD COLUMN sandbox TINYINT(1) NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_behavior_event_fact' AND column_name='source_environment')=0,
  'ALTER TABLE nx_behavior_event_fact ADD COLUMN source_environment VARCHAR(16) NOT NULL DEFAULT ''PRODUCTION'' AFTER locale', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_user_wallet' AND column_name='sandbox')=0,
  'ALTER TABLE nx_user_wallet ADD COLUMN sandbox TINYINT(1) NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_earnings_release_entry' AND column_name='source_environment')=0,
  'ALTER TABLE nx_earnings_release_entry ADD COLUMN source_environment VARCHAR(16) NOT NULL DEFAULT ''PRODUCTION''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

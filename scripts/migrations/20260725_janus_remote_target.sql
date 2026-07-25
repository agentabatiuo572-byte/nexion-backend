-- K6 Janus approved RemoteTarget catalog.
-- Configuration only: this migration intentionally inserts no target URLs or mock/business rows.

CREATE TABLE IF NOT EXISTS nx_janus_remote_target (
  catalog_version BIGINT NOT NULL AUTO_INCREMENT,
  remote_target_key VARCHAR(64) NOT NULL,
  remote_target_version INT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  label VARCHAR(96) NOT NULL,
  target_url VARCHAR(1024) NOT NULL,
  target_origin VARCHAR(320) NOT NULL,
  source VARCHAR(16) NOT NULL DEFAULT 'ADMIN',
  owner_id VARCHAR(96) NOT NULL,
  change_reason VARCHAR(500) NOT NULL,
  impact_note VARCHAR(500) NOT NULL,
  updated_by VARCHAR(96) NOT NULL,
  lock_version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (catalog_version),
  UNIQUE KEY uk_janus_remote_target_version(remote_target_key,remote_target_version),
  KEY idx_janus_remote_target_status(remote_target_key,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Immutable target identity is nullable for legacy rows. NULL is intentional:
-- old key-only strategies/commands/devices fail closed and are never guessed
-- onto the newest target version.
SET @janus_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_janus_device'
     AND COLUMN_NAME='remote_target_version') = 0,
  'ALTER TABLE nx_janus_device ADD COLUMN remote_target_version INT DEFAULT NULL AFTER remote_url_key',
  'SELECT 1');
PREPARE janus_stmt FROM @janus_ddl; EXECUTE janus_stmt; DEALLOCATE PREPARE janus_stmt;

SET @janus_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_janus_device'
     AND COLUMN_NAME='remote_target_catalog_version') = 0,
  'ALTER TABLE nx_janus_device ADD COLUMN remote_target_catalog_version BIGINT DEFAULT NULL AFTER remote_target_version',
  'SELECT 1');
PREPARE janus_stmt FROM @janus_ddl; EXECUTE janus_stmt; DEALLOCATE PREPARE janus_stmt;

SET @janus_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_janus_device'
     AND INDEX_NAME='idx_janus_device_remote_target') = 0,
  'ALTER TABLE nx_janus_device ADD KEY idx_janus_device_remote_target (remote_url_key,remote_target_version,remote_target_catalog_version,command_state)',
  'SELECT 1');
PREPARE janus_stmt FROM @janus_ddl; EXECUTE janus_stmt; DEALLOCATE PREPARE janus_stmt;

SET @janus_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_janus_command'
     AND COLUMN_NAME='remote_target_key') = 0,
  'ALTER TABLE nx_janus_command ADD COLUMN remote_target_key VARCHAR(64) DEFAULT NULL AFTER state',
  'SELECT 1');
PREPARE janus_stmt FROM @janus_ddl; EXECUTE janus_stmt; DEALLOCATE PREPARE janus_stmt;

SET @janus_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_janus_command'
     AND COLUMN_NAME='remote_target_version') = 0,
  'ALTER TABLE nx_janus_command ADD COLUMN remote_target_version INT DEFAULT NULL AFTER remote_target_key',
  'SELECT 1');
PREPARE janus_stmt FROM @janus_ddl; EXECUTE janus_stmt; DEALLOCATE PREPARE janus_stmt;

SET @janus_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_janus_command'
     AND COLUMN_NAME='remote_target_catalog_version') = 0,
  'ALTER TABLE nx_janus_command ADD COLUMN remote_target_catalog_version BIGINT DEFAULT NULL AFTER remote_target_version',
  'SELECT 1');
PREPARE janus_stmt FROM @janus_ddl; EXECUTE janus_stmt; DEALLOCATE PREPARE janus_stmt;

SET @janus_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_janus_command'
     AND INDEX_NAME='idx_janus_command_remote_target') = 0,
  'ALTER TABLE nx_janus_command ADD KEY idx_janus_command_remote_target (remote_target_key,remote_target_version,remote_target_catalog_version,state)',
  'SELECT 1');
PREPARE janus_stmt FROM @janus_ddl; EXECUTE janus_stmt; DEALLOCATE PREPARE janus_stmt;

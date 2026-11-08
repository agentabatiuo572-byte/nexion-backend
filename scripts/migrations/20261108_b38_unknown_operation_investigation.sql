-- B38-BE-03: preserve command origin and append-only investigation conclusions.
-- No role grant: accountable investigator role requires a separate approved RBAC change.

SET @b38_investigation_ddl = IF(EXISTS(
  SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_idempotency_record' AND COLUMN_NAME='origin_admin_id'
), 'SELECT 1', 'ALTER TABLE nx_admin_idempotency_record ADD COLUMN origin_admin_id BIGINT NULL');
PREPARE b38_investigation_stmt FROM @b38_investigation_ddl;
EXECUTE b38_investigation_stmt;
DEALLOCATE PREPARE b38_investigation_stmt;

SET @b38_investigation_ddl = IF(EXISTS(
  SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_idempotency_record' AND COLUMN_NAME='origin_method'
), 'SELECT 1', 'ALTER TABLE nx_admin_idempotency_record ADD COLUMN origin_method VARCHAR(12) NULL');
PREPARE b38_investigation_stmt FROM @b38_investigation_ddl;
EXECUTE b38_investigation_stmt;
DEALLOCATE PREPARE b38_investigation_stmt;

SET @b38_investigation_ddl = IF(EXISTS(
  SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_idempotency_record' AND COLUMN_NAME='origin_path'
), 'SELECT 1', 'ALTER TABLE nx_admin_idempotency_record ADD COLUMN origin_path VARCHAR(512) NULL');
PREPARE b38_investigation_stmt FROM @b38_investigation_ddl;
EXECUTE b38_investigation_stmt;
DEALLOCATE PREPARE b38_investigation_stmt;

SET @b38_investigation_ddl = IF(EXISTS(
  SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_idempotency_record' AND COLUMN_NAME='origin_target'
), 'SELECT 1', 'ALTER TABLE nx_admin_idempotency_record ADD COLUMN origin_target VARCHAR(512) NULL');
PREPARE b38_investigation_stmt FROM @b38_investigation_ddl;
EXECUTE b38_investigation_stmt;
DEALLOCATE PREPARE b38_investigation_stmt;

SET @b38_investigation_ddl = IF(EXISTS(
  SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_idempotency_record' AND COLUMN_NAME='origin_read_authority'
), 'SELECT 1', 'ALTER TABLE nx_admin_idempotency_record ADD COLUMN origin_read_authority VARCHAR(96) NULL');
PREPARE b38_investigation_stmt FROM @b38_investigation_ddl;
EXECUTE b38_investigation_stmt;
DEALLOCATE PREPARE b38_investigation_stmt;

SET @b38_investigation_ddl = IF(EXISTS(
  SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_idempotency_record' AND COLUMN_NAME='rollback_confirmed'
), 'SELECT 1', 'ALTER TABLE nx_admin_idempotency_record ADD COLUMN rollback_confirmed TINYINT NOT NULL DEFAULT 0');
PREPARE b38_investigation_stmt FROM @b38_investigation_ddl;
EXECUTE b38_investigation_stmt;
DEALLOCATE PREPARE b38_investigation_stmt;

SET @b38_investigation_ddl = IF(EXISTS(
  SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_idempotency_record' AND INDEX_NAME='idx_admin_idem_origin_state'
), 'SELECT 1', 'ALTER TABLE nx_admin_idempotency_record ADD INDEX idx_admin_idem_origin_state (scope, origin_method, origin_path(128), status, is_deleted)');
PREPARE b38_investigation_stmt FROM @b38_investigation_ddl;
EXECUTE b38_investigation_stmt;
DEALLOCATE PREPARE b38_investigation_stmt;

CREATE TABLE IF NOT EXISTS nx_admin_command_investigation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  original_record_id BIGINT NOT NULL,
  scope VARCHAR(96) NOT NULL,
  command_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  original_method VARCHAR(12) NOT NULL,
  original_path VARCHAR(512) NOT NULL,
  original_target VARCHAR(512) NULL,
  original_admin_id BIGINT NOT NULL,
  investigator_id BIGINT NOT NULL,
  investigated_at DATETIME NOT NULL,
  requested_conclusion VARCHAR(32) NOT NULL,
  conclusion VARCHAR(32) NOT NULL,
  evidence_reference VARCHAR(512) NOT NULL,
  evidence_type VARCHAR(96) NOT NULL,
  fact_type VARCHAR(96) NULL,
  fact_id VARCHAR(255) NULL,
  fact_action VARCHAR(160) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_command_investigation_original (original_record_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

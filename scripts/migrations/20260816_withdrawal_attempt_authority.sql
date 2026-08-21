-- Durable association between the user idempotency attempt and the canonical
-- withdrawal. Every statement is replay-safe for fresh and upgraded schemas.
SET @withdrawal_idem_column_sql = IF(
  EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order'
            AND COLUMN_NAME='d2_idempotency_key'),
  'SELECT 1',
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_idempotency_key VARCHAR(128) NULL AFTER target_address'
);
PREPARE withdrawal_idem_column_stmt FROM @withdrawal_idem_column_sql;
EXECUTE withdrawal_idem_column_stmt;
DEALLOCATE PREPARE withdrawal_idem_column_stmt;

SET @withdrawal_idem_index_sql = IF(
  EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order'
            AND INDEX_NAME='uk_withdrawal_d2_idempotency'),
  'SELECT 1',
  'ALTER TABLE nx_withdrawal_order ADD UNIQUE KEY uk_withdrawal_d2_idempotency (user_id,d2_idempotency_key)'
);
PREPARE withdrawal_idem_index_stmt FROM @withdrawal_idem_index_sql;
EXECUTE withdrawal_idem_index_stmt;
DEALLOCATE PREPARE withdrawal_idem_index_stmt;

CREATE TABLE IF NOT EXISTS nx_withdrawal_attempt_control (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  withdrawal_no VARCHAR(96) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_withdrawal_attempt_user_key (user_id,idempotency_key),
  KEY idx_withdrawal_attempt_status (status,updated_at),
  CONSTRAINT chk_withdrawal_attempt_status CHECK (status IN ('ACTIVE','COMMITTED','ABANDONED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @withdrawal_attempt_check_sql = IF(
  EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
          WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_attempt_control'
            AND CONSTRAINT_NAME='chk_withdrawal_attempt_status'
            AND CONSTRAINT_TYPE='CHECK'),
  'SELECT 1',
  'ALTER TABLE nx_withdrawal_attempt_control ADD CONSTRAINT chk_withdrawal_attempt_status CHECK (status IN (''ACTIVE'',''COMMITTED'',''ABANDONED''))'
);
PREPARE withdrawal_attempt_check_stmt FROM @withdrawal_attempt_check_sql;
EXECUTE withdrawal_attempt_check_stmt;
DEALLOCATE PREPARE withdrawal_attempt_check_stmt;

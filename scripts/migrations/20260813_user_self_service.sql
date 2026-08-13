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
  UNIQUE KEY uk_user_account_deletion_no (request_no),
  UNIQUE KEY uk_user_account_deletion_user (user_id),
  UNIQUE KEY uk_user_account_deletion_idempotency (user_id,idempotency_key),
  KEY idx_user_account_deletion_status (user_id,status,requested_at),
  CONSTRAINT chk_user_account_deletion_status
    CHECK (status IN ('REQUESTED','IN_REVIEW','BLOCKED','COMPLETED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

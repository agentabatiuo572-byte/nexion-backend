-- DDL only, extracted from retired scripts/migrations/20260810_cd_finance_sandbox.sql (no sandbox DML).
CREATE TABLE IF NOT EXISTS nx_payment_method_revoke_command (
  id BIGINT NOT NULL AUTO_INCREMENT,
  command_no VARCHAR(64) NOT NULL,
  payment_method_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  provider_token VARCHAR(255) NULL,
  status VARCHAR(20) NOT NULL,
  source VARCHAR(16) NOT NULL,
  source_environment VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME NULL,
  lease_until DATETIME NULL,
  deadline_at DATETIME NULL,
  provider_receipt VARCHAR(255) NULL,
  last_error VARCHAR(120) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_payment_method_revoke_command (command_no),
  UNIQUE KEY uk_payment_method_revoke_method (payment_method_id),
  KEY idx_payment_method_revoke_retry (status,next_attempt_at,lease_until,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

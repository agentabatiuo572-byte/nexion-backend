-- Payout addresses are server-authoritative in every App runtime.
-- Production continues to use nx_user_payout_address; these tables are only
-- for the explicit local-sandbox profile and cannot be joined to production
-- address or OTP state.
CREATE TABLE IF NOT EXISTS nx_user_payout_address_sandbox (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  network VARCHAR(32) NOT NULL,
  address VARCHAR(255) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  source VARCHAR(16) NOT NULL DEFAULT 'mock',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  effective_at DATETIME NOT NULL,
  next_change_allowed_at DATETIME NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_payout_address_sandbox_scope (run_id,user_id,network),
  KEY idx_user_payout_address_sandbox_scope (run_id,user_id,status,is_deleted),
  CONSTRAINT chk_user_payout_address_sandbox_network CHECK (network IN ('USDT-TRC20','USDT-BEP20','USDT-ERC20')),
  CONSTRAINT chk_user_payout_address_sandbox_source CHECK (source='mock' AND source_environment='SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_user_payout_address_sandbox_history (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  network VARCHAR(32) NOT NULL,
  previous_address VARCHAR(255) NULL,
  new_address VARCHAR(255) NOT NULL,
  change_type VARCHAR(16) NOT NULL,
  source VARCHAR(16) NOT NULL DEFAULT 'mock',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user_payout_address_sandbox_history_scope (run_id,user_id,network,created_at),
  CONSTRAINT chk_user_payout_address_sandbox_history_source CHECK (source='mock' AND source_environment='SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_user_payout_address_sandbox_otp (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  challenge_no VARCHAR(96) NOT NULL,
  code_hash CHAR(64) NOT NULL,
  expires_at DATETIME NOT NULL,
  consumed_at DATETIME NULL,
  attempts INT NOT NULL DEFAULT 0,
  source VARCHAR(16) NOT NULL DEFAULT 'mock',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_payout_address_sandbox_otp (run_id,user_id,challenge_no),
  KEY idx_user_payout_address_sandbox_otp_scope (run_id,user_id,created_at),
  CONSTRAINT chk_user_payout_address_sandbox_otp_no CHECK (challenge_no LIKE 'PAYOUT-%'),
  CONSTRAINT chk_user_payout_address_sandbox_otp_source CHECK (source='mock' AND source_environment='SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- Explicit market sandbox facts. These tables are never joined to production
-- wallets/positions and every key includes the acceptance RunID and account.
CREATE TABLE IF NOT EXISTS nx_market_sandbox_account (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  domain_key VARCHAR(32) NOT NULL,
  run_id VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  wallet_usdt DECIMAL(24,6) NOT NULL DEFAULT 1000.000000,
  version BIGINT NOT NULL DEFAULT 0,
  source VARCHAR(16) NOT NULL DEFAULT 'mock',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_market_sandbox_account (domain_key,run_id,user_id),
  KEY idx_market_sandbox_account_scope (run_id,user_id),
  CONSTRAINT chk_market_sandbox_account_source CHECK (source='mock' AND source_environment='SANDBOX'),
  CONSTRAINT chk_market_sandbox_account_wallet CHECK (wallet_usdt >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_market_sandbox_position (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  domain_key VARCHAR(32) NOT NULL,
  run_id VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  position_no VARCHAR(96) NOT NULL,
  product_code VARCHAR(64) NOT NULL,
  product_name VARCHAR(120) NOT NULL,
  amount_usdt DECIMAL(24,6) NOT NULL,
  apy_pct DECIMAL(12,6) NOT NULL,
  penalty_pct DECIMAL(12,6) NOT NULL,
  term_days INT NOT NULL,
  locked_at DATETIME NOT NULL,
  unlock_at DATETIME NOT NULL,
  interest_usdt DECIMAL(24,6) NOT NULL,
  status VARCHAR(32) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  source VARCHAR(16) NOT NULL DEFAULT 'mock',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_market_sandbox_position (domain_key,run_id,user_id,position_no),
  KEY idx_market_sandbox_position_scope (domain_key,run_id,user_id,created_at,id),
  CONSTRAINT chk_market_sandbox_position_source CHECK (source='mock' AND source_environment='SANDBOX'),
  CONSTRAINT chk_market_sandbox_position_amount CHECK (amount_usdt > 0),
  CONSTRAINT chk_market_sandbox_position_time CHECK (unlock_at > locked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_market_sandbox_idempotency (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  domain_key VARCHAR(32) NOT NULL,
  run_id VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  operation VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(200) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  resource_no VARCHAR(96) NOT NULL,
  source VARCHAR(16) NOT NULL DEFAULT 'mock',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  created_at DATETIME NOT NULL,
  UNIQUE KEY uk_market_sandbox_idempotency (domain_key,run_id,user_id,operation,idempotency_key),
  KEY idx_market_sandbox_idem_scope (domain_key,run_id,user_id,resource_no),
  CONSTRAINT chk_market_sandbox_idem_source CHECK (source='mock' AND source_environment='SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

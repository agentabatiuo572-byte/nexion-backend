USE nexion;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_nex_buyback_burn_pool_account (
  id TINYINT PRIMARY KEY,
  balance_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT chk_nex_buyback_burn_pool_balance CHECK (balance_usdt >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_nex_buyback_burn_pool_account(id,balance_usdt,version)
VALUES(1,0,0)
ON DUPLICATE KEY UPDATE id=VALUES(id);

CREATE TABLE IF NOT EXISTS nx_nex_buyback_burn_pool_ledger (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  entry_no VARCHAR(96) NOT NULL,
  exchange_no VARCHAR(96) NOT NULL,
  direction VARCHAR(8) NOT NULL,
  amount_usdt DECIMAL(18,6) NOT NULL,
  balance_after_usdt DECIMAL(18,6) NOT NULL,
  nex_equivalent DECIMAL(24,6) NOT NULL,
  price_usdt DECIMAL(18,8) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_nex_buyback_burn_entry (entry_no),
  UNIQUE KEY uk_nex_buyback_burn_idem (idempotency_key),
  KEY idx_nex_buyback_burn_exchange (exchange_no,created_at),
  CONSTRAINT chk_nex_buyback_burn_amount CHECK (
    direction='IN' AND amount_usdt>0 AND balance_after_usdt>=0
    AND nex_equivalent>=0 AND price_usdt>0
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_exchange_fee_allocation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  exchange_no VARCHAR(96) NOT NULL,
  total_fee_usdt DECIMAL(18,6) NOT NULL,
  burn_ratio DECIMAL(8,6) NOT NULL,
  burn_pool_usdt DECIMAL(18,6) NOT NULL,
  fee_buffer_usdt DECIMAL(18,6) NOT NULL,
  price_usdt DECIMAL(18,8) NOT NULL,
  nex_equivalent DECIMAL(24,6) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_exchange_fee_allocation_no (exchange_no),
  CONSTRAINT chk_exchange_fee_allocation_math CHECK (
    total_fee_usdt>0 AND burn_ratio=0.300000
    AND burn_pool_usdt>=0 AND fee_buffer_usdt>=0
    AND total_fee_usdt=burn_pool_usdt+fee_buffer_usdt
    AND price_usdt>0 AND nex_equivalent>=0
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

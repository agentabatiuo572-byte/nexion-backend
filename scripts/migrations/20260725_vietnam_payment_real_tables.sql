-- D1 VietQR bank rail, D3 liability category #9 and D6 VND quote.
-- This migration intentionally creates no reconciliation/account demo rows.
-- Permissions and menus are managed by the consolidated RBAC migration.

CREATE TABLE IF NOT EXISTS nx_vietqr_config (
  id BIGINT NOT NULL PRIMARY KEY,
  tolerance_vnd DECIMAL(18,0) NOT NULL,
  grace_minutes INT NOT NULL,
  per_tx_limit_usd DECIMAL(18,2) NOT NULL,
  trc20_confirmations INT NOT NULL,
  erc20_confirmations INT NOT NULL,
  bep20_confirmations INT NOT NULL,
  rotation_strategy VARCHAR(32) NOT NULL,
  updated_by VARCHAR(64) NULL,
  update_reason VARCHAR(200) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT chk_vietqr_tolerance CHECK (tolerance_vnd BETWEEN 0 AND 5000),
  CONSTRAINT chk_vietqr_grace CHECK (grace_minutes BETWEEN 0 AND 60),
  CONSTRAINT chk_vietqr_limit CHECK (per_tx_limit_usd BETWEEN 100 AND 10000),
  CONSTRAINT chk_vietqr_confirmations CHECK (
    trc20_confirmations BETWEEN 1 AND 64
    AND erc20_confirmations BETWEEN 1 AND 64
    AND bep20_confirmations BETWEEN 1 AND 64),
  CONSTRAINT chk_vietqr_rotation CHECK (rotation_strategy IN ('ROUND_ROBIN','REMAINING_CAPACITY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_vietqr_config (
  id, tolerance_vnd, grace_minutes, per_tx_limit_usd,
  trc20_confirmations, erc20_confirmations, bep20_confirmations,
  rotation_strategy, version, is_deleted
) VALUES (1,1000,10,5000,20,12,15,'ROUND_ROBIN',0,0)
ON DUPLICATE KEY UPDATE id=VALUES(id);

CREATE TABLE IF NOT EXISTS nx_vietqr_bank_account (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  bank_code VARCHAR(16) NOT NULL,
  bank_name VARCHAR(80) NOT NULL,
  account_holder VARCHAR(120) NOT NULL,
  account_number_encrypted VARCHAR(512) NOT NULL,
  account_number_hash CHAR(64) NOT NULL,
  account_number_last4 CHAR(4) NOT NULL,
  daily_cap_vnd DECIMAL(20,0) NOT NULL,
  received_today_vnd DECIMAL(20,0) NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  fuse_reason VARCHAR(200) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_vietqr_bank_account_hash (account_number_hash),
  KEY idx_vietqr_bank_account_status (status, is_deleted),
  CONSTRAINT chk_vietqr_bank_cap CHECK (daily_cap_vnd BETWEEN 1000000 AND 10000000000),
  CONSTRAINT chk_vietqr_bank_received CHECK (received_today_vnd >= 0),
  CONSTRAINT chk_vietqr_bank_status CHECK (status IN ('ACTIVE','DISABLED','FUSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_vietqr_reconciliation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  reconciliation_no VARCHAR(64) NOT NULL,
  intent_no VARCHAR(64) NULL,
  user_id BIGINT NULL,
  bank_account_id BIGINT NULL,
  view_type VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  payable_vnd DECIMAL(20,0) NULL,
  received_vnd DECIMAL(20,0) NULL,
  locked_fx_rate_vnd_per_usdt DECIMAL(18,2) NOT NULL,
  credited_usdt DECIMAL(24,6) NOT NULL DEFAULT 0,
  payment_reference VARCHAR(128) NULL,
  note VARCHAR(200) NULL,
  expires_at DATETIME NULL,
  received_at DATETIME NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_vietqr_reconciliation_no (reconciliation_no),
  UNIQUE KEY uk_vietqr_payment_reference (payment_reference),
  KEY idx_vietqr_reconciliation_queue (view_type, status, created_at),
  KEY idx_vietqr_reconciliation_user (user_id, created_at),
  CONSTRAINT chk_vietqr_reconciliation_view CHECK (
    view_type IN ('INFLIGHT','MATCHED','ORPHAN','MISMATCH','LATE')),
  CONSTRAINT chk_vietqr_reconciliation_status CHECK (
    status IN ('OPEN','CREDITED','RETURN_PENDING','RETURNED')),
  CONSTRAINT chk_vietqr_reconciliation_amount CHECK (
    (payable_vnd IS NULL OR payable_vnd >= 0)
    AND (received_vnd IS NULL OR received_vnd >= 0)
    AND locked_fx_rate_vnd_per_usdt > 0
    AND credited_usdt >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_finance_fx_quote_config (
  config_code VARCHAR(32) PRIMARY KEY,
  base_rate_vnd_per_usdt DECIMAL(18,0) NOT NULL,
  buy_spread_pct DECIMAL(8,2) NOT NULL,
  lock_window_minutes INT NOT NULL,
  updated_by VARCHAR(64) NULL,
  update_reason VARCHAR(200) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT chk_fx_base_rate CHECK (base_rate_vnd_per_usdt BETWEEN 20000 AND 35000),
  CONSTRAINT chk_fx_spread CHECK (buy_spread_pct BETWEEN 0 AND 3),
  CONSTRAINT chk_fx_lock_window CHECK (lock_window_minutes BETWEEN 5 AND 120)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_finance_fx_quote_config (
  config_code, base_rate_vnd_per_usdt, buy_spread_pct, lock_window_minutes, version, is_deleted
) VALUES ('VND_USDT',26000,1.50,30,0,0)
ON DUPLICATE KEY UPDATE config_code=VALUES(config_code);

CREATE TABLE IF NOT EXISTS nx_finance_fx_quote_history (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  base_rate_before DECIMAL(18,0) NOT NULL,
  base_rate_after DECIMAL(18,0) NOT NULL,
  buy_spread_before DECIMAL(8,2) NOT NULL,
  buy_spread_after DECIMAL(8,2) NOT NULL,
  lock_window_before INT NOT NULL,
  lock_window_after INT NOT NULL,
  operator VARCHAR(64) NOT NULL,
  reason VARCHAR(200) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_fx_quote_history_idempotency (idempotency_key),
  KEY idx_fx_quote_history_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Existing structured D3 config gains category #9 without resetting operator choices.
UPDATE nx_config_item
   SET config_value=JSON_SET(
         CAST(config_value AS JSON),
         '$.liabilityCategories.unverified_deposit',
         TRUE),
       updated_at=NOW()
 WHERE config_key IN ('treasury.d3.forecast-config','treasury.d3.forecast-config.pending')
   AND is_deleted=0
   AND config_value IS NOT NULL
   AND config_value <> ''
   AND JSON_VALID(config_value);

-- App Exchange + Genesis acceptance Sandbox only.
-- Registered by apply_startup_schema_migrations.ps1 after sandbox prerequisites.
-- No FK or trigger references a production wallet/order/holding/ledger rail.

CREATE TABLE IF NOT EXISTS nx_market_sandbox_run_lock (
  run_id VARCHAR(96) NOT NULL,
  domain_key VARCHAR(32) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (run_id,domain_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_exchange_sandbox_wallet (
  run_id VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  usdt_available DECIMAL(36,6) NOT NULL DEFAULT 0,
  nex_available DECIMAL(36,6) NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (run_id,user_id),
  KEY ix_exchange_sandbox_wallet_user (user_id),
  CONSTRAINT chk_exchange_sandbox_wallet_nonnegative CHECK (usdt_available >= 0 AND nex_available >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_exchange_sandbox_order (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  exchange_no VARCHAR(128) NOT NULL,
  idempotency_key VARCHAR(200) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  from_asset VARCHAR(12) NOT NULL,
  to_asset VARCHAR(12) NOT NULL,
  from_amount DECIMAL(36,6) NOT NULL,
  to_amount DECIMAL(36,6) NOT NULL,
  rate DECIMAL(36,12) NOT NULL,
  status VARCHAR(24) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_exchange_sandbox_no (run_id,exchange_no),
  UNIQUE KEY uk_exchange_sandbox_idem (run_id,user_id,idempotency_key),
  KEY ix_exchange_sandbox_user (run_id,user_id,created_at),
  CONSTRAINT chk_exchange_sandbox_order_amount CHECK (from_amount > 0 AND to_amount > 0 AND rate > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_exchange_sandbox_ledger (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  biz_no VARCHAR(128) NOT NULL,
  asset VARCHAR(12) NOT NULL,
  direction VARCHAR(8) NOT NULL,
  amount DECIMAL(36,6) NOT NULL,
  balance_after DECIMAL(36,6) NOT NULL,
  remark VARCHAR(255) NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_exchange_sandbox_ledger (run_id,user_id,biz_no,asset,direction),
  KEY ix_exchange_sandbox_ledger_user (run_id,user_id,created_at),
  CONSTRAINT chk_exchange_sandbox_ledger_amount CHECK (amount > 0 AND balance_after >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_exchange_sandbox_operation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  idempotency_key VARCHAR(200) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  exchange_no VARCHAR(128) NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_exchange_sandbox_operation (run_id,user_id,idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_genesis_sandbox_wallet (
  run_id VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  usdt_available DECIMAL(36,6) NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (run_id,user_id),
  KEY ix_genesis_sandbox_wallet_user (user_id),
  CONSTRAINT chk_genesis_sandbox_wallet_nonnegative CHECK (usdt_available >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_genesis_sandbox_order (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id VARCHAR(96) NOT NULL,
  order_no VARCHAR(128) NOT NULL,
  client_request_no VARCHAR(200) NOT NULL,
  user_id BIGINT NOT NULL,
  holding_no VARCHAR(128) NULL,
  order_type VARCHAR(24) NOT NULL,
  amount_usdt DECIMAL(36,6) NOT NULL,
  price_usdt DECIMAL(36,6) NOT NULL,
  seller_user_id BIGINT NULL,
  status VARCHAR(24) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_genesis_sandbox_no (run_id,order_no),
  UNIQUE KEY uk_genesis_sandbox_idem (run_id,user_id,client_request_no),
  KEY ix_genesis_sandbox_order_user (run_id,user_id,created_at),
  CONSTRAINT chk_genesis_sandbox_order_amount CHECK (amount_usdt >= 0 AND price_usdt >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_genesis_sandbox_holding (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id VARCHAR(96) NOT NULL,
  holding_no VARCHAR(128) NOT NULL,
  order_no VARCHAR(128) NOT NULL,
  user_id BIGINT NOT NULL,
  series_code VARCHAR(64) NOT NULL,
  acquired_price_usdt DECIMAL(36,6) NOT NULL,
  status VARCHAR(24) NOT NULL,
  listing_price_usdt DECIMAL(36,6) NULL,
  acquired_at DATETIME NOT NULL,
  listed_at DATETIME NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_genesis_sandbox_holding_no (run_id,holding_no),
  KEY ix_genesis_sandbox_holding_user (run_id,user_id,status),
  KEY ix_genesis_sandbox_listing (run_id,status,listing_price_usdt),
  CONSTRAINT chk_genesis_sandbox_holding_price CHECK (acquired_price_usdt >= 0 AND (listing_price_usdt IS NULL OR listing_price_usdt > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_genesis_sandbox_ledger (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  biz_no VARCHAR(128) NOT NULL,
  direction VARCHAR(8) NOT NULL,
  amount DECIMAL(36,6) NOT NULL,
  balance_after DECIMAL(36,6) NOT NULL,
  remark VARCHAR(255) NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_genesis_sandbox_ledger (run_id,user_id,biz_no,direction),
  KEY ix_genesis_sandbox_ledger_user (run_id,user_id,created_at),
  CONSTRAINT chk_genesis_sandbox_ledger_amount CHECK (amount > 0 AND balance_after >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

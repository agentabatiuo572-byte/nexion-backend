-- Acceptance-only G2 fixture persistence. These tables are never read by production G2 metrics,
-- nx_exchange_order, nx_user_wallet, nx_wallet_ledger, the production mutex, outbox or audit log.
CREATE TABLE IF NOT EXISTS nx_g2_acceptance_sandbox_batch (
  batch_no VARCHAR(80) PRIMARY KEY,
  source VARCHAR(16) NOT NULL,
  source_environment VARCHAR(16) NOT NULL,
  status VARCHAR(24) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  CONSTRAINT chk_g2_acceptance_batch_source CHECK (source = 'mock' AND source_environment = 'SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_g2_acceptance_sandbox_order (
  exchange_no VARCHAR(100) PRIMARY KEY,
  batch_no VARCHAR(80) NOT NULL,
  fixture_outcome VARCHAR(16) NOT NULL,
  status VARCHAR(24) NOT NULL,
  reason_code VARCHAR(80),
  reason VARCHAR(255),
  amount_usdt DECIMAL(20,6) NOT NULL,
  source VARCHAR(16) NOT NULL,
  source_environment VARCHAR(16) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  KEY idx_g2_acceptance_order_batch (batch_no),
  CONSTRAINT chk_g2_acceptance_order_source CHECK (source = 'mock' AND source_environment = 'SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_g2_acceptance_sandbox_ledger (
  entry_no VARCHAR(120) PRIMARY KEY,
  batch_no VARCHAR(80) NOT NULL,
  exchange_no VARCHAR(100) NOT NULL,
  asset VARCHAR(16) NOT NULL,
  direction VARCHAR(8) NOT NULL,
  amount DECIMAL(20,6) NOT NULL,
  source VARCHAR(16) NOT NULL,
  source_environment VARCHAR(16) NOT NULL,
  created_at DATETIME NOT NULL,
  KEY idx_g2_acceptance_ledger_batch (batch_no),
  CONSTRAINT chk_g2_acceptance_ledger_source CHECK (source = 'mock' AND source_environment = 'SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_g2_acceptance_sandbox_idempotency (
  command_key VARCHAR(160) PRIMARY KEY,
  batch_no VARCHAR(80) NOT NULL,
  created_at DATETIME NOT NULL,
  KEY idx_g2_acceptance_idempotency_batch (batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Acceptance-only H8 settlement facts. They are deliberately separate from
-- nx_referral_reward_settlement so production risk, BI and outbox consumers
-- cannot ingest sandbox runs.
CREATE TABLE IF NOT EXISTS nx_h8_sandbox_referral_settlement (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  settlement_no VARCHAR(96) NOT NULL,
  invited_user_id BIGINT NOT NULL,
  inviter_user_id BIGINT NOT NULL,
  newcomer_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  newcomer_nex DECIMAL(18,6) NOT NULL DEFAULT 0,
  inviter_nex DECIMAL(18,6) NOT NULL DEFAULT 0,
  lock_mode VARCHAR(24) NOT NULL DEFAULT 'risk_bucket',
  config_snapshot VARCHAR(500) NOT NULL DEFAULT '',
  operator VARCHAR(96) NOT NULL DEFAULT 'system',
  reason VARCHAR(500) NOT NULL DEFAULT 'acceptance sandbox settlement',
  idempotency_key VARCHAR(160) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'SETTLED',
  source VARCHAR(16) NOT NULL DEFAULT 'mock',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_h8_sandbox_referral_invited (invited_user_id),
  UNIQUE KEY uk_h8_sandbox_referral_no (settlement_no),
  UNIQUE KEY uk_h8_sandbox_referral_idempotency (idempotency_key),
  KEY idx_h8_sandbox_referral_inviter (inviter_user_id, created_at),
  CONSTRAINT chk_h8_sandbox_referral_amounts
    CHECK (newcomer_usdt >= 0 AND newcomer_nex >= 0 AND inviter_nex >= 0),
  CONSTRAINT chk_h8_sandbox_referral_source
    CHECK (source = 'mock' AND source_environment = 'SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- Acceptance-only H8 proof ledger. It is deliberately separate from
-- nx_wallet_ledger so production BI, regulatory and user-bill consumers can
-- never ingest MOCK_REFERRAL facts from a shared table.
CREATE TABLE IF NOT EXISTS nx_h8_sandbox_referral_ledger (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  settlement_no VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  asset VARCHAR(16) NOT NULL,
  amount DECIMAL(18,6) NOT NULL,
  balance_after DECIMAL(18,6) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',
  source VARCHAR(16) NOT NULL DEFAULT 'mock',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  remark VARCHAR(255) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_h8_sandbox_referral_ledger_fact (settlement_no, user_id, asset),
  KEY idx_h8_sandbox_referral_ledger_user_time (user_id, created_at),
  CONSTRAINT chk_h8_sandbox_referral_ledger_amount CHECK (amount > 0),
  CONSTRAINT chk_h8_sandbox_referral_ledger_source
    CHECK (source = 'mock' AND source_environment = 'SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

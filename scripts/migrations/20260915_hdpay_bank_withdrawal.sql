-- Additive only. No enabling configuration, wallet changes or credentials.
CREATE TABLE IF NOT EXISTS nx_bank_payout_beneficiary (
  user_id BIGINT NOT NULL PRIMARY KEY,
  beneficiary_no VARCHAR(40) NOT NULL,
  bank_code VARCHAR(16) NOT NULL,
  masked_account VARCHAR(40) NOT NULL,
  recipient_cipher TEXT NOT NULL,
  effective_at DATETIME(6) NOT NULL,
  next_change_at DATETIME(6) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_bank_beneficiary_no (beneficiary_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_bank_payout_quote (
  quote_no VARCHAR(40) NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  beneficiary_no VARCHAR(40) NOT NULL,
  beneficiary_version BIGINT NOT NULL,
  bank_code VARCHAR(16) NOT NULL,
  masked_account VARCHAR(40) NOT NULL,
  recipient_cipher TEXT NOT NULL,
  amount_usdt DECIMAL(24,6) NOT NULL,
  fee_usdt DECIMAL(24,6) NOT NULL,
  net_usdt DECIMAL(24,6) NOT NULL,
  rate_vnd DECIMAL(24,6) NOT NULL,
  amount_vnd DECIMAL(24,0) NOT NULL,
  d7_version BIGINT NOT NULL,
  d5_version VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  withdrawal_no VARCHAR(96) NULL,
  cancelled_at DATETIME(6) NULL,
  UNIQUE KEY uk_bank_quote_withdrawal (withdrawal_no),
  KEY idx_bank_quote_user (user_id,created_at),
  CONSTRAINT chk_bank_quote_money CHECK (amount_usdt > 0 AND fee_usdt >= 0 AND net_usdt > 0
     AND amount_usdt = fee_usdt + net_usdt AND rate_vnd > 0 AND amount_vnd > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_hdpay_payout (
  withdrawal_no VARCHAR(96) NOT NULL PRIMARY KEY,
  quote_no VARCHAR(40) NOT NULL,
  user_id BIGINT NOT NULL,
  state VARCHAR(24) NOT NULL DEFAULT 'READY',
  provider_order_id BIGINT NULL,
  provider_status INT NULL,
  approved_risk_hash CHAR(64) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  next_query_at DATETIME(6) NULL,
  last_error VARCHAR(96) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_hdpay_payout_quote (quote_no),
  UNIQUE KEY uk_hdpay_payout_provider (provider_order_id),
  KEY idx_hdpay_payout_recovery (state,next_query_at),
  CONSTRAINT chk_hdpay_payout_state CHECK (state IN ('READY','DISPATCHING','PENDING','PAID','FAILED','MANUAL_REVIEW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_hdpay_payout_callback (
  event_hash CHAR(64) NOT NULL PRIMARY KEY,
  withdrawal_no VARCHAR(96) NOT NULL,
  provider_order_id BIGINT NOT NULL,
  provider_status INT NOT NULL,
  amount_vnd DECIMAL(24,0) NOT NULL,
  received_at DATETIME(6) NOT NULL,
  KEY idx_hdpay_payout_callback_order (withdrawal_no,provider_status),
  CONSTRAINT chk_hdpay_payout_callback_status CHECK (provider_status BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

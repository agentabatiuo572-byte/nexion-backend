-- Evidence only: no provider credentials, account approval, channel enablement or wallet mutation.
CREATE TABLE IF NOT EXISTS nx_bank_beneficiary_verification (
  beneficiary_no VARCHAR(40) NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  beneficiary_version BIGINT NOT NULL,
  verification_status VARCHAR(16) NOT NULL DEFAULT 'pending',
  payout_capability VARCHAR(16) NOT NULL DEFAULT 'unknown',
  ownership_status VARCHAR(16) NOT NULL DEFAULT 'unknown',
  account_type VARCHAR(24) NOT NULL DEFAULT 'unknown',
  reason_code VARCHAR(96) NULL,
  checked_at DATETIME(6) NULL,
  expires_at DATETIME(6) NULL,
  evidence_ref VARCHAR(128) NULL,
  capability_version VARCHAR(64) NULL,
  provider VARCHAR(64) NULL,
  requested_at DATETIME(6) NOT NULL,
  KEY idx_bank_verification_owner (user_id, beneficiary_version),
  CONSTRAINT chk_bank_verification_status CHECK (verification_status IN ('pending','verified','rejected','unavailable')),
  CONSTRAINT chk_bank_verification_capability CHECK (payout_capability IN ('supported','unsupported','unknown')),
  CONSTRAINT chk_bank_verification_ownership CHECK (ownership_status IN ('matched','mismatched','unknown')),
  CONSTRAINT chk_bank_verification_type CHECK (account_type IN ('payment_account','credit_card','prepaid','unknown')),
  CONSTRAINT chk_bank_verified_evidence CHECK (verification_status <> 'verified' OR
    (checked_at IS NOT NULL AND expires_at IS NOT NULL AND expires_at > checked_at
      AND evidence_ref IS NOT NULL AND CHAR_LENGTH(TRIM(evidence_ref)) > 0
      AND capability_version IS NOT NULL AND CHAR_LENGTH(TRIM(capability_version)) > 0
      AND provider IS NOT NULL AND CHAR_LENGTH(TRIM(provider)) > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Account-scoped discovery runs while holding the account mutex; avoid scanning other owners' payouts.
SET @bank_payout_owner_index_sql = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_hdpay_payout' AND INDEX_NAME='idx_hdpay_payout_owner') = 0,
  'ALTER TABLE nx_hdpay_payout ADD INDEX idx_hdpay_payout_owner(user_id,created_at,withdrawal_no)',
  'SELECT 1');
PREPARE bank_payout_owner_index_stmt FROM @bank_payout_owner_index_sql;
EXECUTE bank_payout_owner_index_stmt;
DEALLOCATE PREPARE bank_payout_owner_index_stmt;

-- Retained expiry tombstones prohibit a late submit even if the wall clock subsequently moves backwards.
CREATE TABLE IF NOT EXISTS nx_bank_payout_quote_expiry (
  quote_no VARCHAR(40) NOT NULL PRIMARY KEY,
  expired_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

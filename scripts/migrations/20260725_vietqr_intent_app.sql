-- App VietQR canonical intent. Run after 20260725_vietnam_payment_real_tables.sql.
-- No demo intent or reconciliation rows are created.

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE()
      AND table_name='nx_vietqr_bank_account'
      AND column_name='received_business_date') = 0,
  'ALTER TABLE nx_vietqr_bank_account ADD COLUMN received_business_date DATE NULL AFTER received_today_vnd',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE nx_vietqr_bank_account
   SET received_business_date = DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 7 HOUR))
 WHERE received_business_date IS NULL AND received_today_vnd > 0;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE()
      AND table_name='nx_vietqr_reconciliation'
      AND column_name='intent_transition_required') = 0,
  'ALTER TABLE nx_vietqr_reconciliation ADD COLUMN intent_transition_required TINYINT NOT NULL DEFAULT 1 AFTER received_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_vietqr_intent (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  intent_no VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  create_idempotency_key VARCHAR(128) NOT NULL,
  create_request_hash CHAR(64) NOT NULL,
  requested_usdt DECIMAL(24,2) NOT NULL,
  payable_vnd DECIMAL(20,0) NOT NULL,
  credited_usdt DECIMAL(24,6) NOT NULL DEFAULT 0,
  received_vnd DECIMAL(20,0) NULL,
  locked_fx_rate_vnd_per_usdt DECIMAL(18,2) NOT NULL,
  fx_quote_version BIGINT NOT NULL,
  bank_account_id BIGINT NOT NULL,
  memo_code VARCHAR(32) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'AWAITING_PAYMENT',
  expires_at DATETIME NOT NULL,
  matched_at DATETIME NULL,
  cancel_idempotency_key VARCHAR(128) NULL,
  cancel_request_hash CHAR(64) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_vietqr_intent_no (intent_no),
  UNIQUE KEY uk_vietqr_intent_user_create_key (user_id, create_idempotency_key),
  UNIQUE KEY uk_vietqr_intent_memo (memo_code),
  UNIQUE KEY uk_vietqr_intent_user_cancel_key (user_id, cancel_idempotency_key),
  KEY idx_vietqr_intent_user_created (user_id, created_at),
  KEY idx_vietqr_intent_match (memo_code, status, expires_at),
  KEY idx_vietqr_intent_account_active (bank_account_id, status, expires_at),
  CONSTRAINT chk_vietqr_intent_requested CHECK (requested_usdt >= 10),
  CONSTRAINT chk_vietqr_intent_amounts CHECK (
    payable_vnd > 0 AND credited_usdt >= 0
    AND (received_vnd IS NULL OR received_vnd > 0)
    AND locked_fx_rate_vnd_per_usdt > 0),
  CONSTRAINT chk_vietqr_intent_status CHECK (
    status IN (
      'AWAITING_PAYMENT','RECEIPT_REVIEW','CREDITED','EXPIRED','MISMATCH_REVIEW',
      'LATE_REVIEW','CANCELLED','RETURN_PENDING','RETURNED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE constraint_schema=DATABASE()
      AND table_name='nx_vietqr_intent'
      AND constraint_name='chk_vietqr_intent_status'
      AND constraint_type='CHECK') > 0,
  'ALTER TABLE nx_vietqr_intent DROP CHECK chk_vietqr_intent_status, ADD CONSTRAINT chk_vietqr_intent_status CHECK (status IN (''AWAITING_PAYMENT'',''RECEIPT_REVIEW'',''CREDITED'',''EXPIRED'',''MISMATCH_REVIEW'',''LATE_REVIEW'',''CANCELLED'',''RETURN_PENDING'',''RETURNED''))',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- H7 sandbox popup state is isolated by acceptance RunID and account.
-- Definitions remain in nx_growth_voucher; this table must never be joined
-- without both run_id and user_id predicates.
CREATE TABLE IF NOT EXISTS nx_voucher_popup_sandbox_state (
  run_id VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  voucher_id VARCHAR(80) NOT NULL,
  last_seen_at BIGINT NOT NULL DEFAULT 0,
  session_count BIGINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  claim_status VARCHAR(20) NOT NULL DEFAULT 'UNCLAIMED',
  claim_id VARCHAR(96) NULL,
  claim_idempotency_key VARCHAR(128) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (run_id,user_id,voucher_id),
  KEY idx_voucher_popup_sandbox_user (run_id,user_id),
  KEY idx_voucher_popup_sandbox_voucher (run_id,voucher_id),
  UNIQUE KEY uk_voucher_popup_sandbox_claim_idem (run_id,user_id,voucher_id,claim_idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Sandbox claims stay in the same run/account-scoped projection.  They must
-- never create a row in nx_growth_voucher_grant (the production ownership
-- ledger).  The primary key is also the concurrency fence: one claim per
-- voucher in a run/account, while the idempotency key makes a replay safe.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_voucher_popup_sandbox_state' AND COLUMN_NAME='claim_status')=0,
  'ALTER TABLE nx_voucher_popup_sandbox_state ADD COLUMN claim_status VARCHAR(20) NOT NULL DEFAULT ''UNCLAIMED'' AFTER version','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_voucher_popup_sandbox_state' AND COLUMN_NAME='claim_id')=0,
  'ALTER TABLE nx_voucher_popup_sandbox_state ADD COLUMN claim_id VARCHAR(96) NULL AFTER claim_status','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_voucher_popup_sandbox_state' AND COLUMN_NAME='claim_idempotency_key')=0,
  'ALTER TABLE nx_voucher_popup_sandbox_state ADD COLUMN claim_idempotency_key VARCHAR(128) NULL AFTER claim_id','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_voucher_popup_sandbox_state' AND INDEX_NAME='uk_voucher_popup_sandbox_claim_idem')=0,
  'ALTER TABLE nx_voucher_popup_sandbox_state ADD UNIQUE KEY uk_voucher_popup_sandbox_claim_idem (run_id,user_id,voucher_id,claim_idempotency_key)','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

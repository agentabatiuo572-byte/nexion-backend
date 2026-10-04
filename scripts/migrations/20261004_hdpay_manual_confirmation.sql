-- Independent ADMIN confirmation; never a provider callback or bank reconciliation.
CREATE TABLE IF NOT EXISTS nx_hdpay_manual_confirmation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  confirmation_no VARCHAR(64) NOT NULL,
  merchant_order_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  received_vnd DECIMAL(20,0) NOT NULL,
  credited_usdt DECIMAL(24,6) NOT NULL,
  payment_reference VARCHAR(128) NOT NULL,
  received_at DATETIME NOT NULL,
  evidence_ref VARCHAR(64) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  operator VARCHAR(128) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  confirmation_source VARCHAR(32) NOT NULL DEFAULT 'ADMIN_MANUAL',
  reserve_source VARCHAR(32) NOT NULL,
  bank_receipt_id BIGINT NULL,
  bank_receipt_version BIGINT NULL,
  bank_reconciliation_no VARCHAR(64) NULL,
  previous_intent_status VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_hdpay_manual_confirmation_no (confirmation_no),
  UNIQUE KEY uk_hdpay_manual_confirmation_order (merchant_order_id),
  UNIQUE KEY uk_hdpay_manual_confirmation_reference (payment_reference),
  UNIQUE KEY uk_hdpay_manual_confirmation_receipt (bank_receipt_id),
  CONSTRAINT chk_hdpay_manual_confirmation_source CHECK (confirmation_source = 'ADMIN_MANUAL'),
  CONSTRAINT chk_hdpay_manual_confirmation_reserve CHECK (
    (reserve_source = 'RESERVE_LEDGER' AND bank_receipt_id IS NULL
     AND bank_receipt_version IS NULL AND bank_reconciliation_no IS NULL)
    OR (reserve_source = 'EXISTING_BANK_RECEIPT' AND bank_receipt_id IS NOT NULL
     AND bank_receipt_version IS NOT NULL AND bank_reconciliation_no IS NOT NULL)),
  CONSTRAINT chk_hdpay_manual_confirmation_amount CHECK (received_vnd > 0 AND credited_usdt > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Bound receipt locks to one intent rather than scanning the whole reconciliation table.
SET @hdpay_receipt_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema=DATABASE() AND table_name='nx_vietqr_reconciliation'
     AND index_name='idx_vietqr_reconciliation_intent_received'
);
SET @hdpay_receipt_index_ddl = IF(@hdpay_receipt_index_exists=0,
  'ALTER TABLE nx_vietqr_reconciliation ADD INDEX idx_vietqr_reconciliation_intent_received (intent_no,is_deleted,received_vnd,id)',
  'SELECT 1');
PREPARE hdpay_receipt_index_statement FROM @hdpay_receipt_index_ddl;
EXECUTE hdpay_receipt_index_statement;
DEALLOCATE PREPARE hdpay_receipt_index_statement;

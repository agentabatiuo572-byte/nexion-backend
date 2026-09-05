-- HDPay callback settlement is additive and rerunnable. A signed callback is
-- only a trigger; wallet credit still requires an authoritative provider query.

CREATE TABLE IF NOT EXISTS nx_hdpay_settlement_review (
  id BIGINT NOT NULL AUTO_INCREMENT,
  review_no CHAR(64) NOT NULL,
  merchant_order_id VARCHAR(64) NOT NULL,
  provider_order_id VARCHAR(64) NOT NULL,
  callback_payload_hash CHAR(64) NOT NULL,
  reason VARCHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_nx_hdpay_review_no (review_no),
  UNIQUE KEY uk_nx_hdpay_review_callback (callback_payload_hash),
  KEY idx_nx_hdpay_review_status_created (status,created_at),
  KEY idx_nx_hdpay_review_merchant_created (merchant_order_id,created_at),
  CONSTRAINT chk_nx_hdpay_review_status CHECK (status IN ('OPEN','RESOLVED','DISMISSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_hdpay_payin_order'
    AND column_name='settlement_status')=0,
  'ALTER TABLE nx_hdpay_payin_order ADD COLUMN settlement_status VARCHAR(24) NOT NULL DEFAULT ''UNSETTLED'' AFTER provider_status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_hdpay_payin_order'
    AND column_name='settled_usdt')=0,
  'ALTER TABLE nx_hdpay_payin_order ADD COLUMN settled_usdt DECIMAL(18,6) NULL AFTER settlement_status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_hdpay_payin_order'
    AND column_name='wallet_ledger_biz_no')=0,
  'ALTER TABLE nx_hdpay_payin_order ADD COLUMN wallet_ledger_biz_no VARCHAR(96) NULL AFTER settled_usdt',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_hdpay_payin_order'
    AND column_name='settled_at')=0,
  'ALTER TABLE nx_hdpay_payin_order ADD COLUMN settled_at DATETIME NULL AFTER wallet_ledger_biz_no',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='nx_hdpay_payin_order'
    AND index_name='idx_nx_hdpay_payin_settlement_updated')=0,
  'ALTER TABLE nx_hdpay_payin_order ADD KEY idx_nx_hdpay_payin_settlement_updated (settlement_status,updated_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.check_constraints
  WHERE constraint_schema=DATABASE() AND constraint_name='chk_nx_hdpay_payin_settlement')=0,
  'ALTER TABLE nx_hdpay_payin_order ADD CONSTRAINT chk_nx_hdpay_payin_settlement CHECK (settlement_status IN (''UNSETTLED'',''CREDITED'',''MANUAL_REVIEW''))',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_hdpay_callback_inbox'
    AND column_name='claim_token')=0,
  'ALTER TABLE nx_hdpay_callback_inbox ADD COLUMN claim_token CHAR(36) NULL AFTER processing_status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_hdpay_callback_inbox'
    AND column_name='claimed_at')=0,
  'ALTER TABLE nx_hdpay_callback_inbox ADD COLUMN claimed_at DATETIME NULL AFTER claim_token',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_hdpay_callback_inbox'
    AND column_name='provider_query_status')=0,
  'ALTER TABLE nx_hdpay_callback_inbox ADD COLUMN provider_query_status INT NULL AFTER claimed_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='nx_hdpay_callback_inbox'
    AND index_name='idx_nx_hdpay_callback_recovery')=0,
  'ALTER TABLE nx_hdpay_callback_inbox ADD KEY idx_nx_hdpay_callback_recovery (processing_status,updated_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_hdpay_callback_inbox'
    AND column_name='result_code')=0,
  'ALTER TABLE nx_hdpay_callback_inbox ADD COLUMN result_code VARCHAR(64) NULL AFTER provider_query_status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_hdpay_callback_inbox'
    AND column_name='processed_at')=0,
  'ALTER TABLE nx_hdpay_callback_inbox ADD COLUMN processed_at DATETIME NULL AFTER result_code',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.check_constraints
  WHERE constraint_schema=DATABASE() AND constraint_name='chk_nx_hdpay_callback_processing'
    AND LOCATE('CREDITED',UPPER(check_clause))=0)>0,
  'ALTER TABLE nx_hdpay_callback_inbox DROP CHECK chk_nx_hdpay_callback_processing',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.check_constraints
  WHERE constraint_schema=DATABASE() AND constraint_name='chk_nx_hdpay_callback_processing')=0,
  'ALTER TABLE nx_hdpay_callback_inbox ADD CONSTRAINT chk_nx_hdpay_callback_processing CHECK (processing_status IN (''PROCESSING'',''OBSERVED'',''AMOUNT_MISMATCH'',''CREDITED'',''MANUAL_REVIEW''))',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

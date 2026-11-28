-- Cross-checks from finance reconciliation. Empty coverage has no implied zero.
CREATE TABLE IF NOT EXISTS nx_support_performance_channel_review (
  month_key CHAR(7) NOT NULL,
  channel VARCHAR(40) NOT NULL,
  coverage_from DATETIME(6) NOT NULL,
  coverage_to DATETIME(6) NOT NULL,
  evidence_digest CHAR(64) NOT NULL,
  evidence_ref VARCHAR(255) NOT NULL,
  verified_by BIGINT NOT NULL,
  verified_at DATETIME(6) NOT NULL,
  PRIMARY KEY (month_key, channel, coverage_to)
);

SET @b80_bank_amount_ddl = IF(
  EXISTS (SELECT 1 FROM information_schema.columns
           WHERE table_schema=DATABASE() AND table_name='nx_purchase_payment_intent'
             AND column_name='confirmed_bank_amount_vnd'),
  'DO 0',
  'ALTER TABLE nx_purchase_payment_intent ADD COLUMN confirmed_bank_amount_vnd DECIMAL(20,0) NULL'
);
PREPARE b80_bank_amount_stmt FROM @b80_bank_amount_ddl;
EXECUTE b80_bank_amount_stmt;
DEALLOCATE PREPARE b80_bank_amount_stmt;

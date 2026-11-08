-- New executions retain their actual refund; historical amounts remain backed by their original bill/ledger.
SET @b37_amount_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_order' AND COLUMN_NAME='refund_amount_usdt'),
  'SELECT 1',
  'ALTER TABLE nx_order ADD COLUMN refund_amount_usdt DECIMAL(18,6) NULL DEFAULT NULL');
PREPARE b37_amount_stmt FROM @b37_amount_ddl;
EXECUTE b37_amount_stmt;
DEALLOCATE PREPARE b37_amount_stmt;

-- Tokenized payment methods keep only safe display facts. PAN and CVV remain
-- exclusively inside the provider-hosted field; expiry is the PSP-returned
-- MM/YY label used by App and Ops projections.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'nx_wallet_bank_card'
      AND COLUMN_NAME = 'expiry_label') = 0,
  'ALTER TABLE nx_wallet_bank_card ADD COLUMN expiry_label VARCHAR(5) NULL AFTER last4',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

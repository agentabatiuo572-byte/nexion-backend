-- Keep the verified refund separate from the original fulfilment receipt.
SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_purchase_payment_intent' AND COLUMN_NAME='refund_receipt_json'),
 'SELECT 1','ALTER TABLE nx_purchase_payment_intent ADD COLUMN refund_receipt_json JSON NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_purchase_payment_intent' AND COLUMN_NAME='refund_transfer_key'),
 'SELECT 1','ALTER TABLE nx_purchase_payment_intent ADD COLUMN refund_transfer_key VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_purchase_payment_intent' AND INDEX_NAME='uk_purchase_refund_transfer'),
 'SELECT 1','ALTER TABLE nx_purchase_payment_intent ADD UNIQUE KEY uk_purchase_refund_transfer(refund_transfer_key)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

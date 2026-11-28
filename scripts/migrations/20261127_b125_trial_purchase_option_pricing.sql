-- Add per-device trial pricing alongside the existing ordered product numbers.
SET @sql = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_trial_purchase_options' AND COLUMN_NAME='pricing_json'),
 'SELECT 1','ALTER TABLE nx_trial_purchase_options ADD COLUMN pricing_json JSON NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

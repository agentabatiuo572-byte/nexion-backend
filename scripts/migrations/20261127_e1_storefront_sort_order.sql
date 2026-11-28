-- Keep the shared catalogue order and store the In Sale display override separately.
SET @stmt = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_product' AND COLUMN_NAME='storefront_sort_order'),
  'SELECT 1', 'ALTER TABLE nx_product ADD COLUMN storefront_sort_order INT NULL');
PREPARE stmt FROM @stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- B97: display-only values. Existing and newly inserted products default to 0 and visible.
SET @sql = IF(EXISTS (SELECT 1 FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_product' AND COLUMN_NAME='display_sold_base'),
  'SELECT 1', 'ALTER TABLE nx_product ADD COLUMN display_sold_base INT NOT NULL DEFAULT 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(EXISTS (SELECT 1 FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_product' AND COLUMN_NAME='live_activity_visible'),
  'SELECT 1', 'ALTER TABLE nx_product ADD COLUMN live_activity_visible TINYINT(1) NOT NULL DEFAULT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

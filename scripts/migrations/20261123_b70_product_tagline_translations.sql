-- B70-BE-02: optional localized product subtitles. The existing tagline column
-- stays the English base and the fallback for every missing translation.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_product'
                  AND COLUMN_NAME='tagline_translations_json') = 0,
  'ALTER TABLE nx_product ADD COLUMN tagline_translations_json JSON NULL AFTER tagline',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_device_sku'
                  AND COLUMN_NAME='tagline_translations_json') = 0,
  'ALTER TABLE nx_admin_device_sku ADD COLUMN tagline_translations_json JSON NULL AFTER tagline',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- F4 leadership-pool usage rows were originally created before the denormalized
-- quota/product/order fields were added to the read model. Keep this migration
-- idempotent so existing installations can be upgraded before the API starts.

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_team_hardware_quota_usage' AND COLUMN_NAME='quota_code')=0,
  'ALTER TABLE nx_team_hardware_quota_usage ADD COLUMN quota_code VARCHAR(32) NULL AFTER quota_tier_id',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_team_hardware_quota_usage' AND COLUMN_NAME='product_no')=0,
  'ALTER TABLE nx_team_hardware_quota_usage ADD COLUMN product_no VARCHAR(64) NULL AFTER quota_code',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_team_hardware_quota_usage' AND COLUMN_NAME='order_no')=0,
  'ALTER TABLE nx_team_hardware_quota_usage ADD COLUMN order_no VARCHAR(64) NULL AFTER user_id',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_team_hardware_quota_usage' AND COLUMN_NAME='remark')=0,
  'ALTER TABLE nx_team_hardware_quota_usage ADD COLUMN remark VARCHAR(255) NULL AFTER status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE nx_team_hardware_quota_usage u
JOIN nx_team_hardware_quota_tier t ON t.id=u.quota_tier_id
SET u.quota_code=COALESCE(u.quota_code,t.quota_code),
    u.product_no=COALESCE(u.product_no,t.product_no)
WHERE u.quota_code IS NULL OR u.product_no IS NULL;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_team_hardware_quota_usage' AND INDEX_NAME='idx_team_hardware_quota_usage_order')=0,
  'ALTER TABLE nx_team_hardware_quota_usage ADD KEY idx_team_hardware_quota_usage_order (order_no)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

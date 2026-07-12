-- I3 notification runtime closure. Idempotent on MySQL 8.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification' AND COLUMN_NAME = 'priority') = 0,
  'ALTER TABLE nx_notification ADD COLUMN priority VARCHAR(16) NOT NULL DEFAULT ''normal'' AFTER type', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification' AND COLUMN_NAME = 'cta_label') = 0,
  'ALTER TABLE nx_notification ADD COLUMN cta_label VARCHAR(64) NULL AFTER body', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification' AND COLUMN_NAME = 'cta_href') = 0,
  'ALTER TABLE nx_notification ADD COLUMN cta_href VARCHAR(255) NULL AFTER cta_label', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification' AND COLUMN_NAME = 'read_at') = 0,
  'ALTER TABLE nx_notification ADD COLUMN read_at DATETIME NULL AFTER read_flag', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification_campaign' AND COLUMN_NAME = 'body_vi') = 0,
  'ALTER TABLE nx_notification_campaign ADD COLUMN body_vi TEXT NULL AFTER body_zh', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
UPDATE nx_notification_campaign SET body_vi = body_zh WHERE body_vi IS NULL;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification_campaign' AND COLUMN_NAME = 'body_vi' AND IS_NULLABLE = 'YES') > 0,
  'ALTER TABLE nx_notification_campaign MODIFY COLUMN body_vi TEXT NOT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification_campaign' AND COLUMN_NAME = 'cta_label') = 0,
  'ALTER TABLE nx_notification_campaign ADD COLUMN cta_label VARCHAR(64) NULL AFTER swipe_to', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification_campaign' AND COLUMN_NAME = 'cta_href') = 0,
  'ALTER TABLE nx_notification_campaign ADD COLUMN cta_href VARCHAR(255) NULL AFTER cta_label', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO nx_admin_permission (
  permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted
) VALUES (
  'content_i3_critical_send', '通知Campaign-critical下发(合规/监管高敏)', 'API', '/content/notifications', 'HIGH', 0, 1, 0
) ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name), perm_type='HIGH', status=1, is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id
  FROM nx_admin_role r
  JOIN nx_admin_permission p ON p.permission_code='content_i3_critical_send'
 WHERE r.role_code IN ('SUPER_ADMIN', 'CONTENT', 'RISK');

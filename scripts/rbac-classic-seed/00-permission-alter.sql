-- 经典 RBAC 迁移：nx_admin_permission 加 3 字段（关联菜单 / 权限类型 / 放大流出标志）
-- 供 A8 权限字典展示 + 角色配置面分组。幂等（已存在则跳过）。
-- 手动执行（schema.sql 不自动跑，见 schema-manual-init 记忆）：
--   mysql -uroot -p nexion < scripts/rbac-classic-seed/00-permission-alter.sql

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_permission' AND COLUMN_NAME = 'menu_id') = 0,
  'ALTER TABLE nx_admin_permission ADD COLUMN menu_id BIGINT NULL AFTER resource_path, ADD KEY idx_admin_permission_menu (menu_id)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_permission' AND COLUMN_NAME = 'perm_type') = 0,
  'ALTER TABLE nx_admin_permission ADD COLUMN perm_type VARCHAR(16) NULL AFTER resource_type',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_permission' AND COLUMN_NAME = 'amplifies') = 0,
  'ALTER TABLE nx_admin_permission ADD COLUMN amplifies TINYINT NOT NULL DEFAULT 0 AFTER perm_type',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

USE nexion;

CREATE TABLE IF NOT EXISTS admin_menu (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  menu_code VARCHAR(96) NOT NULL,
  menu_name VARCHAR(96) NOT NULL,
  parent_id BIGINT NULL,
  route_path VARCHAR(255) NOT NULL,
  icon VARCHAR(64) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  remark VARCHAR(255) NULL,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_menu_code (menu_code),
  KEY idx_admin_menu_parent (parent_id),
  KEY idx_admin_menu_sort (sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS admin_role_menu (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_id BIGINT NOT NULL,
  menu_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_role_menu (role_id, menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

UPDATE admin_permission
SET resource_path = '/auth/access-control/roles/*/permissions,/auth/access-control/roles/*/menus',
    updated_at = NOW()
WHERE permission_code = 'PERM_ROLE_PERMISSION_ASSIGN'
  AND resource_type = 'API';

INSERT INTO admin_menu (id, menu_code, menu_name, parent_id, route_path, icon, sort_order, remark, status)
VALUES
  (10, 'MENU_HOME', '首页', NULL, '/home', 'HomeFilled', 10, '后台首页菜单', 1),
  (20, 'MENU_UMS', '权限', NULL, '/ums', 'Lock', 20, '权限管理目录', 1),
  (21, 'MENU_UMS_ADMIN', '管理员列表', 20, '/ums/admin', 'User', 21, '管理员维护菜单', 1),
  (22, 'MENU_UMS_ROLE', '角色列表', 20, '/ums/role', 'UserFilled', 22, '角色维护菜单', 1),
  (23, 'MENU_UMS_MENU', '菜单管理', 20, '/ums/menu', 'Menu', 23, '后台菜单维护入口', 1),
  (24, 'MENU_UMS_PERMISSION', 'API 权限', 20, '/ums/permission', 'Key', 24, 'API 权限维护菜单', 1),
  (30, 'MENU_OPS', '运营', NULL, '/ops', 'Monitor', 30, '运营管理目录', 1),
  (31, 'MENU_OPS_DEVICE', '设备运营', 30, '/ops/device', 'Cpu', 31, '设备运营菜单', 1),
  (32, 'MENU_OPS_WALLET', '钱包与通知', 30, '/ops/wallet', 'Wallet', 32, '钱包、凭证、通知菜单', 1),
  (40, 'MENU_TEAM', '团队', NULL, '/team', 'Share', 40, '团队管理目录', 1),
  (41, 'MENU_TEAM_RANK_CONFIG', '等级配置', 40, '/team/rank-config', 'Medal', 41, '等级配置菜单', 1),
  (42, 'MENU_TEAM_RANK_EVALUATE', '等级评估', 40, '/team/rank-evaluate', 'Trophy', 42, '等级评估菜单', 1),
  (43, 'MENU_TEAM_COMMISSION_RECORDS', '佣金记录', 40, '/team/commission-records', 'Tickets', 43, '佣金记录菜单', 1),
  (44, 'MENU_TEAM_COMMISSION_SETTLE', '佣金结算', 40, '/team/commission-settle', 'Money', 44, '佣金结算菜单', 1)
ON DUPLICATE KEY UPDATE
  menu_name = VALUES(menu_name),
  parent_id = VALUES(parent_id),
  route_path = VALUES(route_path),
  icon = VALUES(icon),
  sort_order = VALUES(sort_order),
  remark = VALUES(remark),
  status = VALUES(status);

INSERT IGNORE INTO admin_role_menu (role_id, menu_id)
SELECT rp.role_id, m.id
FROM admin_role_permission rp
JOIN admin_permission p ON p.id = rp.permission_id
JOIN admin_menu m ON m.route_path = p.resource_path
WHERE rp.is_deleted = 0
  AND p.resource_type = 'MENU';

INSERT IGNORE INTO admin_role_menu (role_id, menu_id)
SELECT 1, id FROM admin_menu;

UPDATE admin_role_permission rp
JOIN admin_permission p ON p.id = rp.permission_id
SET rp.is_deleted = 1, rp.updated_at = NOW()
WHERE p.resource_type = 'MENU';

UPDATE admin_permission
SET is_deleted = 1, status = 0, updated_at = NOW()
WHERE resource_type = 'MENU';

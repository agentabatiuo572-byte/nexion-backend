USE nexion;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- Local system baseline only: admin login, RBAC, navigation, and platform config.

INSERT INTO nx_admin (id, username, password_hash, nickname, email, phone, super_admin, status)
VALUES
  (1, 'superadmin', '$2a$10$OiCP0hEdnWNuxl/Q6PQ.juoWSzQCXFbvrLheJ.TYywnoyiZY7s19C', 'Super Admin', 'admin@nexion.ai', NULL, 1, 1)
ON DUPLICATE KEY UPDATE
  password_hash = VALUES(password_hash),
  nickname = VALUES(nickname),
  email = VALUES(email),
  super_admin = VALUES(super_admin),
  status = VALUES(status),
  is_deleted = 0;

INSERT INTO nx_admin_role (id, role_code, role_name, remark, status)
VALUES
  (1, 'SUPER_ADMIN', 'Super Administrator', 'Platform owner role with all permissions.', 1),
  (2, 'OPS_ADMIN', 'Operations Administrator', 'Daily operations role for compute, wallet, team, and content.', 1)
ON DUPLICATE KEY UPDATE
  role_name = VALUES(role_name),
  remark = VALUES(remark),
  status = VALUES(status),
  is_deleted = 0;

UPDATE nx_admin_permission
SET permission_code = CONCAT(permission_code, '_LEGACY_', id),
    status = 0,
    is_deleted = 1,
    updated_at = NOW()
WHERE id BETWEEN 101 AND 110
  AND resource_type = 'API';

INSERT INTO nx_admin_permission (id, permission_code, permission_name, resource_type, resource_path, remark, status)
VALUES
  (1, 'PERM_ADMIN_READ', 'Read admins', 'API', '/api/admin/platform/accounts/**', NULL, 1),
  (2, 'PERM_ADMIN_WRITE', 'Write admins', 'API', '/api/admin/platform/accounts/**', NULL, 1),
  (3, 'PERM_ADMIN_ROLE_ASSIGN', 'Assign admin roles', 'API', '/api/admin/platform/accounts/*/role', NULL, 1),
  (4, 'PERM_ROLE_READ', 'Read roles', 'API', '/api/admin/platform/rbac/**', NULL, 1),
  (5, 'PERM_ROLE_WRITE', 'Write roles', 'API', '/api/admin/platform/rbac/**', NULL, 1),
  (6, 'PERM_ROLE_PERMISSION_ASSIGN', 'Assign role permissions', 'API', '/api/admin/platform/rbac/actions/*/grants', NULL, 1),
  (7, 'PERM_PERMISSION_READ', 'Read permissions', 'API', '/api/admin/platform/rbac/**', NULL, 1),
  (8, 'PERM_PERMISSION_WRITE', 'Write permissions', 'API', '/api/admin/platform/rbac/**', NULL, 1),
  (9, 'PERM_MENU_READ', 'Read menus', 'API', '/api/admin/platform/rbac/**', NULL, 1),
  (10, 'PERM_MENU_WRITE', 'Write menus', 'API', '/api/admin/platform/rbac/**', NULL, 1),
  (101, 'PERM_OVERVIEW_READ', 'Read ops dashboard overview', 'API', '/api/admin/ops-dashboard/**,/api/admin/treasury/overview,/api/admin/treasury/dual-ledger', NULL, 1),
  (102, 'PERM_USERS_READ', 'Read user operations', 'API', '/api/admin/users/**', NULL, 1),
  (117, 'PERM_USERS_WRITE', 'Write user operations', 'API', '/api/admin/users/**', NULL, 1),
  (103, 'PERM_FINANCE_READ', 'Read finance and treasury operations', 'API', '/api/admin/finance/**,/api/admin/treasury/**', NULL, 1),
  (104, 'PERM_FINANCE_WRITE', 'Write finance and treasury operations', 'API', '/api/admin/finance/**,/api/admin/treasury/**', NULL, 1),
  (105, 'PERM_DEVICES_READ', 'Read device and commerce operations', 'API', '/api/admin/devices/**', NULL, 1),
  (112, 'PERM_DEVICES_WRITE', 'Write device and commerce operations', 'API', '/api/admin/devices/**', NULL, 1),
  (106, 'PERM_MARKET_READ', 'Read financial product operations', 'API', '/api/admin/market/**', NULL, 1),
  (118, 'PERM_MARKET_WRITE', 'Write financial product operations', 'API', '/api/admin/market/**', NULL, 1),
  (107, 'PERM_CONTENT_READ', 'Read content and support operations', 'API', '/api/admin/content/**', NULL, 1),
  (119, 'PERM_CONTENT_WRITE', 'Write content and support operations', 'API', '/api/admin/content/**', NULL, 1),
  (108, 'PERM_TEAM_READ', 'Read team and commission operations', 'API', '/api/admin/teams/**', NULL, 1),
  (116, 'PERM_TEAM_WRITE', 'Write team and commission operations', 'API', '/api/admin/teams/**', NULL, 1),
  (109, 'PERM_GROWTH_READ', 'Read growth operations', 'API', '/api/admin/growth/**', NULL, 1),
  (121, 'PERM_GROWTH_WRITE', 'Write growth operations', 'API', '/api/admin/growth/**', NULL, 1),
  (110, 'PERM_RISK_READ', 'Read risk and emergency operations', 'API', '/api/admin/risk/**,/api/admin/emergency/**', NULL, 1),
  (113, 'PERM_RISK_WRITE', 'Write risk and emergency operations', 'API', '/api/admin/risk/**,/api/admin/emergency/**', NULL, 1),
  (111, 'PERM_SYSTEM_READ', 'Read platform operations', 'API', '/api/admin/platform/**,/api/admin/options/**,/api/admin/media/**,/api/admin/auth/**', NULL, 1),
  (115, 'PERM_SYSTEM_WRITE', 'Write platform operations', 'API', '/api/admin/platform/**,/api/admin/media/**,/api/admin/commands/**', NULL, 1),
  (114, 'PERM_BI_READ', 'Read BI and reporting operations', 'API', '/api/admin/bi/**', NULL, 1),
  (120, 'PERM_AUDIT_READ', 'Read platform audit logs', 'API', '/api/admin/platform/audit/**', NULL, 1),
  (122, 'PERM_USER_READ', 'Read C-end user operations', 'API', '/auth/users/page,/auth/users/search,/auth/users/*', NULL, 1),
  (123, 'PERM_USER_WRITE', 'Write C-end user operations', 'API', '/auth/users/*', NULL, 1)
ON DUPLICATE KEY UPDATE
  permission_code = VALUES(permission_code),
  permission_name = VALUES(permission_name),
  resource_type = VALUES(resource_type),
  resource_path = VALUES(resource_path),
  remark = VALUES(remark),
  status = VALUES(status),
  is_deleted = 0;

INSERT INTO nx_admin_menu (id, menu_code, menu_name, parent_id, route_path, icon, sort_order, remark, status)
VALUES
  (10, 'MENU_HOME', 'Home', NULL, '/home', 'HomeFilled', 10, 'Admin dashboard entry.', 1),
  (20, 'MENU_UMS', 'Access Control', NULL, '/ums', 'Lock', 20, 'Admin access control group.', 1),
  (21, 'MENU_UMS_ADMIN', 'Admins', 20, '/ums/admin', 'User', 21, 'Admin account management.', 1),
  (22, 'MENU_UMS_ROLE', 'Roles', 20, '/ums/role', 'UserFilled', 22, 'Role management.', 1),
  (23, 'MENU_UMS_MENU', 'Menus', 20, '/ums/menu', 'Menu', 23, 'Menu management.', 1),
  (24, 'MENU_UMS_PERMISSION', 'API Permissions', 20, '/ums/permission', 'Key', 24, 'API permission management.', 1)
ON DUPLICATE KEY UPDATE
  menu_name = VALUES(menu_name),
  parent_id = VALUES(parent_id),
  route_path = VALUES(route_path),
  icon = VALUES(icon),
  sort_order = VALUES(sort_order),
  remark = VALUES(remark),
  status = VALUES(status),
  is_deleted = 0;

UPDATE nx_admin_menu
SET menu_name_zh = CASE CAST(menu_code AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci
  WHEN 'MENU_HOME' THEN '首页'
  WHEN 'MENU_UMS' THEN '权限'
  WHEN 'MENU_UMS_ADMIN' THEN '管理员列表'
  WHEN 'MENU_UMS_ROLE' THEN '角色列表'
  WHEN 'MENU_UMS_MENU' THEN '菜单管理'
  WHEN 'MENU_UMS_PERMISSION' THEN 'API 权限'
  ELSE COALESCE(menu_name_zh, CAST(menu_name AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci)
END,
menu_name_en = COALESCE(menu_name_en, CAST(menu_name AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci)
WHERE menu_code LIKE 'MENU_%';

UPDATE nx_admin_menu
SET status = 0,
    is_deleted = 1,
    updated_at = NOW()
WHERE menu_code IN ('MENU_OPS', 'MENU_OPS_DEVICE', 'MENU_OPS_WALLET', 'MENU_OPS_CONFIG');

INSERT INTO nx_admin_role_relation (admin_id, role_id)
VALUES (1, 1)
ON DUPLICATE KEY UPDATE
  is_deleted = 0,
  updated_at = NOW();

INSERT INTO nx_admin_role_permission (role_id, permission_id)
SELECT 1, id FROM nx_admin_permission WHERE resource_type = 'API' AND status = 1 AND is_deleted = 0
ON DUPLICATE KEY UPDATE
  is_deleted = 0,
  updated_at = NOW();

INSERT INTO nx_admin_role_permission (role_id, permission_id)
SELECT 2, id FROM nx_admin_permission
WHERE permission_code IN (
  'PERM_OVERVIEW_READ',
  'PERM_USERS_READ',
  'PERM_USERS_WRITE',
  'PERM_FINANCE_READ',
  'PERM_FINANCE_WRITE',
  'PERM_DEVICES_READ',
  'PERM_DEVICES_WRITE',
  'PERM_MARKET_READ',
  'PERM_MARKET_WRITE',
  'PERM_TEAM_READ',
  'PERM_TEAM_WRITE',
  'PERM_CONTENT_READ',
  'PERM_CONTENT_WRITE',
  'PERM_GROWTH_READ',
  'PERM_GROWTH_WRITE',
  'PERM_RISK_READ',
  'PERM_RISK_WRITE',
  'PERM_SYSTEM_READ',
  'PERM_SYSTEM_WRITE',
  'PERM_BI_READ',
  'PERM_AUDIT_READ',
  'PERM_USER_READ',
  'PERM_USER_WRITE'
)
ON DUPLICATE KEY UPDATE
  is_deleted = 0,
  updated_at = NOW();

INSERT INTO nx_admin_role_menu (role_id, menu_id)
SELECT 1, id FROM nx_admin_menu WHERE status = 1 AND is_deleted = 0
ON DUPLICATE KEY UPDATE
  is_deleted = 0,
  updated_at = NOW();

INSERT INTO nx_admin_role_menu (role_id, menu_id)
SELECT 2, id FROM nx_admin_menu WHERE menu_code IN (
  'MENU_HOME'
)
ON DUPLICATE KEY UPDATE
  is_deleted = 0,
  updated_at = NOW();

INSERT INTO nx_config_item (config_key, config_value, value_type, config_group, visibility, remark, status)
VALUES
  ('admin.bootstrap.mode', 'baseline', 'STRING', 'admin_system', 'ADMIN', 'Local system baseline mode.', 1),
  ('admin.security.min_super_admins', '1', 'NUMBER', 'admin_system', 'ADMIN', 'Minimum active super admins for local startup.', 1),
  ('auth.session.access_ttl_hours', '2', 'NUMBER', 'auth', 'PRIVATE', 'Access token lifetime in hours.', 1),
  ('auth.session.refresh_ttl_days', '30', 'NUMBER', 'auth', 'PRIVATE', 'Refresh token lifetime in days.', 1),
  ('auth.session.idle_ttl_days', '7', 'NUMBER', 'auth', 'PRIVATE', 'Idle session lifetime in days.', 1),
  ('auth.session.step_up_days', '7', 'NUMBER', 'auth', 'PRIVATE', 'Step-up authentication interval in days.', 1),
  ('auth.risk.login_lock_threshold', '5', 'NUMBER', 'auth', 'PRIVATE', 'Short-window login lock threshold.', 1),
  ('auth.risk.lock_duration_minutes', '30', 'NUMBER', 'auth', 'PRIVATE', 'Short-window login lock duration.', 1),
  ('auth.risk.login_long_lock_threshold', '10', 'NUMBER', 'auth', 'PRIVATE', 'Long-window login lock threshold.', 1),
  ('auth.risk.long_lock_duration_hours', '24', 'NUMBER', 'auth', 'PRIVATE', 'Long-window login lock duration.', 1),
  ('auth.risk.otp_ttl_minutes', '10', 'NUMBER', 'auth', 'PRIVATE', 'OTP lifetime in minutes.', 1),
  ('auth.risk.otp_cooldown_seconds', '60', 'NUMBER', 'auth', 'PRIVATE', 'OTP resend cooldown in seconds.', 1),
  ('auth.risk.otp_max_24h', '3', 'NUMBER', 'auth', 'PRIVATE', 'OTP daily send limit.', 1),
  ('media.upload.max_size_mb', '20', 'NUMBER', 'media', 'ADMIN', 'Maximum media upload size in MB.', 1),
  ('media.upload.allowed_extensions', 'jpg,jpeg,png,webp,pdf', 'STRING', 'media', 'ADMIN', 'Allowed media upload extensions.', 1),
  ('openapi.developer.default_qps_limit', '20', 'NUMBER', 'openapi', 'PUBLIC', 'Default OpenAPI QPS limit.', 1),
  ('openapi.developer.default_daily_limit', '10000', 'NUMBER', 'openapi', 'PUBLIC', 'Default OpenAPI daily limit.', 1)
ON DUPLICATE KEY UPDATE
  config_value = VALUES(config_value),
  value_type = VALUES(value_type),
  config_group = VALUES(config_group),
  visibility = VALUES(visibility),
  remark = VALUES(remark),
  status = VALUES(status),
  is_deleted = 0;

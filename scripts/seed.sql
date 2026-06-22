USE nexion;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

INSERT INTO nx_admin (id, username, password_hash, nickname, email, phone, super_admin, status)
VALUES
  (1, 'superadmin', '$2a$10$OiCP0hEdnWNuxl/Q6PQ.juoWSzQCXFbvrLheJ.TYywnoyiZY7s19C', 'Super Admin', 'admin@nexion.ai', NULL, 1, 1)
ON DUPLICATE KEY UPDATE
  password_hash = VALUES(password_hash),
  nickname = VALUES(nickname),
  email = VALUES(email),
  super_admin = VALUES(super_admin),
  status = VALUES(status);

INSERT INTO nx_admin_role (id, role_code, role_name, remark, status)
VALUES
  (1, 'SUPER_ADMIN', 'Super Administrator', 'Platform owner role with all permissions.', 1),
  (2, 'OPS_ADMIN', 'Operations Administrator', 'Daily operations role for compute, wallet, team, and content.', 1)
ON DUPLICATE KEY UPDATE
  role_name = VALUES(role_name),
  remark = VALUES(remark),
  status = VALUES(status);

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
  (115, 'PERM_SYSTEM_WRITE', 'Write platform operations', 'API', '/api/admin/platform/**,/api/admin/options/**,/api/admin/media/**,/api/admin/commands/**', NULL, 1),
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

INSERT IGNORE INTO nx_admin_role_relation (admin_id, role_id)
VALUES (1, 1);

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT 1, id FROM nx_admin_permission WHERE resource_type = 'API';

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
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
);

INSERT IGNORE INTO nx_admin_role_menu (role_id, menu_id)
SELECT 1, id FROM nx_admin_menu;

INSERT IGNORE INTO nx_admin_role_menu (role_id, menu_id)
SELECT 2, id FROM nx_admin_menu WHERE menu_code IN (
  'MENU_HOME'
);

INSERT INTO nx_config_item (config_key, config_value, value_type, config_group, visibility, remark, status)
VALUES
  ('a1.account.pendingTickets', '0', 'NUMBER', 'admin_a1_account', 'ADMIN', 'A1 pending account governance tickets.', 1),
  ('a1.security.minEffectiveSupers', '2', 'NUMBER', 'admin_a1_security', 'ADMIN', 'Minimum enabled super admins required by A1 governance.', 1),
  ('a1.role.super.registered', 'true', 'BOOLEAN', 'admin_a1_role', 'ADMIN', 'A1 role registration.', 1),
  ('a1.role.super.name', '超级管理员', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role name.', 1),
  ('a1.role.super.avatar', 'SA', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role avatar.', 1),
  ('a1.role.super.color', 'red', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role color.', 1),
  ('a1.role.super.description', '平台 Owner，保留所有危急操作。', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role description.', 1),
  ('a1.role.super.scope', '全域：资金、风控、内容、配置、审计', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role scope.', 1),
  ('a1.role.super.sort', '10', 'NUMBER', 'admin_a1_role', 'ADMIN', 'A1 role sort.', 1),
  ('a1.role.finance.registered', 'true', 'BOOLEAN', 'admin_a1_role', 'ADMIN', 'A1 role registration.', 1),
  ('a1.role.finance.name', '资金运营', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role name.', 1),
  ('a1.role.finance.avatar', 'FI', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role avatar.', 1),
  ('a1.role.finance.color', 'green', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role color.', 1),
  ('a1.role.finance.description', '资金、账务、提现与覆盖率。', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role description.', 1),
  ('a1.role.finance.scope', 'D/C/B 资金域', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role scope.', 1),
  ('a1.role.finance.sort', '20', 'NUMBER', 'admin_a1_role', 'ADMIN', 'A1 role sort.', 1),
  ('a1.role.risk.registered', 'true', 'BOOLEAN', 'admin_a1_role', 'ADMIN', 'A1 role registration.', 1),
  ('a1.role.risk.name', '风控运营', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role name.', 1),
  ('a1.role.risk.avatar', 'RK', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role avatar.', 1),
  ('a1.role.risk.color', 'orange', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role color.', 1),
  ('a1.role.risk.description', '风控模型、KYC、账户限制与熔断。', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role description.', 1),
  ('a1.role.risk.scope', 'C/K/J 风控域', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role scope.', 1),
  ('a1.role.risk.sort', '30', 'NUMBER', 'admin_a1_role', 'ADMIN', 'A1 role sort.', 1),
  ('a1.role.growth.registered', 'true', 'BOOLEAN', 'admin_a1_role', 'ADMIN', 'A1 role registration.', 1),
  ('a1.role.growth.name', '增长运营', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role name.', 1),
  ('a1.role.growth.avatar', 'GR', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role avatar.', 1),
  ('a1.role.growth.color', 'blue', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role color.', 1),
  ('a1.role.growth.description', '活动、Phase dial、权益与触达。', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role description.', 1),
  ('a1.role.growth.scope', 'H/权益/触达', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role scope.', 1),
  ('a1.role.growth.sort', '40', 'NUMBER', 'admin_a1_role', 'ADMIN', 'A1 role sort.', 1),
  ('a1.role.content.registered', 'true', 'BOOLEAN', 'admin_a1_role', 'ADMIN', 'A1 role registration.', 1),
  ('a1.role.content.name', '内容运营', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role name.', 1),
  ('a1.role.content.avatar', 'CT', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role avatar.', 1),
  ('a1.role.content.color', 'purple', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role color.', 1),
  ('a1.role.content.description', '文案、课程、风险披露与公告。', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role description.', 1),
  ('a1.role.content.scope', 'I/公告/课程', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role scope.', 1),
  ('a1.role.content.sort', '50', 'NUMBER', 'admin_a1_role', 'ADMIN', 'A1 role sort.', 1),
  ('a1.role.support.registered', 'true', 'BOOLEAN', 'admin_a1_role', 'ADMIN', 'A1 role registration.', 1),
  ('a1.role.support.name', '客服运营', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role name.', 1),
  ('a1.role.support.avatar', 'CS', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role avatar.', 1),
  ('a1.role.support.color', 'cyan', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role color.', 1),
  ('a1.role.support.description', '用户查询、工单协同与只读辅助。', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role description.', 1),
  ('a1.role.support.scope', 'C/D 只读与协助', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role scope.', 1),
  ('a1.role.support.sort', '60', 'NUMBER', 'admin_a1_role', 'ADMIN', 'A1 role sort.', 1),
  ('a1.role.audit.registered', 'true', 'BOOLEAN', 'admin_a1_role', 'ADMIN', 'A1 role registration.', 1),
  ('a1.role.audit.name', '审计只读', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role name.', 1),
  ('a1.role.audit.avatar', 'AU', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role avatar.', 1),
  ('a1.role.audit.color', 'gray', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role color.', 1),
  ('a1.role.audit.description', '审计与合规观察，禁止写操作。', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role description.', 1),
  ('a1.role.audit.scope', 'A2/审计/报表', 'STRING', 'admin_a1_role', 'ADMIN', 'A1 role scope.', 1),
  ('a1.role.audit.sort', '70', 'NUMBER', 'admin_a1_role', 'ADMIN', 'A1 role sort.', 1),
  ('a1.security.baseline.tfa_required.registered', 'true', 'BOOLEAN', 'admin_a1_security', 'ADMIN', 'A1 security baseline registration.', 1),
  ('a1.security.baseline.tfa_required.label', '2FA 强制', 'STRING', 'admin_a1_security', 'ADMIN', 'A1 security baseline label.', 1),
  ('a1.security.baseline.tfa_required.description', '所有运营账号必须启用二次验证。', 'STRING', 'admin_a1_security', 'ADMIN', 'A1 security baseline description.', 1),
  ('a1.security.baseline.tfa_required.value', 'ON', 'STRING', 'admin_a1_security', 'ADMIN', 'A1 security baseline value.', 1),
  ('a1.security.baseline.tfa_required.locked', 'true', 'BOOLEAN', 'admin_a1_security', 'ADMIN', 'A1 security baseline lock.', 1),
  ('a1.security.baseline.tfa_required.sort', '10', 'NUMBER', 'admin_a1_security', 'ADMIN', 'A1 security baseline sort.', 1),
  ('a1.security.baseline.least_priv.registered', 'true', 'BOOLEAN', 'admin_a1_security', 'ADMIN', 'A1 security baseline registration.', 1),
  ('a1.security.baseline.least_priv.label', '最小权限', 'STRING', 'admin_a1_security', 'ADMIN', 'A1 security baseline label.', 1),
  ('a1.security.baseline.least_priv.description', '默认按岗位授予最小可写权限。', 'STRING', 'admin_a1_security', 'ADMIN', 'A1 security baseline description.', 1),
  ('a1.security.baseline.least_priv.value', 'ON', 'STRING', 'admin_a1_security', 'ADMIN', 'A1 security baseline value.', 1),
  ('a1.security.baseline.least_priv.locked', 'true', 'BOOLEAN', 'admin_a1_security', 'ADMIN', 'A1 security baseline lock.', 1),
  ('a1.security.baseline.least_priv.sort', '20', 'NUMBER', 'admin_a1_security', 'ADMIN', 'A1 security baseline sort.', 1),
  ('a1.security.baseline.min_supers.registered', 'true', 'BOOLEAN', 'admin_a1_security', 'ADMIN', 'A1 security baseline registration.', 1),
  ('a1.security.baseline.min_supers.label', '双超级管理员', 'STRING', 'admin_a1_security', 'ADMIN', 'A1 security baseline label.', 1),
  ('a1.security.baseline.min_supers.description', '生产后台至少保留两个有效超级管理员。', 'STRING', 'admin_a1_security', 'ADMIN', 'A1 security baseline description.', 1),
  ('a1.security.baseline.min_supers.value', '2 人', 'STRING', 'admin_a1_security', 'ADMIN', 'A1 security baseline value.', 1),
  ('a1.security.baseline.min_supers.locked', 'true', 'BOOLEAN', 'admin_a1_security', 'ADMIN', 'A1 security baseline lock.', 1),
  ('a1.security.baseline.min_supers.sort', '30', 'NUMBER', 'admin_a1_security', 'ADMIN', 'A1 security baseline sort.', 1),
  ('a1.security.baseline.session.registered', 'true', 'BOOLEAN', 'admin_a1_security', 'ADMIN', 'A1 security baseline registration.', 1),
  ('a1.security.baseline.session.label', '会话上限', 'STRING', 'admin_a1_security', 'ADMIN', 'A1 security baseline label.', 1),
  ('a1.security.baseline.session.description', '后台闲置 30min、绝对 8h。', 'STRING', 'admin_a1_security', 'ADMIN', 'A1 security baseline description.', 1),
  ('a1.security.baseline.session.value', '30min / 8h', 'STRING', 'admin_a1_security', 'ADMIN', 'A1 security baseline value.', 1),
  ('a1.security.baseline.session.locked', 'false', 'BOOLEAN', 'admin_a1_security', 'ADMIN', 'A1 security baseline lock.', 1),
  ('a1.security.baseline.session.sort', '40', 'NUMBER', 'admin_a1_security', 'ADMIN', 'A1 security baseline sort.', 1),
  ('a1.security.baseline.lock.registered', 'true', 'BOOLEAN', 'admin_a1_security', 'ADMIN', 'A1 security baseline registration.', 1),
  ('a1.security.baseline.lock.label', '登录锁定', 'STRING', 'admin_a1_security', 'ADMIN', 'A1 security baseline label.', 1),
  ('a1.security.baseline.lock.description', '短窗连续失败锁定，长窗累计失败人工复核。', 'STRING', 'admin_a1_security', 'ADMIN', 'A1 security baseline description.', 1),
  ('a1.security.baseline.lock.value', '5 次/15min · 15 次/24h', 'STRING', 'admin_a1_security', 'ADMIN', 'A1 security baseline value.', 1),
  ('a1.security.baseline.lock.locked', 'false', 'BOOLEAN', 'admin_a1_security', 'ADMIN', 'A1 security baseline lock.', 1),
  ('a1.security.baseline.lock.sort', '50', 'NUMBER', 'admin_a1_security', 'ADMIN', 'A1 security baseline sort.', 1),
  ('a1.rbac.action.balance_adjust', '余额/资产调整(C3)', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action.', 1),
  ('a1.rbac.action.balance_adjust.domainGroup', '用户/风控', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action domain.', 1),
  ('a1.rbac.action.balance_adjust.sort', '10', 'NUMBER', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action sort.', 1),
  ('a1.rbac.super.balance_adjust', 'C', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.finance.balance_adjust', 'C', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.risk.balance_adjust', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.growth.balance_adjust', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.content.balance_adjust', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.support.balance_adjust', 'M', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.audit.balance_adjust', 'R', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.action.user_freeze', '账户冻结/解冻(C2)', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action.', 1),
  ('a1.rbac.action.user_freeze.domainGroup', '用户/风控', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action domain.', 1),
  ('a1.rbac.action.user_freeze.sort', '20', 'NUMBER', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action sort.', 1),
  ('a1.rbac.super.user_freeze', 'C', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.finance.user_freeze', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.risk.user_freeze', 'M', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.growth.user_freeze', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.content.user_freeze', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.support.user_freeze', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.audit.user_freeze', 'R', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.action.withdraw_approve', '提现放行/冻结(D2)', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action.', 1),
  ('a1.rbac.action.withdraw_approve.domainGroup', '资金', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action domain.', 1),
  ('a1.rbac.action.withdraw_approve.sort', '30', 'NUMBER', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action sort.', 1),
  ('a1.rbac.super.withdraw_approve', 'C', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.finance.withdraw_approve', 'M', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.risk.withdraw_approve', 'M', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.growth.withdraw_approve', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.content.withdraw_approve', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.support.withdraw_approve', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.audit.withdraw_approve', 'R', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.action.bill_adjust', '账单手工调整(D4)', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action.', 1),
  ('a1.rbac.action.bill_adjust.domainGroup', '资金', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action domain.', 1),
  ('a1.rbac.action.bill_adjust.sort', '40', 'NUMBER', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action sort.', 1),
  ('a1.rbac.super.bill_adjust', 'C', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.finance.bill_adjust', 'M', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.risk.bill_adjust', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.growth.bill_adjust', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.content.bill_adjust', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.support.bill_adjust', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.audit.bill_adjust', 'R', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.action.coverage_line', '覆盖率红黄线(B1)', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action.', 1),
  ('a1.rbac.action.coverage_line.domainGroup', '资金', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action domain.', 1),
  ('a1.rbac.action.coverage_line.sort', '50', 'NUMBER', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action sort.', 1),
  ('a1.rbac.super.coverage_line', 'C', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.finance.coverage_line', 'M', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.risk.coverage_line', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.growth.coverage_line', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.content.coverage_line', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.support.coverage_line', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.audit.coverage_line', 'R', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.action.withdraw_param', '提现参数(D5)', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action.', 1),
  ('a1.rbac.action.withdraw_param.domainGroup', '资金', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action domain.', 1),
  ('a1.rbac.action.withdraw_param.sort', '60', 'NUMBER', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action sort.', 1),
  ('a1.rbac.super.withdraw_param', 'C', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.finance.withdraw_param', 'M', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.risk.withdraw_param', 'M', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.growth.withdraw_param', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.content.withdraw_param', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.support.withdraw_param', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.audit.withdraw_param', 'R', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.action.risk_model', '风险模型权重(K4)', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action.', 1),
  ('a1.rbac.action.risk_model.domainGroup', '用户/风控', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action domain.', 1),
  ('a1.rbac.action.risk_model.sort', '70', 'NUMBER', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action sort.', 1),
  ('a1.rbac.super.risk_model', 'C', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.finance.risk_model', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.risk.risk_model', 'M', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.growth.risk_model', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.content.risk_model', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.support.risk_model', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.audit.risk_model', 'R', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.action.kyc_decide', '大额 KYC 裁决(K5)', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action.', 1),
  ('a1.rbac.action.kyc_decide.domainGroup', '用户/风控', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action domain.', 1),
  ('a1.rbac.action.kyc_decide.sort', '80', 'NUMBER', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action sort.', 1),
  ('a1.rbac.super.kyc_decide', 'C', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.finance.kyc_decide', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.risk.kyc_decide', 'M', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.growth.kyc_decide', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.content.kyc_decide', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.support.kyc_decide', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.audit.kyc_decide', 'R', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.action.phase_dial', 'Phase dial(H1)', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action.', 1),
  ('a1.rbac.action.phase_dial.domainGroup', '增长/内容', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action domain.', 1),
  ('a1.rbac.action.phase_dial.sort', '90', 'NUMBER', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action sort.', 1),
  ('a1.rbac.super.phase_dial', 'C', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.finance.phase_dial', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.risk.phase_dial', 'C', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.growth.phase_dial', 'M', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.content.phase_dial', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.support.phase_dial', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.audit.phase_dial', 'R', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.action.content_publish', '文案/课程发布(I)', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action.', 1),
  ('a1.rbac.action.content_publish.domainGroup', '增长/内容', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action domain.', 1),
  ('a1.rbac.action.content_publish.sort', '100', 'NUMBER', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action sort.', 1),
  ('a1.rbac.super.content_publish', 'C', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.finance.content_publish', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.risk.content_publish', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.growth.content_publish', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.content.content_publish', 'M', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.support.content_publish', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.audit.content_publish', 'R', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.action.disclosure_publish', '风险披露发布(I5)', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action.', 1),
  ('a1.rbac.action.disclosure_publish.domainGroup', '增长/内容', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action domain.', 1),
  ('a1.rbac.action.disclosure_publish.sort', '110', 'NUMBER', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action sort.', 1),
  ('a1.rbac.super.disclosure_publish', 'C', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.finance.disclosure_publish', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.risk.disclosure_publish', 'M', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.growth.disclosure_publish', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.content.disclosure_publish', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.support.disclosure_publish', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.audit.disclosure_publish', 'R', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.action.killswitch_toggle', '功能闸熔断(J1)', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action.', 1),
  ('a1.rbac.action.killswitch_toggle.domainGroup', '基座/应急', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action domain.', 1),
  ('a1.rbac.action.killswitch_toggle.sort', '120', 'NUMBER', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action sort.', 1),
  ('a1.rbac.super.killswitch_toggle', 'C', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.finance.killswitch_toggle', 'M', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.risk.killswitch_toggle', 'M', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.growth.killswitch_toggle', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.content.killswitch_toggle', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.support.killswitch_toggle', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.audit.killswitch_toggle', 'R', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.action.geo_block', '地区屏蔽(J2)', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action.', 1),
  ('a1.rbac.action.geo_block.domainGroup', '基座/应急', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action domain.', 1),
  ('a1.rbac.action.geo_block.sort', '130', 'NUMBER', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action sort.', 1),
  ('a1.rbac.super.geo_block', 'C', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.finance.geo_block', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.risk.geo_block', 'M', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.growth.geo_block', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.content.geo_block', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.support.geo_block', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.audit.geo_block', 'R', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.action.feature_flag', 'feature flag(A3)', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action.', 1),
  ('a1.rbac.action.feature_flag.domainGroup', '基座/应急', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action domain.', 1),
  ('a1.rbac.action.feature_flag.sort', '140', 'NUMBER', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action sort.', 1),
  ('a1.rbac.super.feature_flag', 'C', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.finance.feature_flag', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.risk.feature_flag', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.growth.feature_flag', 'M', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.content.feature_flag', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.support.feature_flag', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.audit.feature_flag', 'R', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.action.operator_governance', '运营账号治理(A1)', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action.', 1),
  ('a1.rbac.action.operator_governance.domainGroup', '基座/应急', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action domain.', 1),
  ('a1.rbac.action.operator_governance.sort', '150', 'NUMBER', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action sort.', 1),
  ('a1.rbac.super.operator_governance', 'M', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.finance.operator_governance', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.risk.operator_governance', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.growth.operator_governance', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.content.operator_governance', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.support.operator_governance', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.audit.operator_governance', 'R', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.action.audit_export', '审计全量导出(A2)', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action.', 1),
  ('a1.rbac.action.audit_export.domainGroup', '基座/应急', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action domain.', 1),
  ('a1.rbac.action.audit_export.sort', '160', 'NUMBER', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC action sort.', 1),
  ('a1.rbac.super.audit_export', 'M', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.finance.audit_export', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.risk.audit_export', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.growth.audit_export', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.content.audit_export', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.support.audit_export', '-', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1),
  ('a1.rbac.audit.audit_export', 'M', 'STRING', 'admin_a1_rbac', 'ADMIN', 'A1 RBAC grant.', 1)
ON DUPLICATE KEY UPDATE
  config_value = VALUES(config_value),
  value_type = VALUES(value_type),
  config_group = VALUES(config_group),
  visibility = VALUES(visibility),
  remark = VALUES(remark),
  status = VALUES(status),
  is_deleted = 0;

INSERT INTO nx_user (id, country_code, phone, password_hash, nickname, referral_code, sponsor_code, kyc_status, user_level, v_rank, status, language, region)
VALUES
  (10001, '+1', '4150004892', '$2a$10$0Q7Qw2lVhKjKq6C8E2bVKuDg4bQ9zq1N9bG4.cHk0xvS3MSB6tQdu', 'Stella Miner', 'NX4892', 'SPONSOR7', 'PENDING', 'L1', 'V0', 'ACTIVE', 'en-US', 'US')
ON DUPLICATE KEY UPDATE
  nickname = VALUES(nickname),
  kyc_status = VALUES(kyc_status),
  user_level = VALUES(user_level),
  v_rank = VALUES(v_rank),
  status = VALUES(status);

INSERT INTO nx_user_profile (id, user_id, display_name, email, wallet_address)
VALUES
  (1, 10001, 'Stella Miner', 'stella@nexion.ai', '0x4892nexiondemo')
ON DUPLICATE KEY UPDATE
  display_name = VALUES(display_name),
  email = VALUES(email),
  wallet_address = VALUES(wallet_address);

INSERT INTO nx_user_preference
  (id, user_id, sound_enabled, haptics_enabled, notify_commission, notify_team, notify_staking, notify_market, notify_genesis, notify_system)
VALUES
  (1, 10001, 1, 1, 1, 1, 1, 1, 1, 1)
ON DUPLICATE KEY UPDATE
  sound_enabled = VALUES(sound_enabled),
  haptics_enabled = VALUES(haptics_enabled),
  notify_commission = VALUES(notify_commission),
  notify_team = VALUES(notify_team),
  notify_staking = VALUES(notify_staking),
  notify_market = VALUES(notify_market),
  notify_genesis = VALUES(notify_genesis),
  notify_system = VALUES(notify_system);

INSERT INTO nx_user_security (id, user_id, two_factor_enabled, login_fail_count)
VALUES
  (1, 10001, 0, 0)
ON DUPLICATE KEY UPDATE
  two_factor_enabled = VALUES(two_factor_enabled),
  login_fail_count = VALUES(login_fail_count);

INSERT INTO nx_account_list (
  user_id, kind, reason, status, expires_at, created_by, released_by, release_reason, released_at
)
VALUES
  (10001, 'ALLOW', 'Seeded C2 trust-list entry for backend integration smoke test', 'ACTIVE', DATE_ADD(NOW(), INTERVAL 30 DAY), 'superadmin', NULL, NULL, NULL)
ON DUPLICATE KEY UPDATE
  kind = VALUES(kind),
  reason = VALUES(reason),
  status = VALUES(status),
  expires_at = VALUES(expires_at),
  created_by = VALUES(created_by),
  released_by = VALUES(released_by),
  release_reason = VALUES(release_reason),
  released_at = VALUES(released_at),
  is_deleted = 0;

INSERT INTO nx_user_impersonation_session (
  session_no, user_id, status, ttl_minutes, operator, reason, expires_at, terminated_by, terminate_reason, terminated_at
)
VALUES
  ('IMP-SEED-10001', 10001, 'ACTIVE', 30, 'superadmin', 'Seeded C2 impersonation session for backend integration smoke test', DATE_ADD(NOW(), INTERVAL 30 MINUTE), NULL, NULL, NULL)
ON DUPLICATE KEY UPDATE
  user_id = VALUES(user_id),
  status = VALUES(status),
  ttl_minutes = VALUES(ttl_minutes),
  operator = VALUES(operator),
  reason = VALUES(reason),
  expires_at = VALUES(expires_at),
  terminated_by = VALUES(terminated_by),
  terminate_reason = VALUES(terminate_reason),
  terminated_at = VALUES(terminated_at),
  is_deleted = 0;

INSERT INTO nx_user_level_config (id, level_code, level_name, entry_condition, core_goal, sort_order, status)
VALUES
  (1, 'L0', 'Visitor', 'Not registered', 'Complete registration', 0, 1),
  (2, 'L1', 'New Contributor', 'Registered with mobile compute access', 'Stay online for 24 hours and receive first earning', 1, 1),
  (3, 'L2', 'Active Contributor', 'Earned 5+ USDT', 'Complete first withdrawal', 2, 1),
  (4, 'L3', 'Upgrade Candidate', 'Viewed hardware commerce', 'Complete device purchase', 3, 1),
  (5, 'L4', 'Device Holder', 'Purchased at least one NexionBox', 'Add or renew devices', 4, 1),
  (6, 'L5', 'Ambassador', 'Three successful referrals', 'Scale referral growth', 5, 1)
ON DUPLICATE KEY UPDATE
  level_name = VALUES(level_name),
  entry_condition = VALUES(entry_condition),
  core_goal = VALUES(core_goal),
  sort_order = VALUES(sort_order),
  status = VALUES(status);

INSERT INTO nx_v_rank_config
  (id, rank_code, title_en, title_cn, self_buy_usd, direct_refs, team_volume_usd, required_downline_rank, required_downline_count, downline_requirement, unilevel_depth, peer_bonus_rate, leadership_votes, physical_reward, sort_order, status)
VALUES
  (1, 'V0', 'Cadet', 'Cadet', 0, 0, 0, NULL, 0, 'Registered account', 'L1', 0, 0, NULL, 0, 1),
  (2, 'V1', 'Pilot', 'Pilot', 299, 3, 0, NULL, 0, 'Self buy >= 299 USDT and 3 directs', 'L2', 0, 0, 'Pilot badge', 1, 1),
  (3, 'V2', 'Operator', 'Operator', 0, 0, 5000, NULL, 0, 'Team volume >= 5000 USDT', 'L2-L3', 0, 0, 'Operator badge', 2, 1),
  (4, 'V3', 'Captain', 'Captain', 0, 0, 20000, 'V1', 2, 'Team volume >= 20000 USDT and 2 V1 legs', 'L2-L4', 0.0500, 1, 'Apple Watch SE', 3, 1),
  (5, 'V4', 'Commander', 'Commander', 0, 0, 50000, 'V2', 3, 'Team volume >= 50000 USDT and 3 V2 legs', 'L2-L5', 0.0500, 2, 'iPhone 16 Pro', 4, 1),
  (6, 'V5', 'Wing Leader', 'Wing Leader', 0, 0, 150000, 'V3', 4, 'Team volume >= 150000 USDT and 4 V3 legs', 'L2-L6', 0.0500, 4, 'Apple Vision Pro', 5, 1),
  (7, 'V6', 'Squadron Lead', 'Squadron Lead', 0, 0, 500000, 'V4', 4, 'Team volume >= 500000 USDT and 4 V4 legs', 'L2-L7', 0.0600, 8, 'MacBook Pro', 6, 1),
  (8, 'V7', 'Aviator', 'Aviator', 0, 0, 1000000, 'V5', 3, 'Team volume >= 1000000 USDT and 3 V5 legs', 'L2-L7', 0.0600, 12, 'AI workstation credit', 7, 1),
  (9, 'V8', 'Ace', 'Ace', 0, 0, 2000000, 'V6', 3, 'Team volume >= 2000000 USDT and 3 V6 legs', 'L2-L7', 0.0700, 20, 'Leadership pool ticket', 8, 1),
  (10, 'V9', 'Maverick', 'Maverick', 0, 0, 5000000, 'V7', 3, 'Team volume >= 5000000 USDT and 3 V7 legs', 'L2-L7', 0.0700, 32, 'Global retreat ticket', 9, 1),
  (11, 'V10', 'Strategist', 'Strategist', 0, 0, 10000000, 'V8', 3, 'Team volume >= 10000000 USDT and 3 V8 legs', 'L2-L7', 0.0800, 48, 'Regional operations grant', 10, 1),
  (12, 'V11', 'Architect', 'Architect', 0, 0, 25000000, 'V9', 3, 'Team volume >= 25000000 USDT and 3 V9 legs', 'L2-L7', 0.0900, 72, 'Global ambassador grant', 11, 1),
  (13, 'V12', 'Legend', 'Legend', 0, 0, 50000000, 'V10', 3, 'Team volume >= 50000000 USDT and 3 V10 legs', 'L2-L7', 0.1000, 100, 'Top leadership share', 12, 1)
ON DUPLICATE KEY UPDATE
  title_en = VALUES(title_en),
  title_cn = VALUES(title_cn),
  self_buy_usd = VALUES(self_buy_usd),
  direct_refs = VALUES(direct_refs),
  team_volume_usd = VALUES(team_volume_usd),
  required_downline_rank = VALUES(required_downline_rank),
  required_downline_count = VALUES(required_downline_count),
  downline_requirement = VALUES(downline_requirement),
  unilevel_depth = VALUES(unilevel_depth),
  peer_bonus_rate = VALUES(peer_bonus_rate),
  leadership_votes = VALUES(leadership_votes),
  physical_reward = VALUES(physical_reward),
  sort_order = VALUES(sort_order),
  status = VALUES(status);

INSERT INTO nx_commission_rule
  (id, commission_type, layer_no, rank_code, usdt_rate, nex_per_usd, fixed_nex, daily_cap_usdt, cooldown_days, status)
VALUES
  (1, 'UNILEVEL', 1, NULL, 0.100000, 50.000000, 0, 0, 30, 1),
  (2, 'UNILEVEL', 2, NULL, 0.050000, 20.000000, 0, 0, 30, 1),
  (3, 'UNILEVEL', 3, NULL, 0.030000, 10.000000, 0, 0, 30, 1),
  (4, 'UNILEVEL', 4, NULL, 0.020000, 5.000000, 0, 0, 30, 1),
  (5, 'UNILEVEL', 5, NULL, 0.010000, 2.500000, 0, 0, 30, 1),
  (6, 'UNILEVEL', 6, NULL, 0.005000, 1.000000, 0, 0, 30, 1),
  (7, 'UNILEVEL', 7, NULL, 0.005000, 1.000000, 0, 0, 30, 1),
  (8, 'BINARY', NULL, NULL, 0.100000, 0, 0, 5000.000000, 1, 1),
  (9, 'PEER', NULL, NULL, 0.050000, 0, 0, 0, 30, 1),
  (10, 'LEADERSHIP', NULL, NULL, 0.050000, 0, 0, 0, 0, 1),
  (11, 'CULTIVATION', NULL, 'V1', 0, 0, 500.000000, 0, 0, 1),
  (12, 'CULTIVATION', NULL, 'V2', 0, 0, 2000.000000, 0, 0, 1),
  (13, 'CULTIVATION', NULL, 'V3', 0, 0, 10000.000000, 0, 0, 1),
  (14, 'CULTIVATION', NULL, 'V4', 0, 0, 50000.000000, 0, 0, 1),
  (15, 'CULTIVATION', NULL, 'V5', 0, 0, 200000.000000, 0, 0, 1)
ON DUPLICATE KEY UPDATE
  rank_code = VALUES(rank_code),
  usdt_rate = VALUES(usdt_rate),
  nex_per_usd = VALUES(nex_per_usd),
  fixed_nex = VALUES(fixed_nex),
  daily_cap_usdt = VALUES(daily_cap_usdt),
  cooldown_days = VALUES(cooldown_days),
  status = VALUES(status);

UPDATE nx_commission_rule
   SET rank_code = NULL,
       usdt_rate = 0.050000,
       cooldown_days = 0,
       status = 1,
       is_deleted = 0
 WHERE commission_type = 'LEADERSHIP';

INSERT INTO nx_team_ambassador_application
  (id, user_id, applicant_name, region, city, event_date, contact_method, application_reason, event_plan, expected_attendees, current_rank, requested_budget_usdt, kol_budget_pct, status)
VALUES
  (1, 1, 'Regional Operator Alpha', 'Southeast Asia', 'Singapore', '2026-07-18', 'Telegram @alphaops', 'Host a regional Nexion hardware owner meetup.', 'Venue demo, onboarding desk, local KOL live stream and post-event lead report.', 120, 'V5', 50000.000000, 50.0000, 'PENDING'),
  (2, 2, 'Regional Operator Beta', 'Middle East', 'Dubai', '2026-08-09', 'WhatsApp +971-555-0102', 'Launch regional ambassador booth with reseller partners.', 'Mall booth, invited miner customers, conversion tracking and weekly recap.', 180, 'V6', 80000.000000, 45.0000, 'PENDING'),
  (3, 3, 'Regional Operator Gamma', 'Korea', 'Seoul', '2026-07-28', 'Line gamma-ops', 'Small private salon proposal.', 'Invite-only product introduction session.', 35, 'V4', 25000.000000, 35.0000, 'REJECTED')
ON DUPLICATE KEY UPDATE
  applicant_name = VALUES(applicant_name),
  region = VALUES(region),
  city = VALUES(city),
  event_date = VALUES(event_date),
  contact_method = VALUES(contact_method),
  application_reason = VALUES(application_reason),
  event_plan = VALUES(event_plan),
  expected_attendees = VALUES(expected_attendees),
  current_rank = VALUES(current_rank),
  requested_budget_usdt = VALUES(requested_budget_usdt),
  kol_budget_pct = VALUES(kol_budget_pct),
  status = VALUES(status),
  updated_at = NOW(),
  is_deleted = 0;

INSERT INTO nx_team_hardware_quota_tier
  (id, quota_code, product_no, display_name, note, direct_refs, month_volume_usd, monthly_quota, unlock_mode, status, sort_order)
VALUES
  (1, 'PRO', 'NX-PRO', 'NexionBox Pro', 'AI Premium pool · higher daily compute jobs', 5, 50000.000000, 96, 'ALL', 1, 10),
  (2, 'RACK', 'NX-RACK', 'NexionRack P1', 'Rack-scale allocation · leadership-grade supply', 15, 0.000000, 24, 'ANY', 1, 20)
ON DUPLICATE KEY UPDATE
  product_no = VALUES(product_no),
  display_name = VALUES(display_name),
  note = VALUES(note),
  direct_refs = VALUES(direct_refs),
  month_volume_usd = VALUES(month_volume_usd),
  monthly_quota = VALUES(monthly_quota),
  unlock_mode = VALUES(unlock_mode),
  status = VALUES(status),
  sort_order = VALUES(sort_order),
  updated_at = NOW(),
  is_deleted = 0;

INSERT INTO nx_team_hardware_quota_usage
  (id, quota_tier_id, quota_code, product_no, user_id, order_no, usage_type, quantity, status, remark, occurred_at)
VALUES
  (1, 1, 'PRO', 'NX-PRO', 1, 'HQ-PRO-202606-001', 'REDEEMED', 12, 'ACTIVE', 'Seeded monthly redeemed Pro quota for ops visibility', DATE_FORMAT(NOW(), '%Y-%m-03 10:00:00')),
  (2, 1, 'PRO', 'NX-PRO', 2, 'HQ-PRO-202606-002', 'RESERVED', 8, 'ACTIVE', 'Seeded monthly reserved Pro quota awaiting checkout', DATE_FORMAT(NOW(), '%Y-%m-08 14:00:00')),
  (3, 2, 'RACK', 'NX-RACK', 1, 'HQ-RACK-202606-001', 'REDEEMED', 3, 'ACTIVE', 'Seeded monthly redeemed Rack quota for ops visibility', DATE_FORMAT(NOW(), '%Y-%m-11 09:30:00'))
ON DUPLICATE KEY UPDATE
  quota_tier_id = VALUES(quota_tier_id),
  quota_code = VALUES(quota_code),
  product_no = VALUES(product_no),
  user_id = VALUES(user_id),
  order_no = VALUES(order_no),
  usage_type = VALUES(usage_type),
  quantity = VALUES(quantity),
  status = VALUES(status),
  remark = VALUES(remark),
  occurred_at = VALUES(occurred_at),
  updated_at = NOW(),
  is_deleted = 0;

INSERT INTO nx_product (
  id, product_no, name, product_type, tier, status, price_usdt, hashrate, estimated_daily_usdt, daily_nex, stock, cover_url,
  badge, tagline, store_status, store_visible, store_featured, sort_order, generation, gpu_model, vram_total_gb, ai_performance_json,
  share_yield_min, share_yield_max, superseded_by_product_no, unlock_phase, sold_count, rating_value, review_count
)
VALUES
  (1, 'NX-S1', 'NexionBox S1 P1', 'NEXION_BOX', 'S1', 'ON_SALE', 1299.00, 4.200000, 38.500000, 65.000000, 47, '/img/products/nexionbox-s1.png',
   'Best Seller', 'Personal AI box for home compute income', 'legacy', 1, 1, 10, 1, '4x RTX 4090', 96, '{"imageGenPerMin":320,"llmTokensPerSec":12400,"videoMinPerHour":18,"unlocks":"LLM 70B inference pool"}',
   NULL, NULL, 'NX-PRO-V2', 'P1', 4821, 4.80, 2847),
  (2, 'NX-PRO', 'NexionBox Pro P1', 'NEXION_BOX', 'PRO', 'ON_SALE', 2399.00, 11.500000, 76.000000, 215.000000, 23, '/img/products/nexionbox-pro.png',
   'Trending', 'Pro-grade AI box with higher local inference capacity', 'legacy', 1, 0, 20, 1, '8x RTX 4090', 192, '{"imageGenPerMin":720,"llmTokensPerSec":38000,"videoMinPerHour":12,"unlocks":"AI Premium pool"}',
   NULL, NULL, 'NX-PRO-V2', 'P1', 1842, 4.90, 1124),
  (3, 'NX-RACK', 'NexionRack P1', 'NEXION_RACK', 'RACK', 'ON_SALE', 8999.00, 48.000000, 142.600000, 950.000000, 8, '/img/products/nexionrack.png',
   'Flagship', 'Rack-scale AI income hardware for serious capacity', 'legacy', 1, 0, 30, 1, '8x NVIDIA A100', 640, '{"imageGenPerMin":1800,"llmTokensPerSec":128000,"videoMinPerHour":60,"unlocks":"Training pool"}',
   NULL, NULL, 'NX-RACK-P2', 'P1', 287, 4.90, 154),
  (4, 'NX-PRO-V2', 'NexionBox Pro v2 P3', 'NEXION_BOX', 'PRO_V2', 'ON_SALE', 2639.00, 18.000000, 96.000000, 280.000000, 38, '/img/products/nexionbox-pro-v2.png',
   'New Gen', 'New silicon tier with stronger AI pool access', 'active', 1, 0, 40, 2, '8x RTX 5090', 256, '{"unlocks":"AI Premium pool"}',
   NULL, NULL, NULL, 'P3', 412, 4.90, 187),
  (5, 'NX-RACK-P2', 'NexionRack P2', 'NEXION_RACK', 'RACK_P2', 'ON_SALE', 14999.00, 96.000000, 248.000000, 1820.000000, 4, '/img/products/nexionrack-p2.png',
   'New Gen', 'Training pool unlock tier for flagship rack owners', 'active', 1, 0, 50, 2, '8x NVIDIA H100', 1024, '{"unlocks":"Training pool"}',
   NULL, NULL, NULL, 'P5', 64, 5.00, 41),
  (6, 'NX-MINI-P2', 'NexionMini P2', 'NEXION_BOX', 'MINI_P2', 'ON_SALE', 899.00, 2.800000, 24.000000, 42.000000, 66, '/img/products/nexion-mini-p2.png',
   'P2 Unlock', 'Entry AI box for retention ramp users', 'active', 1, 0, 25, 2, '2x RTX 4080 Super', 32, '{"imageGenPerMin":180,"llmTokensPerSec":6800,"unlocks":"Starter AI pool"}',
   NULL, NULL, NULL, 'P2', 936, 4.70, 388),
  (7, 'NX-EDGE-P4', 'NexionEdge P4', 'NEXION_BOX', 'EDGE_P4', 'ON_SALE', 3699.00, 24.000000, 128.000000, 360.000000, 18, '/img/products/nexion-edge-p4.png',
   'Subscription Ready', 'Subscription-era edge compute SKU with balanced AI yield', 'active', 1, 0, 45, 4, '4x RTX 5090', 128, '{"imageGenPerMin":960,"llmTokensPerSec":52000,"videoMinPerHour":24,"unlocks":"Subscription compute pool"}',
   NULL, NULL, NULL, 'P4', 218, 4.80, 96),
  (8, 'NX-LEGACY-P6', 'NexionLegacy P6', 'NEXION_RACK', 'LEGACY_P6', 'ON_SALE', 5999.00, 36.000000, 88.000000, 520.000000, 12, '/img/products/nexion-legacy-p6.png',
   'Soft Exit', 'Soft-exit replacement SKU for late lifecycle users', 'active', 1, 0, 60, 6, '4x NVIDIA L40S', 192, '{"imageGenPerMin":720,"llmTokensPerSec":42000,"unlocks":"Lifecycle replacement pool"}',
   NULL, NULL, NULL, 'P6', 74, 4.60, 35),
  (9, 'CLOUD-SHARE-P1', 'Cloud Share P1', 'CLOUD_SHARE', 'Share', 'ON_SALE', 199.00, 0.000000, 0.073000, 30.000000, 9999, '',
   'Low Barrier', 'Fractional access to managed cloud compute yield', 'active', 1, 0, 15, 1, 'Managed cloud pool', 0, '{"unlocks":"Fractional IG + EM + SP pools"}',
   8.0000, 15.0000, NULL, 'P1', 12483, 4.60, 3812)
ON DUPLICATE KEY UPDATE
  product_no = VALUES(product_no),
  name = VALUES(name),
  product_type = VALUES(product_type),
  tier = VALUES(tier),
  status = VALUES(status),
  price_usdt = VALUES(price_usdt),
  hashrate = VALUES(hashrate),
  estimated_daily_usdt = VALUES(estimated_daily_usdt),
  daily_nex = VALUES(daily_nex),
  stock = VALUES(stock),
  cover_url = VALUES(cover_url),
  badge = VALUES(badge),
  tagline = VALUES(tagline),
  store_status = VALUES(store_status),
  store_visible = VALUES(store_visible),
  store_featured = VALUES(store_featured),
  sort_order = VALUES(sort_order),
  generation = VALUES(generation),
  gpu_model = VALUES(gpu_model),
  vram_total_gb = VALUES(vram_total_gb),
  ai_performance_json = VALUES(ai_performance_json),
  share_yield_min = VALUES(share_yield_min),
  share_yield_max = VALUES(share_yield_max),
  superseded_by_product_no = VALUES(superseded_by_product_no),
  unlock_phase = VALUES(unlock_phase),
  sold_count = VALUES(sold_count),
  rating_value = VALUES(rating_value),
  review_count = VALUES(review_count);

UPDATE nx_product
SET
  detail_metrics_json = JSON_OBJECT(
    'phoneDailyEarn', 0.06,
    'viewing', 24 + MOD(CHAR_LENGTH(product_no) * 13, 92),
    'sold30m', GREATEST(1, FLOOR(GREATEST(8, FLOOR(sold_count * 0.024)) * 0.022)),
    'sold24h', GREATEST(8, FLOOR(sold_count * 0.024)),
    'stockLeft', stock
  ),
  hardware_specs_json = JSON_ARRAY(
    JSON_OBJECT('k', 'GPU', 'v', COALESCE(gpu_model, product_type)),
    JSON_OBJECT('k', 'VRAM', 'v', CONCAT(COALESCE(vram_total_gb, 0), 'GB VRAM')),
    JSON_OBJECT('k', 'Power', 'v', IF(product_type = 'CLOUD_SHARE', 'Managed cloud pool', '—')),
    JSON_OBJECT('k', 'Datacenter', 'v', 'Singapore'),
    JSON_OBJECT('k', 'Uptime SLA', 'v', '99.9%'),
    JSON_OBJECT('k', 'Warranty', 'v', IF(product_type = 'CLOUD_SHARE', 'Redeem 30d', '24 months'))
  ),
  review_summary_json = JSON_OBJECT(
    'bars', JSON_ARRAY(
      JSON_OBJECT('star', 5, 'pct', 78),
      JSON_OBJECT('star', 4, 'pct', 15),
      JSON_OBJECT('star', 3, 'pct', 4),
      JSON_OBJECT('star', 2, 'pct', 2),
      JSON_OBJECT('star', 1, 'pct', 1)
    )
  ),
  reviews_json = JSON_ARRAY(
    JSON_OBJECT('name', 'Maya · ID', 'stars', 5, 'time', '2 days ago', 'text', 'Paid back fast. Withdrew the first month without friction.', 'color', '#c68316'),
    JSON_OBJECT('name', 'cypher.eth', 'stars', 5, 'time', '1 week ago', 'text', 'AI workloads are real and steady. Best ROI in my compute portfolio.', 'color', '#7250c8'),
    JSON_OBJECT('name', 'Hideo · JP', 'stars', 4, 'time', '2 weeks ago', 'text', 'Stable yields. Activation support was slow at first, now running fine.', 'color', '#0e8e4a')
  ),
  trust_json = JSON_OBJECT(
    'featuredIn', JSON_ARRAY('Forbes', 'CoinDesk', 'TechCrunch', 'The Block'),
    'compliance', JSON_ARRAY('SOC 2 Type II', 'ISO 27001', 'Chainalysis KYT')
  ),
  faq_json = JSON_ARRAY(
    JSON_OBJECT('q', 'Where is the device physically?', 'a', 'In our Singapore datacenter. Maintenance, power and network operations are included.'),
    JSON_OBJECT('q', 'Can I withdraw earnings anytime?', 'a', 'Yes, after the withdrawal minimum is met. KYC and wallet verification still apply.'),
    JSON_OBJECT('q', 'What if AI demand drops?', 'a', 'Earnings move with network workload pricing. The displayed estimate uses the current average.'),
    JSON_OBJECT('q', 'Is there a refund window?', 'a', 'Before activation, orders follow the refund policy. After activation, lifecycle and resale rules apply.')
  ),
  phone_compare_json = JSON_OBJECT('phoneLabel', 'Your phone', 'phoneDailyEarn', 0.06, 'targetIcon', 'box')
WHERE product_no IN ('NX-S1', 'NX-PRO', 'NX-RACK', 'NX-PRO-V2', 'NX-RACK-P2', 'NX-MINI-P2', 'NX-EDGE-P4', 'NX-LEGACY-P6', 'CLOUD-SHARE-P1');

INSERT INTO nx_product_review
  (id, product_id, user_id, order_id, rating, title, content, media_object_keys, avatar_object_key, avatar_color, status, sort_order)
VALUES
  (1, 1, 10001, NULL, 5.00, 'Verified buyer', 'Stable daily yield and fast activation.', NULL, NULL, '#9EDC1D', 'VISIBLE', 10),
  (2, 1, 10001, NULL, 4.50, 'Good compute node', 'The income estimate matched the first week within a small range.', NULL, NULL, '#4CC9F0', 'VISIBLE', 20),
  (3, 2, 10001, NULL, 5.00, 'AI Premium pool', 'The Pro tier gets stronger LLM jobs and a better daily mix.', NULL, NULL, '#FFD166', 'VISIBLE', 10)
ON DUPLICATE KEY UPDATE
  product_id = VALUES(product_id),
  rating = VALUES(rating),
  title = VALUES(title),
  content = VALUES(content),
  avatar_object_key = VALUES(avatar_object_key),
  avatar_color = VALUES(avatar_color),
  status = VALUES(status),
  sort_order = VALUES(sort_order);

INSERT INTO nx_product_faq
  (id, product_id, question, answer, status, sort_order)
VALUES
  (1, 1, 'Where is the device physically?', 'In our Singapore datacenter. Maintenance, power and network operations are included.', 'VISIBLE', 10),
  (2, 1, 'Can I withdraw earnings anytime?', 'Yes, after the withdrawal minimum is met. KYC and wallet verification still apply.', 'VISIBLE', 20),
  (3, 2, 'How is daily yield calculated?', 'Daily yield is calculated from backend task settlement and current device efficiency.', 'VISIBLE', 10)
ON DUPLICATE KEY UPDATE
  product_id = VALUES(product_id),
  question = VALUES(question),
  answer = VALUES(answer),
  status = VALUES(status),
  sort_order = VALUES(sort_order);

INSERT INTO nx_product_spec
  (id, product_id, spec_group, spec_key, spec_value, unit, status, sort_order)
VALUES
  (1, 1, 'COMPUTE', 'GPU', '4x RTX 4090', NULL, 'VISIBLE', 10),
  (2, 1, 'COMPUTE', 'VRAM', '96', 'GB', 'VISIBLE', 20),
  (3, 2, 'COMPUTE', 'GPU', '8x RTX 4090', NULL, 'VISIBLE', 10),
  (4, 2, 'COMPUTE', 'VRAM', '192', 'GB', 'VISIBLE', 20)
ON DUPLICATE KEY UPDATE
  product_id = VALUES(product_id),
  spec_group = VALUES(spec_group),
  spec_key = VALUES(spec_key),
  spec_value = VALUES(spec_value),
  unit = VALUES(unit),
  status = VALUES(status),
  sort_order = VALUES(sort_order);

INSERT INTO nx_price_index
  (id, metric_code, metric_label, unit_label, price_usdt, delta_percent, volume_24h_usdt, sparkline, status, sampled_at)
VALUES
  (1, 'IMAGE_GEN', 'Image Gen', 'per image', 0.00300000, 4.2000, 312000.000000, '[0.0027,0.0028,0.0029,0.0030]', 'ACTIVE', NOW()),
  (2, 'LLM_INFERENCE', 'LLM Inference', 'per 1k tok', 0.00240000, 18.7000, 528000.000000, '[0.0019,0.0020,0.0022,0.0024]', 'ACTIVE', NOW()),
  (3, 'VIDEO_GEN', 'Video Gen', 'per sec', 0.18000000, -1.2000, 146000.000000, '[0.182,0.181,0.180,0.180]', 'ACTIVE', NOW()),
  (4, 'NEX_USDT', 'NEX / USDT', 'per NEX', 0.17100000, 6.2000, 980000.000000, '[0.154,0.158,0.163,0.161,0.166,0.169,0.171]', 'ACTIVE', NOW())
ON DUPLICATE KEY UPDATE
  metric_code = VALUES(metric_code),
  metric_label = VALUES(metric_label),
  unit_label = VALUES(unit_label),
  price_usdt = VALUES(price_usdt),
  delta_percent = VALUES(delta_percent),
  volume_24h_usdt = VALUES(volume_24h_usdt),
  sparkline = VALUES(sparkline),
  status = VALUES(status),
  sampled_at = VALUES(sampled_at);

INSERT INTO nx_genesis_series
  (id, series_code, name, total_supply, sold_supply, price_usdt, status, sale_start_at, sale_end_at, royalty_bps, cover_url, metadata_json)
VALUES
  (1, 'GENESIS-2026', 'Nexion Genesis Node 2026', 1000, 0, 9999.000000, 'ACTIVE', '2026-05-01 00:00:00', NULL, 800, '/img/genesis/genesis-2026.png',
   '{"utility":["lifetime node rights","leadership pool boost","secondary royalty"],"phase":"MVP"}')
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  total_supply = VALUES(total_supply),
  price_usdt = VALUES(price_usdt),
  status = VALUES(status),
  sale_start_at = VALUES(sale_start_at),
  sale_end_at = VALUES(sale_end_at),
  royalty_bps = VALUES(royalty_bps),
  cover_url = VALUES(cover_url),
  metadata_json = VALUES(metadata_json);

INSERT INTO nx_tradein_rule
  (id, source_product_no, source_tier, target_tier, discount_usdt, salvage_rate, min_holding_months, status, sort_order)
VALUES
  (1, 'NX-S1', 'S1', 'PRO_V2', 300.000000, 0.300000, 1, 1, 10),
  (2, 'NX-PRO', 'PRO', 'PRO_V2', 300.000000, 0.300000, 1, 1, 20),
  (3, 'NX-RACK', 'RACK', 'RACK_P2', 800.000000, 0.300000, 1, 1, 30),
  (4, NULL, 'RACK_P1', 'RACK_P2', 800.000000, 0.300000, 1, 1, 40)
ON DUPLICATE KEY UPDATE
  source_product_no = VALUES(source_product_no),
  source_tier = VALUES(source_tier),
  target_tier = VALUES(target_tier),
  discount_usdt = VALUES(discount_usdt),
  salvage_rate = VALUES(salvage_rate),
  min_holding_months = VALUES(min_holding_months),
  status = VALUES(status),
  sort_order = VALUES(sort_order);

INSERT INTO nx_user_device (id, user_id, source_order_no, product_id, product_tier, instance_no, name, device_type, status, hashrate, daily_usdt, daily_nex, last_seen_at, purchased_at, activated_at, pending_deactivate)
VALUES
  (1, 10001, NULL, NULL, NULL, 'UD-10001-PHONE', 'Mobile Compute', 'MOBILE', 'ONLINE', 0.250000, 0.060000, 12.000000, NOW(), NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  product_tier = VALUES(product_tier),
  device_type = VALUES(device_type),
  status = VALUES(status),
  hashrate = VALUES(hashrate),
  daily_usdt = VALUES(daily_usdt),
  daily_nex = VALUES(daily_nex),
  last_seen_at = VALUES(last_seen_at),
  purchased_at = VALUES(purchased_at),
  activated_at = VALUES(activated_at),
  pending_deactivate = VALUES(pending_deactivate);

INSERT INTO nx_device_lifecycle_rule
  (id, scope_type, scope_value, start_month, end_month, monthly_decay_rate, floor_efficiency, exempt, status, sort_order)
VALUES
  (1, 'DEFAULT', NULL, 1, 3, 0.040000, 0.220000, 0, 1, 10),
  (2, 'DEFAULT', NULL, 4, 8, 0.060000, 0.220000, 0, 1, 20),
  (3, 'DEFAULT', NULL, 9, NULL, 0.100000, 0.220000, 0, 1, 30),
  (4, 'PRODUCT_TYPE', 'MOBILE', 0, NULL, 0.000000, 1.000000, 1, 1, 100),
  (5, 'PRODUCT_TYPE', 'CLOUD_SHARE', 0, NULL, 0.000000, 1.000000, 1, 1, 100)
ON DUPLICATE KEY UPDATE
  scope_type = VALUES(scope_type),
  scope_value = VALUES(scope_value),
  start_month = VALUES(start_month),
  end_month = VALUES(end_month),
  monthly_decay_rate = VALUES(monthly_decay_rate),
  floor_efficiency = VALUES(floor_efficiency),
  exempt = VALUES(exempt),
  status = VALUES(status),
  sort_order = VALUES(sort_order);

INSERT INTO nx_compute_task (id, task_no, user_id, user_device_id, task_type, client_name, status, started_at, completed_at)
VALUES
  (1, 'TASK-20260522-0001', 10001, 1, 'IMAGE_INFERENCE', 'Nexion Demo', 'COMPLETED', NOW(), NOW())
ON DUPLICATE KEY UPDATE
  status = VALUES(status),
  completed_at = VALUES(completed_at);

INSERT INTO nx_compute_receipt (id, user_id, user_device_id, task_no, receipt_no, task_type, client_name, reward_usdt, reward_nex, earning_status, proof_hash, completed_at)
VALUES
  (1, 10001, 1, 'TASK-20260522-0001', 'POC-20260522-0001', 'IMAGE_INFERENCE', 'Nexion Demo', 0.018000, 3.200000, 'PENDING', '0x8a22c91f6f8e7c2d', NOW())
ON DUPLICATE KEY UPDATE
  receipt_no = VALUES(receipt_no),
  user_device_id = VALUES(user_device_id),
  task_no = VALUES(task_no),
  reward_usdt = VALUES(reward_usdt),
  reward_nex = VALUES(reward_nex),
  earning_status = VALUES(earning_status),
  proof_hash = VALUES(proof_hash),
  completed_at = VALUES(completed_at);

INSERT INTO nx_earning_event (id, event_no, user_id, user_device_id, receipt_no, asset, amount, status, wallet_posted_at)
VALUES
  (1, 'EARN-20260522-USDT-0001', 10001, 1, 'POC-20260522-0001', 'USDT', 0.018000, 'PENDING_WALLET', NULL),
  (2, 'EARN-20260522-NEX-0001', 10001, 1, 'POC-20260522-0001', 'NEX', 3.200000, 'PENDING_WALLET', NULL)
ON DUPLICATE KEY UPDATE
  amount = VALUES(amount),
  status = VALUES(status),
  wallet_posted_at = VALUES(wallet_posted_at);

INSERT INTO nx_earning_summary (id, user_id, summary_date, usdt_amount, nex_amount)
VALUES
  (1, 10001, CURRENT_DATE, 0.018000, 3.200000)
ON DUPLICATE KEY UPDATE
  usdt_amount = VALUES(usdt_amount),
  nex_amount = VALUES(nex_amount);

INSERT INTO nx_user_wallet (id, user_id, usdt_available, nex_available, pending_withdraw, lifetime_earned)
VALUES
  (1, 10001, 128.640000, 8420.000000, 20.000000, 612.440000)
ON DUPLICATE KEY UPDATE
  usdt_available = VALUES(usdt_available),
  nex_available = VALUES(nex_available),
  pending_withdraw = VALUES(pending_withdraw),
  lifetime_earned = VALUES(lifetime_earned);

INSERT INTO nx_staking_product
  (id, product_code, name, asset, term_days, apy_bps, early_penalty_bps, min_amount, sort_order, status, is_deleted)
VALUES
  (1, 'USDT_30D', 'USDT · 30d', 'USDT', 30, 2800.000000, 200.000000, 50.000000, 10, 'ACTIVE', 0),
  (2, 'USDT_90D', 'USDT · 90d', 'USDT', 90, 6800.000000, 300.000000, 50.000000, 20, 'ACTIVE', 0),
  (3, 'USDT_180D', 'USDT · 180d', 'USDT', 180, 12000.000000, 500.000000, 100.000000, 30, 'ACTIVE', 0),
  (4, 'USDT_365D', 'USDT · 365d', 'USDT', 365, 18000.000000, 800.000000, 100.000000, 40, 'ACTIVE', 0)
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  asset = VALUES(asset),
  term_days = VALUES(term_days),
  apy_bps = VALUES(apy_bps),
  early_penalty_bps = VALUES(early_penalty_bps),
  min_amount = VALUES(min_amount),
  sort_order = VALUES(sort_order),
  status = VALUES(status),
  is_deleted = VALUES(is_deleted);

INSERT INTO nx_earning_milestone_rule
  (id, milestone_id, label, threshold_usdt, reward_nex, sort_order, status)
VALUES
  (1, 'earn-100', 'First $100 earned', 100.000000, 100.000000, 10, 1),
  (2, 'earn-500', 'Half-grand reached', 500.000000, 250.000000, 20, 1),
  (3, 'earn-1000', 'Four-figure earner', 1000.000000, 500.000000, 30, 1),
  (4, 'earn-5000', 'Mid five-figure operator', 5000.000000, 1500.000000, 40, 1),
  (5, 'earn-10000', 'Top 2% of Nexion earners', 10000.000000, 3000.000000, 50, 1)
ON DUPLICATE KEY UPDATE
  label = VALUES(label),
  threshold_usdt = VALUES(threshold_usdt),
  reward_nex = VALUES(reward_nex),
  sort_order = VALUES(sort_order),
  status = VALUES(status);

DELETE FROM nx_wallet_ledger
WHERE biz_no IN (
  'EARN-20260522-USDT-0001',
  'EARN-20260522-NEX-0001',
  'DEMO-BILL-USDT-1',
  'DEMO-BILL-NEX-1',
  'DEMO-TOPUP-USDT-1',
  'DEMO-WITHDRAW-USDT-1',
  'DEMO-SWAP-NEX-1',
  'DEMO-REFER-NEX-1'
);

INSERT INTO nx_wallet_ledger
  (user_id, biz_no, biz_type, asset, direction, amount, balance_after, status, remark, created_at, updated_at, is_deleted)
VALUES
  (10001, 'DEMO-BILL-USDT-1', 'EARNING', 'USDT', 'IN', 2.310000, 128.640000, 'SUCCESS', 'Device earning · Phone node', DATE_SUB(NOW(), INTERVAL 15 MINUTE), NOW(), 0),
  (10001, 'DEMO-BILL-NEX-1', 'EARNING', 'NEX', 'IN', 320.000000, 8420.000000, 'SUCCESS', 'NEX mining bonus', DATE_SUB(NOW(), INTERVAL 3 HOUR), NOW(), 0),
  (10001, 'DEMO-TOPUP-USDT-1', 'DEPOSIT', 'USDT', 'IN', 50.000000, 126.330000, 'SUCCESS', 'TRC20 top up confirmed', DATE_SUB(NOW(), INTERVAL 1 DAY), NOW(), 0),
  (10001, 'DEMO-WITHDRAW-USDT-1', 'WITHDRAWAL', 'USDT', 'OUT', 20.000000, 76.330000, 'PENDING', 'Withdrawal review', DATE_SUB(NOW(), INTERVAL 31 HOUR), NOW(), 0),
  (10001, 'DEMO-SWAP-NEX-1', 'EXCHANGE', 'NEX', 'OUT', 120.000000, 8100.000000, 'SUCCESS', 'USDT ⇄ NEX exchange', DATE_SUB(NOW(), INTERVAL 4 DAY), NOW(), 0),
  (10001, 'DEMO-REFER-NEX-1', 'REFERRAL_COMMISSION', 'NEX', 'IN', 200.000000, 8220.000000, 'SUCCESS', 'Referral network reward', DATE_SUB(NOW(), INTERVAL 6 DAY), NOW(), 0)
ON DUPLICATE KEY UPDATE
  biz_type = VALUES(biz_type),
  amount = VALUES(amount),
  balance_after = VALUES(balance_after),
  status = VALUES(status),
  remark = VALUES(remark),
  created_at = VALUES(created_at),
  updated_at = VALUES(updated_at),
  is_deleted = VALUES(is_deleted);

INSERT INTO nx_exchange_order
  (id, user_id, exchange_no, from_asset, to_asset, from_amount, to_amount, rate, status, created_at, updated_at, is_deleted)
VALUES
  (1, 10001, 'DEMO-EX-NEX-USDT-1', 'NEX', 'USDT', 120.000000, 20.520000, 0.17100000, 'COMPLETED', DATE_SUB(NOW(), INTERVAL 4 DAY), NOW(), 0),
  (2, 10001, 'DEMO-EX-QUEUE-1', 'NEX', 'USDT', 280.000000, 47.880000, 0.17100000, 'QUEUED', DATE_SUB(NOW(), INTERVAL 3 HOUR), NOW(), 0),
  (3, 10001, 'DEMO-EX-QUEUE-2', 'NEX', 'USDT', 7200.000000, 1231.200000, 0.17100000, 'QUEUED', DATE_SUB(NOW(), INTERVAL 2 HOUR), NOW(), 0),
  (4, 10001, 'DEMO-EX-KYC-1', 'NEX', 'USDT', 660.000000, 112.860000, 0.17100000, 'KYC_REQUIRED', DATE_SUB(NOW(), INTERVAL 1 HOUR), NOW(), 0),
  (5, 10001, 'DEMO-EX-USERCAP-1', 'NEX', 'USDT', 310.000000, 53.010000, 0.17100000, 'USER_CAP', DATE_SUB(NOW(), INTERVAL 45 MINUTE), NOW(), 0),
  (6, 10001, 'DEMO-EX-PLATFORMCAP-1', 'NEX', 'USDT', 5200.000000, 889.200000, 0.17100000, 'PLATFORM_CAP', DATE_SUB(NOW(), INTERVAL 30 MINUTE), NOW(), 0)
ON DUPLICATE KEY UPDATE
  user_id = VALUES(user_id),
  from_asset = VALUES(from_asset),
  to_asset = VALUES(to_asset),
  from_amount = VALUES(from_amount),
  to_amount = VALUES(to_amount),
  rate = VALUES(rate),
  status = VALUES(status),
  created_at = VALUES(created_at),
  updated_at = VALUES(updated_at),
  is_deleted = VALUES(is_deleted);

INSERT INTO nx_mission (id, mission_code, mission_name, mission_type, reward_points, status)
VALUES
  (1, 'DAY_ONE_ONLINE', 'Day-One Online', 'DAY_ONE', 100, 1),
  (2, 'FIRST_RECEIPT', 'First Compute Receipt', 'GROWTH', 200, 1),
  (3, 'DAILY_CHECK_IN', 'Daily Check-in', 'DAILY', 30, 1)
ON DUPLICATE KEY UPDATE
  mission_name = VALUES(mission_name),
  mission_type = VALUES(mission_type),
  reward_points = VALUES(reward_points),
  status = VALUES(status);

INSERT INTO nx_achievement (id, achievement_code, achievement_name, description, category, icon_key, accent_color, trigger_type, trigger_value, reward_points, sort_order, status)
VALUES
  (1, 'STREAK_3', '3-Day Streak', 'Check in for 3 consecutive days.', 'LOYALTY', 'calendar', '#9EDC1D', 'STREAK_DAYS', 3, 5, 10, 1),
  (2, 'STREAK_7', '7-Day Streak', 'Keep daily growth active for a full week.', 'LOYALTY', 'flame', '#00D1FF', 'STREAK_DAYS', 7, 15, 20, 1),
  (3, 'STREAK_ROYALTY', 'Streak Royalty', 'Unlock the royalty growth power-up.', 'LOYALTY', 'crown', '#FFB547', 'POWER_UP_ACTIVATION', 0, 0, 30, 1),
  (5, 'STREAK_STAKER', 'Streak Staker', 'Activate the staking boost from daily streaks.', 'LOYALTY', 'lock', '#6CFFB8', 'POWER_UP_ACTIVATION', 0, 0, 50, 1),
  (6, 'STREAK_FOUNDER', 'Streak Founder', 'Reach the Genesis whitelist streak tier.', 'LOYALTY', 'rocket', '#FF6B8A', 'POWER_UP_ACTIVATION', 0, 0, 60, 1),
  (7, 'STREAK_MASTER', 'Streak Master', 'Complete the 100-day streak milestone.', 'LOYALTY', 'award', '#9EDC1D', 'STREAK_MILESTONE', 100, 0, 70, 1)
ON DUPLICATE KEY UPDATE
  achievement_name = VALUES(achievement_name),
  description = VALUES(description),
  category = VALUES(category),
  icon_key = VALUES(icon_key),
  accent_color = VALUES(accent_color),
  trigger_type = VALUES(trigger_type),
  trigger_value = VALUES(trigger_value),
  reward_points = VALUES(reward_points),
  sort_order = VALUES(sort_order),
  status = VALUES(status);

INSERT INTO nx_streak_power_up
  (id, power_up_code, power_up_name, i18n_key, target_path, badge_achievement_code, unlock_streak_days, effect_type, effect_value, duration_days, sort_order, status)
VALUES
  (1, 'royalty_boost', 'Royalty Boost +5% this week', 'royalty_boost', '/team/unilevel/how-it-works', 'STREAK_ROYALTY', 7, 'CONVERSION_LINK', 'ROYALTY_BOOST_5PCT_WEEK', 7, 10, 1),
  (3, 'staking_boost', '+2% APY on next stake', 'staking_boost', '/staking', 'STREAK_STAKER', 30, 'CONVERSION_LINK', 'STAKING_APY_2PCT_NEXT', 0, 30, 1),
  (4, 'genesis_whitelist', 'Genesis whitelist priority', 'genesis_whitelist', '/genesis', 'STREAK_FOUNDER', 60, 'CONVERSION_LINK', 'GENESIS_WHITELIST_PRIORITY', 0, 40, 1)
ON DUPLICATE KEY UPDATE
  power_up_name = VALUES(power_up_name),
  i18n_key = VALUES(i18n_key),
  target_path = VALUES(target_path),
  badge_achievement_code = VALUES(badge_achievement_code),
  unlock_streak_days = VALUES(unlock_streak_days),
  effect_type = VALUES(effect_type),
  effect_value = VALUES(effect_value),
  duration_days = VALUES(duration_days),
  sort_order = VALUES(sort_order),
  status = VALUES(status);

INSERT INTO nx_streak_milestone
  (id, milestone_day, milestone_name, reward_type, reward_amount, reward_name, badge_achievement_code, sort_order, status)
VALUES
  (1, 3, '3-Day Streak', 'POINTS', 5.000000, '+5 Points', NULL, 10, 1),
  (2, 7, '7-Day Streak', 'POINTS', 15.000000, '+15 Points', NULL, 20, 1),
  (3, 14, '14-Day Streak', 'USDT', 1.000000, '+1 USDT', NULL, 30, 1),
  (4, 21, '21-Day Streak', 'NEX', 100.000000, '+100 NEX', NULL, 40, 1),
  (5, 30, '30-Day Streak', 'SPIN', 1.000000, 'Lucky Spin x1', NULL, 50, 1),
  (6, 60, '60-Day Streak', 'USDT', 10.000000, '+10 USDT', NULL, 60, 1),
  (7, 100, '100-Day Streak', 'BADGE', 1.000000, 'Streak Master Badge', 'STREAK_MASTER', 70, 1)
ON DUPLICATE KEY UPDATE
  milestone_name = VALUES(milestone_name),
  reward_type = VALUES(reward_type),
  reward_amount = VALUES(reward_amount),
  reward_name = VALUES(reward_name),
  badge_achievement_code = VALUES(badge_achievement_code),
  sort_order = VALUES(sort_order),
  status = VALUES(status);

INSERT INTO nx_monthly_challenge
  (id, challenge_code, challenge_name, description, theme, months_from, months_to, target_type, target_value, reward_type, reward_amount, reward_name, badge_achievement_code, sort_order, status)
VALUES
  (1, 'MONTHLY_STARTER', 'Starter Month Challenge', 'Complete the first set of Mission Center actions.', 'STARTER', 0, 0, 'MISSION_ACTIONS', 5, 'POINTS', 1000.000000, '+1,000 Points', NULL, 10, 1),
  (2, 'MONTHLY_GROWTH', 'Growth Month Challenge', 'Build the first stable earning and wallet routine.', 'GROWTH', 1, 2, 'MISSION_ACTIONS', 7, 'POINTS', 2000.000000, '+2,000 Points', NULL, 20, 1),
  (3, 'MONTHLY_NETWORK', 'Network Month Challenge', 'Expand team and activity engagement.', 'NETWORK', 3, 5, 'MISSION_ACTIONS', 9, 'POINTS', 3000.000000, '+3,000 Points', NULL, 30, 1),
  (4, 'MONTHLY_WEALTH', 'Wealth Month Challenge', 'Advance long-term wealth and reinvestment actions.', 'WEALTH', 6, 8, 'MISSION_ACTIONS', 10, 'POINTS', 5000.000000, '+5,000 Points', NULL, 40, 1),
  (5, 'MONTHLY_MASTER', 'Master Month Challenge', 'Complete the senior monthly mission route.', 'MASTER', 9, 999, 'MISSION_ACTIONS', 12, 'POINTS', 10000.000000, '+10,000 Points', NULL, 50, 1)
ON DUPLICATE KEY UPDATE
  challenge_name = VALUES(challenge_name),
  description = VALUES(description),
  theme = VALUES(theme),
  months_from = VALUES(months_from),
  months_to = VALUES(months_to),
  target_type = VALUES(target_type),
  target_value = VALUES(target_value),
  reward_type = VALUES(reward_type),
  reward_amount = VALUES(reward_amount),
  reward_name = VALUES(reward_name),
  badge_achievement_code = VALUES(badge_achievement_code),
  sort_order = VALUES(sort_order),
  status = VALUES(status);

INSERT INTO nx_event_quest
  (id, quest_code, quest_name, description, starts_at, ends_at, target_type, target_value, reward_type, reward_amount, reward_name, badge_achievement_code, sort_order, status)
VALUES
  (1, 'EVENT_GENESIS_WEEK', 'Genesis Week Quest', 'Complete launch-week Genesis education and activity tasks.', NULL, NULL, 'EVENT_ACTIONS', 3, 'POINTS', 1500.000000, '+1,500 Points', NULL, 10, 1),
  (2, 'EVENT_COMPUTE_SPRINT', 'Compute Sprint Quest', 'Complete a focused compute activity sprint.', NULL, NULL, 'EVENT_ACTIONS', 5, 'POINTS', 2500.000000, '+2,500 Points', NULL, 20, 1)
ON DUPLICATE KEY UPDATE
  quest_name = VALUES(quest_name),
  description = VALUES(description),
  starts_at = VALUES(starts_at),
  ends_at = VALUES(ends_at),
  target_type = VALUES(target_type),
  target_value = VALUES(target_value),
  reward_type = VALUES(reward_type),
  reward_amount = VALUES(reward_amount),
  reward_name = VALUES(reward_name),
  badge_achievement_code = VALUES(badge_achievement_code),
  sort_order = VALUES(sort_order),
  status = VALUES(status);

INSERT INTO nx_notification (id, biz_no, user_id, type, title, body, read_flag, push_status)
VALUES
  (1, 'seed:EARN-20260522-USDT-0001', 10001, 'EARNING', 'Overnight compute completed', '+0.018 USDT and +3.2 NEX are ready.', 0, 'PENDING')
ON DUPLICATE KEY UPDATE
  biz_no = VALUES(biz_no),
  type = VALUES(type),
  title = VALUES(title),
  body = VALUES(body),
  read_flag = VALUES(read_flag),
  push_status = VALUES(push_status);

INSERT INTO nx_kyc_profile (
  id, user_id, kyc_no, status, country, applicant_name, document_type, document_last4,
  document_object_key, submitted_at
)
VALUES
  (1, 10001, 'KYC-10001', 'PENDING', 'US', 'Seed User', 'PASSPORT', '0001',
   'kyc/10001/passport-front.jpg', NOW())
ON DUPLICATE KEY UPDATE
  status = VALUES(status),
  country = VALUES(country),
  applicant_name = VALUES(applicant_name),
  document_type = VALUES(document_type),
  document_last4 = VALUES(document_last4),
  document_object_key = VALUES(document_object_key),
  submitted_at = VALUES(submitted_at);

INSERT INTO nx_proof_asset (
  id, user_id, proof_no, proof_type, object_key, status, file_name, content_type,
  size_bytes, checksum, related_biz_type, related_biz_no, submitted_by, metadata_json
)
VALUES
  (1, 10001, 'PROOF-SEED-10001-1', 'COMPUTE_RECEIPT', 'proofs/seed/compute-receipt-10001.json',
   'PENDING', 'compute-receipt-10001.json', 'application/json', 1024, 'sha256:seed',
   'COMPUTE_TASK', 'TASK-SEED-10001', 'seed', '{"receiptNo":"RCPT-SEED-10001"}')
ON DUPLICATE KEY UPDATE
  proof_type = VALUES(proof_type),
  object_key = VALUES(object_key),
  status = VALUES(status),
  file_name = VALUES(file_name),
  content_type = VALUES(content_type),
  size_bytes = VALUES(size_bytes),
  checksum = VALUES(checksum),
  related_biz_type = VALUES(related_biz_type),
  related_biz_no = VALUES(related_biz_no),
  submitted_by = VALUES(submitted_by),
  metadata_json = VALUES(metadata_json);

INSERT INTO nx_config_item (id, config_key, config_value, value_type, config_group, visibility, remark, status)
VALUES
  (1, 'product.phase', 'MVP', 'STRING', 'product', 'PUBLIC', 'Current product phase.', 1),
  (2, 'withdrawal.min_usdt', '10', 'NUMBER', 'withdrawal', 'ADMIN', 'Minimum USDT withdrawal amount.', 1),
  (61, 'wallet.withdrawal.min_usdt', '20', 'NUMBER', 'wallet', 'PUBLIC', 'Minimum USDT withdrawal amount shown in app.', 1),
  (62, 'wallet.withdrawal.fee_rate', '0.02', 'NUMBER', 'wallet', 'PUBLIC', 'USDT withdrawal fee rate shown in app.', 1),
  (63, 'wallet.exchange.nex_usdt_price', '0.171', 'NUMBER', 'wallet', 'PUBLIC', 'NEX to USDT exchange reference price shown in app.', 1),
  (64, 'wallet.exchange.user_daily_cap_usdt', '50', 'NUMBER', 'wallet', 'ADMIN', 'G2 per-user daily NEX to USDT outflow cap.', 1),
  (65, 'wallet.exchange.platform_daily_cap_usdt', '20000', 'NUMBER', 'wallet', 'ADMIN', 'G2 platform daily NEX to USDT outflow cap.', 1),
  (66, 'wallet.exchange.fee_pct', '0', 'NUMBER', 'wallet', 'ADMIN', 'G2 exchange fee percent; 0 means free promotion.', 1),
  (67, 'wallet.exchange.fee_min_usdt', '0.50', 'NUMBER', 'wallet', 'ADMIN', 'G2 minimum exchange fee in USDT.', 1),
  (68, 'wallet.exchange.queue_mode', 'QUEUE', 'STRING', 'wallet', 'ADMIN', 'G2 over-cap handling mode: QUEUE or REJECT.', 1),
  (69, 'wallet.exchange.kyc_threshold_usdt', '100', 'NUMBER', 'wallet', 'ADMIN', 'G2 cumulative exchange KYC trigger threshold.', 1),
  (144, 'wallet.exchange.min_usdt_value', '1', 'NUMBER', 'wallet', 'PUBLIC', 'Minimum exchange USDT-equivalent amount shown in app and enforced by wallet service.', 1),
  (145, 'wallet.exchange.max_usdt_value', '5000', 'NUMBER', 'wallet', 'PUBLIC', 'Maximum exchange USDT-equivalent amount shown in app and enforced by wallet service.', 1),
  (146, 'wallet.exchange.daily_count_limit', '10', 'NUMBER', 'wallet', 'PUBLIC', 'Maximum non-rejected exchange orders per user per day.', 1),
  (147, 'commerce.payment.default_provider', 'MOCK', 'STRING', 'commerce', 'PUBLIC', 'Default checkout provider selected by uniapp checkout.', 1),
  (148, 'commerce.payment.checkout.enabled', 'true', 'BOOLEAN', 'commerce', 'PUBLIC', 'Whether USDT checkout is visible and selectable in uniapp checkout.', 1),
  (149, 'commerce.payment.checkout.label', 'USDT checkout', 'STRING', 'commerce', 'PUBLIC', 'Checkout provider label shown in uniapp checkout.', 1),
  (150, 'commerce.payment.checkout.currency', 'USDT', 'STRING', 'commerce', 'PUBLIC', 'Checkout currency shown in uniapp checkout.', 1),
  (151, 'commerce.payment.checkout.network', 'TRC20 / ERC20', 'STRING', 'commerce', 'PUBLIC', 'Checkout settlement network hint shown in uniapp checkout.', 1),
  (152, 'commerce.payment.checkout.min_usdt', '1', 'NUMBER', 'commerce', 'PUBLIC', 'Minimum checkout amount shown in uniapp checkout.', 1),
  (153, 'commerce.payment.checkout.max_usdt', '10000', 'NUMBER', 'commerce', 'PUBLIC', 'Maximum checkout amount shown in uniapp checkout.', 1),
  (205, 'commerce.payment.checkout.fee_mode', 'INCLUDED', 'STRING', 'commerce', 'PUBLIC', 'Checkout network fee mode shown in uniapp checkout.', 1),
  (206, 'commerce.payment.checkout.fee_label', 'Included', 'STRING', 'commerce', 'PUBLIC', 'Checkout network fee label shown in uniapp checkout.', 1),
  (207, 'commerce.payment.checkout.fee_amount_usdt', '0', 'NUMBER', 'commerce', 'PUBLIC', 'Fixed checkout network fee amount in USDT.', 1),
  (208, 'commerce.payment.checkout.fee_rate_pct', '0', 'NUMBER', 'commerce', 'PUBLIC', 'Percentage checkout network fee rate.', 1),
  (209, 'commerce.orders.page_size', '20', 'NUMBER', 'commerce', 'PUBLIC', 'Default page size used by uniapp commerce order list.', 1),
  (184, 'commerce.bundle.discount_tier_1_min_items', '2', 'NUMBER', 'commerce', 'PUBLIC', 'Minimum selected products for the first bundle discount tier.', 1),
  (185, 'commerce.bundle.discount_tier_1_pct', '5', 'NUMBER', 'commerce', 'PUBLIC', 'First bundle discount percentage shown in uniapp bundle checkout.', 1),
  (186, 'commerce.bundle.discount_tier_2_min_items', '3', 'NUMBER', 'commerce', 'PUBLIC', 'Minimum selected products for the second bundle discount tier.', 1),
  (187, 'commerce.bundle.discount_tier_2_pct', '8', 'NUMBER', 'commerce', 'PUBLIC', 'Second bundle discount percentage shown in uniapp bundle checkout.', 1),
  (188, 'commerce.bundle.discount_tier_3_min_items', '4', 'NUMBER', 'commerce', 'PUBLIC', 'Minimum selected products for the third bundle discount tier.', 1),
  (189, 'commerce.bundle.discount_tier_3_pct', '12', 'NUMBER', 'commerce', 'PUBLIC', 'Third bundle discount percentage shown in uniapp bundle checkout.', 1),
  (210, 'commerce.bundle.default_selected_count', '2', 'NUMBER', 'commerce', 'PUBLIC', 'Default number of products selected on uniapp bundle page when no query item is provided.', 1),
  (211, 'commerce.bundle.suggestion_limit', '4', 'NUMBER', 'commerce', 'PUBLIC', 'Maximum number of product suggestions shown on uniapp bundle page.', 1),
  (190, 'commerce.yield.month_days', '30', 'NUMBER', 'commerce', 'PUBLIC', 'Day count used for monthly yield estimates in store pages.', 1),
  (191, 'commerce.yield.year_days', '365', 'NUMBER', 'commerce', 'PUBLIC', 'Day count used for annual yield estimates in store pages.', 1),
  (192, 'commerce.payback.month_threshold_days', '60', 'NUMBER', 'commerce', 'PUBLIC', 'Payback day threshold for switching the product page label to months.', 1),
  (193, 'commerce.checkout.max_quantity', '6', 'NUMBER', 'commerce', 'PUBLIC', 'Maximum product quantity selectable in uniapp store checkout.', 1),
  (194, 'commerce.inventory.low_stock_threshold', '50', 'NUMBER', 'commerce', 'PUBLIC', 'Stock threshold for low-inventory badges in uniapp product detail.', 1),
  (214, 'commerce.inventory.stock_progress_capacity', '50', 'NUMBER', 'commerce', 'PUBLIC', 'Stock quantity used as the full-scale denominator for uniapp store inventory progress bars.', 1),
  (195, 'commerce.share.min_stake_usdt', '199', 'NUMBER', 'commerce', 'PUBLIC', 'Minimum share product stake amount shown in the uniapp store.', 1),
  (196, 'commerce.share.redemption_days', '30', 'NUMBER', 'commerce', 'PUBLIC', 'Share product redemption waiting days shown in the uniapp store.', 1),
  (197, 'commerce.store.datacenter_label', 'Singapore DC', 'STRING', 'commerce', 'PUBLIC', 'Default datacenter label shown on store product cards when product-specific data is absent.', 1),
  (198, 'commerce.store.sla_uptime_pct', '99.9', 'NUMBER', 'commerce', 'PUBLIC', 'Uptime SLA percentage shown in the store hero.', 1),
  (199, 'commerce.store.shipping_label', 'zero shipping', 'STRING', 'commerce', 'PUBLIC', 'Shipping promise label shown in the store hero.', 1),
  (200, 'commerce.store.phone_daily_usdt_baseline', '0.08', 'NUMBER', 'commerce', 'PUBLIC', 'Phone daily USDT baseline used for store comparison multipliers.', 1),
  (212, 'commerce.store.ladder_product_limit', '4', 'NUMBER', 'commerce', 'PUBLIC', 'Maximum number of hardware products used by the uniapp store income ladder.', 1),
  (201, 'commerce.review.media_max_count', '6', 'NUMBER', 'commerce', 'PUBLIC', 'Maximum number of image/video media objects allowed on a product review.', 1),
  (202, 'commerce.review.video_max_duration_sec', '30', 'NUMBER', 'commerce', 'PUBLIC', 'Maximum product review video duration in seconds.', 1),
  (203, 'commerce.review.title_max_length', '64', 'NUMBER', 'commerce', 'PUBLIC', 'Maximum product review title length in characters.', 1),
  (204, 'commerce.review.content_max_length', '800', 'NUMBER', 'commerce', 'PUBLIC', 'Maximum product review content length in characters.', 1),
  (215, 'commerce.review.content_min_length', '10', 'NUMBER', 'commerce', 'PUBLIC', 'Minimum product review content length in characters.', 1),
  (64, 'wallet.withdrawal.daily_count_limit', '1', 'NUMBER', 'wallet', 'PUBLIC', 'Maximum successful/non-rejected withdrawal requests per user per day.', 1),
  (65, 'wallet.withdrawal.max_balance_pct', '0.80', 'NUMBER', 'wallet', 'PUBLIC', 'Maximum withdrawal amount as a percentage of available USDT balance.', 1),
  (138, 'wallet.withdrawal.trc20.enabled', 'true', 'BOOLEAN', 'wallet', 'PUBLIC', 'Whether USDT TRC20 withdrawal is visible in the app and accepted by wallet service.', 1),
  (139, 'wallet.withdrawal.trc20.label', 'TRC20', 'STRING', 'wallet', 'PUBLIC', 'USDT TRC20 withdrawal network label shown in the app.', 1),
  (140, 'wallet.withdrawal.trc20.hint', 'Lowest fee · fastest', 'STRING', 'wallet', 'PUBLIC', 'USDT TRC20 withdrawal network hint shown in the app.', 1),
  (141, 'wallet.withdrawal.erc20.enabled', 'true', 'BOOLEAN', 'wallet', 'PUBLIC', 'Whether USDT ERC20 withdrawal is visible in the app and accepted by wallet service.', 1),
  (142, 'wallet.withdrawal.erc20.label', 'ERC20', 'STRING', 'wallet', 'PUBLIC', 'USDT ERC20 withdrawal network label shown in the app.', 1),
  (143, 'wallet.withdrawal.erc20.hint', 'Ethereum wallet', 'STRING', 'wallet', 'PUBLIC', 'USDT ERC20 withdrawal network hint shown in the app.', 1),
  (66, 'wallet.deposit.trc20.enabled', 'false', 'BOOLEAN', 'wallet', 'PUBLIC', 'Whether the USDT TRC20 deposit channel is visible in the app.', 1),
  (67, 'wallet.deposit.trc20.address', '', 'STRING', 'wallet', 'PUBLIC', 'USDT TRC20 deposit address shown in the app.', 1),
  (68, 'wallet.deposit.trc20.min_confirmations', '20', 'NUMBER', 'wallet', 'PUBLIC', 'Minimum TRON confirmations before crediting USDT deposits.', 1),
  (69, 'wallet.deposit.trc20.min_amount_usdt', '10', 'NUMBER', 'wallet', 'PUBLIC', 'Minimum USDT TRC20 deposit amount shown in the app.', 1),
  (70, 'wallet.deposit.erc20.enabled', 'false', 'BOOLEAN', 'wallet', 'PUBLIC', 'Whether the USDT ERC20 deposit channel is visible in the app.', 1),
  (71, 'wallet.deposit.erc20.address', '', 'STRING', 'wallet', 'PUBLIC', 'USDT ERC20 deposit address shown in the app.', 1),
  (72, 'wallet.deposit.erc20.min_confirmations', '12', 'NUMBER', 'wallet', 'PUBLIC', 'Minimum Ethereum confirmations before crediting USDT deposits.', 1),
  (73, 'wallet.deposit.erc20.min_amount_usdt', '20', 'NUMBER', 'wallet', 'PUBLIC', 'Minimum USDT ERC20 deposit amount shown in the app.', 1),
  (74, 'wallet.nex_lock.apy_bps', '25000', 'NUMBER', 'wallet', 'PUBLIC', 'NEX v2 lock APY in basis points.', 1),
  (75, 'wallet.nex_lock.default_term_months', '24', 'NUMBER', 'wallet', 'PUBLIC', 'Default NEX v2 lock term in months.', 1),
  (76, 'wallet.nex_lock.min_amount_nex', '1000', 'NUMBER', 'wallet', 'PUBLIC', 'Minimum NEX v2 lock amount shown in the app and enforced by wallet service.', 1),
  (83, 'wallet.repurchase.boost_threshold_nex', '5000', 'NUMBER', 'wallet', 'PUBLIC', 'Locked NEX threshold for repurchase boost.', 1),
  (84, 'wallet.repurchase.boost_multiplier', '1.5', 'NUMBER', 'wallet', 'PUBLIC', 'Repurchase boost multiplier when threshold is met.', 1),
  (85, 'wallet.repurchase.bonus_points', '500', 'NUMBER', 'wallet', 'PUBLIC', 'Bonus points when repurchase boost threshold is met.', 1),
  (86, 'wallet.nex.boost_low_threshold', '1000', 'NUMBER', 'wallet', 'PUBLIC', 'Lower NEX balance threshold for earning boost.', 1),
  (87, 'wallet.nex.boost_low_pct', '4', 'NUMBER', 'wallet', 'PUBLIC', 'Earning boost percentage once low threshold is met.', 1),
  (88, 'wallet.nex.boost_high_threshold', '5000', 'NUMBER', 'wallet', 'PUBLIC', 'Higher NEX balance threshold for earning boost.', 1),
  (89, 'wallet.nex.boost_high_pct', '10', 'NUMBER', 'wallet', 'PUBLIC', 'Earning boost percentage once high threshold is met.', 1),
  (90, 'wallet.nex.fee_discount_target_nex', '5000', 'NUMBER', 'wallet', 'PUBLIC', 'NEX balance target used by the app fee discount progress.', 1),
  (91, 'wallet.nex_market.volatility_pct_per_hour', '3', 'NUMBER', 'wallet', 'PUBLIC', 'Market making volatility guardrail shown in PC G3.', 1),
  (92, 'wallet.nex_market.oracle_sources', '3', 'NUMBER', 'wallet', 'PUBLIC', 'Number of oracle sources used for NEX price display.', 1),
  (93, 'wallet.nex_market.pump_curve', 'STEP', 'STRING', 'wallet', 'PUBLIC', 'Structured pump curve mode used by market operations.', 1),
  (154, 'wallet.nex_market.circulating_supply', '24000000', 'NUMBER', 'wallet', 'PUBLIC', 'Current circulating NEX supply used by the app market page.', 1),
  (155, 'wallet.nex_market.total_supply', '100000000', 'NUMBER', 'wallet', 'PUBLIC', 'Total NEX supply used to derive FDV on the app market page.', 1),
  (156, 'wallet.nex_market.volume_24h_usdt', '980000', 'NUMBER', 'wallet', 'PUBLIC', 'Reported 24h NEX market volume in USDT shown on the app market page.', 1),
  (216, 'wallet.genesis.daily_dividend_rate_pct', '0.1', 'NUMBER', 'wallet', 'PUBLIC', 'Daily Genesis dividend rate percentage shown in PC G4 and uniapp Genesis pages.', 1),
  (217, 'wallet.genesis.daily_volume_base_usdt', '24000000', 'NUMBER', 'wallet', 'PUBLIC', 'Volume base used to estimate Genesis dividend economics.', 1),
  (218, 'wallet.genesis.secondary_floor_usdt', '25000', 'NUMBER', 'wallet', 'PUBLIC', 'Secondary market floor price shown in uniapp Genesis pages.', 1),
  (219, 'wallet.genesis.secondary_market_paused', 'false', 'BOOLEAN', 'wallet', 'PUBLIC', 'Emergency pause flag consumed by uniapp Genesis marketplace.', 1),
  (94, 'team.invite.reward_usdt', '400', 'NUMBER', 'team', 'PUBLIC', 'Invite reward USDT shown on team home.', 1),
  (95, 'team.invite.previous_reward_usdt', '200', 'NUMBER', 'team', 'PUBLIC', 'Previous invite reward USDT shown as comparison.', 1),
  (96, 'team.invite.reward_nex', '400', 'NUMBER', 'team', 'PUBLIC', 'Invite reward NEX shown on team home.', 1),
  (97, 'team.invite.cooldown_days', '30', 'NUMBER', 'team', 'PUBLIC', 'Invite reward cooldown days.', 1),
  (99, 'team.leaderboard.weekly_pool_usdt', '50000', 'NUMBER', 'team', 'PUBLIC', 'Weekly leaderboard pool shown on team home.', 1),
  (101, 'team.unilevel.l1_usdt_pct', '10', 'NUMBER', 'team', 'PUBLIC', 'L1 unilevel USDT rate shown on team home.', 1),
  (102, 'team.binary.track_min_usd', '1000', 'NUMBER', 'team', 'PUBLIC', 'Minimum volume required on each binary lane.', 1),
  (103, 'team.binary.match_rate_pct', '10', 'NUMBER', 'team', 'PUBLIC', 'Weak-side binary match rate shown in PC and app.', 1),
  (104, 'team.binary.daily_cap_usdt', '5000', 'NUMBER', 'team', 'PUBLIC', 'Current binary daily cap shown in PC and app.', 1),
  (105, 'team.binary.next_daily_cap_usdt', '2000', 'NUMBER', 'team', 'PUBLIC', 'Next scheduled binary cap shown to operators.', 1),
  (106, 'team.binary.next_cap_day', '7', 'NUMBER', 'team', 'PUBLIC', 'Calendar day when the next binary cap takes effect.', 1),
  (107, 'team.binary.gv_reset_day', '1', 'NUMBER', 'team', 'PUBLIC', 'Monthly GV reset day for binary settlement.', 1),
  (108, 'team.binary.auto_placement_enabled', 'true', 'BOOLEAN', 'team', 'PUBLIC', 'Whether binary auto-placement is enabled.', 1),
  (109, 'team.rate_tier.standard_min_usd', '0', 'NUMBER', 'team', 'PUBLIC', 'Standard direct royalty tier minimum.', 1),
  (110, 'team.rate_tier.standard_direct_pct', '8', 'NUMBER', 'team', 'PUBLIC', 'Standard direct royalty rate shown in PC F2.', 1),
  (111, 'team.rate_tier.standard_distribution_pct', '62', 'NUMBER', 'team', 'PUBLIC', 'Standard tier distribution shown in PC F2.', 1),
  (112, 'team.rate_tier.verified_min_usd', '5000', 'NUMBER', 'team', 'PUBLIC', 'Verified direct royalty tier minimum.', 1),
  (113, 'team.rate_tier.verified_direct_pct', '10', 'NUMBER', 'team', 'PUBLIC', 'Verified direct royalty rate shown in PC F2.', 1),
  (114, 'team.rate_tier.verified_distribution_pct', '24', 'NUMBER', 'team', 'PUBLIC', 'Verified tier distribution shown in PC F2.', 1),
  (115, 'team.rate_tier.premium_min_usd', '50000', 'NUMBER', 'team', 'PUBLIC', 'Premium direct royalty tier minimum.', 1),
  (116, 'team.rate_tier.premium_direct_pct', '12', 'NUMBER', 'team', 'PUBLIC', 'Premium direct royalty rate shown in PC F2.', 1),
  (117, 'team.rate_tier.premium_distribution_pct', '11', 'NUMBER', 'team', 'PUBLIC', 'Premium tier distribution shown in PC F2.', 1),
  (118, 'team.rate_tier.diamond_min_usd', '500000', 'NUMBER', 'team', 'PUBLIC', 'Diamond direct royalty tier minimum.', 1),
  (119, 'team.rate_tier.diamond_direct_pct', '15', 'NUMBER', 'team', 'PUBLIC', 'Diamond direct royalty rate shown in PC F2.', 1),
  (120, 'team.rate_tier.diamond_distribution_pct', '3', 'NUMBER', 'team', 'PUBLIC', 'Diamond tier distribution shown in PC F2.', 1),
  (121, 'team.influence_score.min', '1', 'NUMBER', 'team', 'PUBLIC', 'Influence score lower clamp.', 1),
  (122, 'team.influence_score.max', '5', 'NUMBER', 'team', 'PUBLIC', 'Influence score upper clamp.', 1),
  (123, 'team.promo.weekly_multiplier', '1', 'NUMBER', 'team', 'PUBLIC', 'Weekly promotion multiplier shown in PC F2.', 1),
  (261, 'team.commission.trigger_target_pct', '80', 'NUMBER', 'team', 'PUBLIC', 'Target percentage for monthly paid orders that should trigger commission events.', 1),
  (124, 'team.leadership.platform_volume_usdt', '1000000', 'NUMBER', 'team', 'PUBLIC', 'Weekly GMV snapshot used to preview the leadership pool.', 1),
  (125, 'team.leadership.settlement_weekday_utc', '7', 'NUMBER', 'team', 'PUBLIC', 'UTC weekday used for leadership pool settlement display.', 1),
  (126, 'team.leadership.settlement_hour_utc', '23', 'NUMBER', 'team', 'PUBLIC', 'UTC hour used for leadership pool settlement display.', 1),
  (244, 'team.hardware.pro_name', 'NexionBox Pro', 'STRING', 'team', 'PUBLIC', 'Hardware quota Pro display name shown in uniapp.', 1),
  (245, 'team.hardware.pro_price_usdt', '3999', 'NUMBER', 'team', 'PUBLIC', 'Hardware quota Pro public price shown in uniapp.', 1),
  (246, 'team.hardware.pro_note', 'For creators scaling regional compute demand.', 'STRING', 'team', 'PUBLIC', 'Hardware quota Pro note shown in uniapp.', 1),
  (127, 'team.hardware.pro_direct_refs', '5', 'NUMBER', 'team', 'PUBLIC', 'Activated direct invites required to unlock NexionBox Pro.', 1),
  (128, 'team.hardware.pro_month_volume_usd', '50000', 'NUMBER', 'team', 'PUBLIC', 'Monthly team volume guardrail shown for Pro quota.', 1),
  (247, 'team.hardware.rack_name', 'NexionRack', 'STRING', 'team', 'PUBLIC', 'Hardware quota Rack display name shown in uniapp.', 1),
  (248, 'team.hardware.rack_price_usdt', '19999', 'NUMBER', 'team', 'PUBLIC', 'Hardware quota Rack public price shown in uniapp.', 1),
  (249, 'team.hardware.rack_note', 'For regional operators with dedicated deployment capacity.', 'STRING', 'team', 'PUBLIC', 'Hardware quota Rack note shown in uniapp.', 1),
  (129, 'team.hardware.rack_direct_refs', '15', 'NUMBER', 'team', 'PUBLIC', 'Activated direct invites required to unlock NexionRack.', 1),
  (130, 'team.hardware.rack_month_volume_usd', '20000', 'NUMBER', 'team', 'PUBLIC', 'Monthly team volume alternative unlock for Rack quota.', 1),
  (131, 'team.hardware.monthly_stock_limit', '96', 'NUMBER', 'team', 'PUBLIC', 'Monthly quota stock ceiling shown in PC and app.', 1),
  (180, 'team.hardware.pro_stock_pct', '72', 'NUMBER', 'team', 'PUBLIC', 'Percentage of monthly hardware quota allocated to Pro devices.', 1),
  (181, 'team.hardware.rack_stock_pct', '28', 'NUMBER', 'team', 'PUBLIC', 'Percentage of monthly hardware quota allocated to Rack devices.', 1),
  (132, 'team.agent.eligibility_rank', '5', 'NUMBER', 'team', 'PUBLIC', 'Minimum V rank required to submit regional ambassador applications.', 1),
  (134, 'team.agent.kol_budget_pct', '50', 'NUMBER', 'team', 'PUBLIC', 'KOL campaign match percentage shown in F7.', 1),
  (135, 'team.agent.annual_budget_usdt', '50000', 'NUMBER', 'team', 'PUBLIC', 'Annual budget shown to eligible regional ambassadors.', 1),
  (250, 'team.agent.event_title', 'Offline event venue', 'STRING', 'team', 'PUBLIC', 'Regional ambassador event benefit title.', 1),
  (251, 'team.agent.event_value', '$1,000-$10,000', 'STRING', 'team', 'PUBLIC', 'Regional ambassador event benefit value.', 1),
  (252, 'team.agent.event_note', 'Attendance >= 100 verified by sign-in QR.', 'STRING', 'team', 'PUBLIC', 'Regional ambassador event benefit note.', 1),
  (253, 'team.agent.kol_title', 'KOL campaign match', 'STRING', 'team', 'PUBLIC', 'Regional ambassador KOL benefit title.', 1),
  (254, 'team.agent.kol_note', 'Invoice and traffic logs required.', 'STRING', 'team', 'PUBLIC', 'Regional ambassador KOL benefit note.', 1),
  (255, 'team.agent.materials_title', 'Print materials', 'STRING', 'team', 'PUBLIC', 'Regional ambassador materials benefit title.', 1),
  (256, 'team.agent.materials_value', 'Region quota', 'STRING', 'team', 'PUBLIC', 'Regional ambassador materials benefit value.', 1),
  (257, 'team.agent.materials_note', 'Banner, brochure and local launch kits.', 'STRING', 'team', 'PUBLIC', 'Regional ambassador materials benefit note.', 1),
  (258, 'team.agent.sdk_title', 'SDK / dev support', 'STRING', 'team', 'PUBLIC', 'Regional ambassador technical support benefit title.', 1),
  (259, 'team.agent.sdk_value', 'Hourly', 'STRING', 'team', 'PUBLIC', 'Regional ambassador technical support benefit value.', 1),
  (260, 'team.agent.sdk_note', 'Custom integrations for top customers.', 'STRING', 'team', 'PUBLIC', 'Regional ambassador technical support benefit note.', 1),
  (136, 'team.leaderboard.top_n', '50', 'NUMBER', 'team', 'PUBLIC', 'Top-N leaderboard winners for the current period.', 1),
  (3, 'risk.withdrawal.kyc_required', 'true', 'BOOLEAN', 'risk', 'ADMIN', 'Require KYC before withdrawal.', 1),
  (4, 'openapi.default_qps_limit', '20', 'NUMBER', 'openapi', 'ADMIN', 'Default OpenAPI per-app QPS quota.', 1),
  (5, 'openapi.default_daily_limit', '10000', 'NUMBER', 'openapi', 'ADMIN', 'Default OpenAPI per-app daily quota.', 1),
  (6, 'risk.withdrawal.review_amount', '1000', 'NUMBER', 'risk', 'ADMIN', 'Withdrawal amount that triggers manual review.', 1),
  (7, 'risk.exchange.review_amount', '5000', 'NUMBER', 'risk', 'ADMIN', 'Exchange amount that triggers manual review.', 1),
  (8, 'feature.genesis.enabled', 'true', 'BOOLEAN', 'feature', 'PUBLIC', 'Genesis feature launch switch.', 1),
  (9, 'risk.region.blocked', '', 'STRING', 'risk', 'ADMIN', 'Comma-separated regions that should be rejected by Compliance gate.', 1),
  (10, 'risk.region.review', '', 'STRING', 'risk', 'ADMIN', 'Comma-separated regions that should enter manual Compliance review.', 1),
  (11, 'risk.low_tier.review_amount', '100', 'NUMBER', 'risk', 'ADMIN', 'Low-tier withdrawal amount that triggers manual review.', 1),
  (12, 'risk.low_tier.levels', 'L0,L1', 'STRING', 'risk', 'ADMIN', 'User levels subject to low-tier withdrawal review amount.', 1),
  (13, 'risk.ip.daily_review_count', '10', 'NUMBER', 'risk', 'ADMIN', 'Daily same-IP decision count that triggers manual review.', 1),
  (14, 'risk.device.daily_review_count', '5', 'NUMBER', 'risk', 'ADMIN', 'Daily same-device decision count that triggers manual review.', 1),
  (15, 'risk.genesis.review_amount', '10000', 'NUMBER', 'risk', 'ADMIN', 'Genesis purchase amount that triggers manual review.', 1),
  (435, 'auth.risk.otp_ttl_seconds', '300', 'NUMBER', 'auth', 'ADMIN', 'C6 registration/login OTP validity in seconds.', 1),
  (436, 'auth.risk.otp_quota_per_hour', '5', 'NUMBER', 'auth', 'ADMIN', 'C6 registration/login OTP send quota per hour.', 1),
  (437, 'auth.risk.login_lock_threshold', '5', 'NUMBER', 'auth', 'ADMIN', 'C6 failed-login count before locking the account.', 1),
  (438, 'auth.risk.lock_duration_minutes', '30', 'NUMBER', 'auth', 'ADMIN', 'C6 account lock duration in minutes.', 1),
  (439, 'auth.risk.captcha_trigger_failures', '3', 'NUMBER', 'auth', 'ADMIN', 'C6 failed-login count before CAPTCHA is required.', 1),
  (440, 'auth.risk.sponsor_bind_dedup_enabled', 'true', 'BOOLEAN', 'auth', 'ADMIN', 'C6 sponsor-bind deduplication switch.', 1),
  (1801, 'profile.region.singapore.label', 'Singapore', 'STRING', 'profile', 'PUBLIC', 'App profile selectable region: Singapore.', 1),
  (1802, 'profile.region.hong_kong.label', 'Hong Kong', 'STRING', 'profile', 'PUBLIC', 'App profile selectable region: Hong Kong.', 1),
  (1803, 'profile.region.tokyo.label', 'Tokyo, JP', 'STRING', 'profile', 'PUBLIC', 'App profile selectable region: Tokyo.', 1),
  (1804, 'profile.region.seoul.label', 'Seoul, KR', 'STRING', 'profile', 'PUBLIC', 'App profile selectable region: Seoul.', 1),
  (1805, 'profile.region.berlin.label', 'Berlin, DE', 'STRING', 'profile', 'PUBLIC', 'App profile selectable region: Berlin.', 1),
  (1806, 'profile.region.london.label', 'London, UK', 'STRING', 'profile', 'PUBLIC', 'App profile selectable region: London.', 1),
  (1807, 'profile.region.dubai.label', 'Dubai, AE', 'STRING', 'profile', 'PUBLIC', 'App profile selectable region: Dubai.', 1),
  (1808, 'profile.region.new_york.label', 'New York, US', 'STRING', 'profile', 'PUBLIC', 'App profile selectable region: New York.', 1),
  (1809, 'profile.timezone.asia_singapore.label', 'Asia/Singapore (UTC+8)', 'STRING', 'profile', 'PUBLIC', 'App profile selectable timezone: Asia/Singapore.', 1),
  (1810, 'profile.timezone.asia_tokyo.label', 'Asia/Tokyo (UTC+9)', 'STRING', 'profile', 'PUBLIC', 'App profile selectable timezone: Asia/Tokyo.', 1),
  (1811, 'profile.timezone.asia_hong_kong.label', 'Asia/Hong_Kong (UTC+8)', 'STRING', 'profile', 'PUBLIC', 'App profile selectable timezone: Asia/Hong Kong.', 1),
  (1812, 'profile.timezone.europe_berlin.label', 'Europe/Berlin (UTC+1)', 'STRING', 'profile', 'PUBLIC', 'App profile selectable timezone: Europe/Berlin.', 1),
  (1813, 'profile.timezone.europe_london.label', 'Europe/London (UTC+0)', 'STRING', 'profile', 'PUBLIC', 'App profile selectable timezone: Europe/London.', 1),
  (1814, 'profile.timezone.asia_dubai.label', 'Asia/Dubai (UTC+4)', 'STRING', 'profile', 'PUBLIC', 'App profile selectable timezone: Asia/Dubai.', 1),
  (1815, 'profile.timezone.america_new_york.label', 'America/New_York (UTC-5)', 'STRING', 'profile', 'PUBLIC', 'App profile selectable timezone: America/New York.', 1),
  (1816, 'profile.avatar.mech_lime.accent', '#c6ff3a', 'STRING', 'profile', 'PUBLIC', 'App profile mechanical avatar lime accent color.', 1),
  (1817, 'profile.avatar.mech_lime.glow', 'rgba(198, 255, 58, 0.22)', 'STRING', 'profile', 'PUBLIC', 'App profile mechanical avatar lime glow color.', 1),
  (1818, 'profile.avatar.mech_cyan.accent', '#46e6ff', 'STRING', 'profile', 'PUBLIC', 'App profile mechanical avatar cyan accent color.', 1),
  (1819, 'profile.avatar.mech_cyan.glow', 'rgba(70, 230, 255, 0.2)', 'STRING', 'profile', 'PUBLIC', 'App profile mechanical avatar cyan glow color.', 1),
  (1820, 'profile.avatar.mech_violet.accent', '#b89cff', 'STRING', 'profile', 'PUBLIC', 'App profile mechanical avatar violet accent color.', 1),
  (1821, 'profile.avatar.mech_violet.glow', 'rgba(184, 156, 255, 0.22)', 'STRING', 'profile', 'PUBLIC', 'App profile mechanical avatar violet glow color.', 1),
  (1822, 'profile.avatar.mech_amber.accent', '#ffb84d', 'STRING', 'profile', 'PUBLIC', 'App profile mechanical avatar amber accent color.', 1),
  (1823, 'profile.avatar.mech_amber.glow', 'rgba(255, 184, 77, 0.22)', 'STRING', 'profile', 'PUBLIC', 'App profile mechanical avatar amber glow color.', 1),
  (1824, 'profile.avatar.mech_rose.accent', '#ff7896', 'STRING', 'profile', 'PUBLIC', 'App profile mechanical avatar rose accent color.', 1),
  (1825, 'profile.avatar.mech_rose.glow', 'rgba(255, 120, 150, 0.2)', 'STRING', 'profile', 'PUBLIC', 'App profile mechanical avatar rose glow color.', 1),
  (313, 'risk.score.weight.multi_account', '25', 'NUMBER', 'risk', 'ADMIN', 'K4 risk score weight for multi-account signals.', 1),
  (314, 'risk.score.weight.arbitrage', '22', 'NUMBER', 'risk', 'ADMIN', 'K4 risk score weight for arbitrage and brush signals.', 1),
  (315, 'risk.score.weight.kyc_state', '18', 'NUMBER', 'risk', 'ADMIN', 'K4 risk score weight for KYC state.', 1),
  (316, 'risk.score.weight.withdraw_speed', '15', 'NUMBER', 'risk', 'ADMIN', 'K4 risk score weight for withdrawal speed.', 1),
  (317, 'risk.score.weight.account_age', '12', 'NUMBER', 'risk', 'ADMIN', 'K4 risk score weight for account age.', 1),
  (318, 'risk.score.weight.anomaly', '8', 'NUMBER', 'risk', 'ADMIN', 'K4 risk score weight for anomaly behavior.', 1),
  (319, 'analytics.kpi.registered_users.value', '96.4', 'NUMBER', 'analytics', 'ADMIN', 'L1 KPI Day 0 automatic onboarding rate.', 1),
  (320, 'analytics.kpi.day7_retention_pct', '58.2', 'NUMBER', 'analytics', 'ADMIN', 'L1 KPI Day 7 retention percentage.', 1),
  (321, 'analytics.kpi.store_visit_conversion_pct', '34.1', 'NUMBER', 'analytics', 'ADMIN', 'L1 KPI L2 to L3 store visit conversion percentage.', 1),
  (322, 'analytics.kpi.order_conversion_pct', '6.8', 'NUMBER', 'analytics', 'ADMIN', 'L1 KPI L3 to L4 order conversion percentage.', 1),
  (323, 'analytics.kpi.referral_conversion_pct', '41.5', 'NUMBER', 'analytics', 'ADMIN', 'L1 KPI L4 to L5 referral conversion percentage.', 1),
  (324, 'analytics.kpi.nova_push_ctr_pct', '27.3', 'NUMBER', 'analytics', 'ADMIN', 'L1 KPI Nova push CTR percentage.', 1),
  (325, 'analytics.kpi.team_commission_trigger_pct', '76.0', 'NUMBER', 'analytics', 'ADMIN', 'L1 KPI team commission trigger percentage.', 1),
  (326, 'analytics.kpi.genesis_sellout_days', '11', 'NUMBER', 'analytics', 'ADMIN', 'L1 KPI Genesis sellout speed in days.', 1),
  (327, 'analytics.revenue.hardware_gmv', '4280000', 'NUMBER', 'analytics', 'ADMIN', 'L3 monthly hardware GMV.', 1),
  (328, 'analytics.revenue.team_commission', '1140000', 'NUMBER', 'analytics', 'ADMIN', 'L3 monthly team commission revenue.', 1),
  (329, 'analytics.revenue.token_economy', '980000', 'NUMBER', 'analytics', 'ADMIN', 'L3 monthly token economy revenue.', 1),
  (330, 'analytics.revenue.compute_market_fee', '312000', 'NUMBER', 'analytics', 'ADMIN', 'L3 monthly compute market service fee.', 1),
  (331, 'analytics.treasury.coverage_ratio_pct', '128.4', 'NUMBER', 'analytics', 'ADMIN', 'L3 treasury coverage ratio percentage.', 1),
  (332, 'analytics.treasury.net_exposure_usdt', '4200000', 'NUMBER', 'analytics', 'ADMIN', 'L3 net exposure in USDT.', 1),
  (333, 'analytics.treasury.maturity_7d_usdt', '3100000', 'NUMBER', 'analytics', 'ADMIN', 'L3 seven-day maturity redemption amount in USDT.', 1),
  (334, 'analytics.treasury.reserve_cover_days', '38', 'NUMBER', 'analytics', 'ADMIN', 'L3 reserve coverage days.', 1),
  (335, 'analytics.ops.online_devices', '41208', 'NUMBER', 'analytics', 'ADMIN', 'L4 online compute device count.', 1),
  (336, 'analytics.ops.completed_jobs_24h', '1.84M', 'STRING', 'analytics', 'ADMIN', 'L4 completed jobs in the last 24 hours.', 1),
  (337, 'analytics.ops.network_throughput_usd_per_sec', '215', 'NUMBER', 'analytics', 'ADMIN', 'L4 network throughput in USD per second.', 1),
  (338, 'analytics.ops.task_success_rate_pct', '99.2', 'NUMBER', 'analytics', 'ADMIN', 'L4 task success rate percentage.', 1),
  (339, 'analytics.ops.avg_device_daily_usdt', '3.42', 'NUMBER', 'analytics', 'ADMIN', 'L4 average device daily earning in USDT.', 1),
  (340, 'analytics.ops.dc_region_count', '3', 'NUMBER', 'analytics', 'ADMIN', 'L4 datacenter region count.', 1),
  (341, 'analytics.ops.queue_saturation_pct', '63', 'NUMBER', 'analytics', 'ADMIN', 'L4 queue saturation percentage.', 1),
  (342, 'analytics.ops.genesis_online_nodes', '812', 'NUMBER', 'analytics', 'ADMIN', 'L4 online Genesis node count.', 1),
  (343, 'analytics.report.schedule', '每月 5 日', 'STRING', 'analytics', 'ADMIN', 'L5 regulatory report schedule.', 1),
  (344, 'analytics.report.bill_csv.last_run', '', 'STRING', 'analytics', 'ADMIN', 'L5 bill CSV last generated timestamp.', 1),
  (345, 'analytics.report.withdraw_compliance.last_run', '', 'STRING', 'analytics', 'ADMIN', 'L5 withdrawal compliance report last generated timestamp.', 1),
  (346, 'analytics.report.kyc_ledger.last_run', '', 'STRING', 'analytics', 'ADMIN', 'L5 KYC ledger report last generated timestamp.', 1),
  (347, 'analytics.report.commission_payout.last_run', '', 'STRING', 'analytics', 'ADMIN', 'L5 commission payout report last generated timestamp.', 1),
  (790, 'analytics.kpi.registered_users.target', '95', 'NUMBER', 'analytics', 'ADMIN', 'L1 KPI Day 0 automatic onboarding target.', 1),
  (791, 'analytics.kpi.day7_retention_target_pct', '60', 'NUMBER', 'analytics', 'ADMIN', 'L1/L2 Day 7 retention target percentage.', 1),
  (792, 'analytics.kpi.store_visit_conversion_target_pct', '30', 'NUMBER', 'analytics', 'ADMIN', 'L1 KPI store visit conversion target percentage.', 1),
  (793, 'analytics.kpi.order_conversion_band_min_pct', '5', 'NUMBER', 'analytics', 'ADMIN', 'L1 KPI order conversion acceptable band minimum percentage.', 1),
  (794, 'analytics.kpi.order_conversion_band_max_pct', '10', 'NUMBER', 'analytics', 'ADMIN', 'L1 KPI order conversion acceptable band maximum percentage.', 1),
  (795, 'analytics.kpi.referral_conversion_target_pct', '40', 'NUMBER', 'analytics', 'ADMIN', 'L1 KPI referral conversion target percentage.', 1),
  (796, 'analytics.kpi.nova_push_ctr_target_pct', '25', 'NUMBER', 'analytics', 'ADMIN', 'L1 KPI Nova push CTR target percentage.', 1),
  (797, 'analytics.kpi.team_commission_trigger_target_pct', '80', 'NUMBER', 'analytics', 'ADMIN', 'L1 KPI team commission trigger target percentage.', 1),
  (798, 'analytics.kpi.genesis_sellout_target_days', '14', 'NUMBER', 'analytics', 'ADMIN', 'L1 KPI Genesis sellout target in days.', 1),
  (799, 'analytics.cohort.w19.registered_users', '22104', 'NUMBER', 'analytics', 'ADMIN', 'L2 cohort W19 registered user count.', 1),
  (800, 'analytics.cohort.w19.day7_retention_pct', '61', 'NUMBER', 'analytics', 'ADMIN', 'L2 cohort W19 Day 7 retention percentage.', 1),
  (801, 'analytics.cohort.w19.l2_l3_pct', '33', 'NUMBER', 'analytics', 'ADMIN', 'L2 cohort W19 L2 to L3 conversion percentage.', 1),
  (802, 'analytics.cohort.w19.l3_l4_pct', '6.2', 'NUMBER', 'analytics', 'ADMIN', 'L2 cohort W19 L3 to L4 conversion percentage.', 1),
  (803, 'analytics.cohort.w19.reinvest_pct', '24', 'NUMBER', 'analytics', 'ADMIN', 'L2 cohort W19 reinvest percentage.', 1),
  (804, 'analytics.cohort.w20.registered_users', '24880', 'NUMBER', 'analytics', 'ADMIN', 'L2 cohort W20 registered user count.', 1),
  (805, 'analytics.cohort.w20.day7_retention_pct', '59', 'NUMBER', 'analytics', 'ADMIN', 'L2 cohort W20 Day 7 retention percentage.', 1),
  (806, 'analytics.cohort.w20.l2_l3_pct', '34', 'NUMBER', 'analytics', 'ADMIN', 'L2 cohort W20 L2 to L3 conversion percentage.', 1),
  (807, 'analytics.cohort.w20.l3_l4_pct', '6.6', 'NUMBER', 'analytics', 'ADMIN', 'L2 cohort W20 L3 to L4 conversion percentage.', 1),
  (808, 'analytics.cohort.w20.reinvest_pct', '26', 'NUMBER', 'analytics', 'ADMIN', 'L2 cohort W20 reinvest percentage.', 1),
  (809, 'analytics.cohort.w21.registered_users', '26420', 'NUMBER', 'analytics', 'ADMIN', 'L2 cohort W21 registered user count.', 1),
  (810, 'analytics.cohort.w21.day7_retention_pct', '58', 'NUMBER', 'analytics', 'ADMIN', 'L2 cohort W21 Day 7 retention percentage.', 1),
  (811, 'analytics.cohort.w21.l2_l3_pct', '35', 'NUMBER', 'analytics', 'ADMIN', 'L2 cohort W21 L2 to L3 conversion percentage.', 1),
  (812, 'analytics.cohort.w21.l3_l4_pct', '6.8', 'NUMBER', 'analytics', 'ADMIN', 'L2 cohort W21 L3 to L4 conversion percentage.', 1),
  (813, 'analytics.cohort.w21.reinvest_pct', '27', 'NUMBER', 'analytics', 'ADMIN', 'L2 cohort W21 reinvest percentage.', 1),
  (814, 'analytics.cohort.w22.registered_users', '28940', 'NUMBER', 'analytics', 'ADMIN', 'L2 cohort W22 registered user count.', 1),
  (815, 'analytics.cohort.w22.day7_retention_pct', '57', 'NUMBER', 'analytics', 'ADMIN', 'L2 cohort W22 Day 7 retention percentage.', 1),
  (816, 'analytics.cohort.w22.l2_l3_pct', '34', 'NUMBER', 'analytics', 'ADMIN', 'L2 cohort W22 L2 to L3 conversion percentage.', 1),
  (817, 'analytics.cohort.w22.l3_l4_pct', '6.9', 'NUMBER', 'analytics', 'ADMIN', 'L2 cohort W22 L3 to L4 conversion percentage.', 1),
  (818, 'analytics.cohort.w22.reinvest_pct', '25', 'NUMBER', 'analytics', 'ADMIN', 'L2 cohort W22 reinvest percentage.', 1),
  (819, 'analytics.retention.day7_trend.d1_pct', '61', 'NUMBER', 'analytics', 'ADMIN', 'L2 Day 7 retention trend day 1 percentage.', 1),
  (820, 'analytics.retention.day7_trend.d2_pct', '60', 'NUMBER', 'analytics', 'ADMIN', 'L2 Day 7 retention trend day 2 percentage.', 1),
  (821, 'analytics.retention.day7_trend.d3_pct', '59', 'NUMBER', 'analytics', 'ADMIN', 'L2 Day 7 retention trend day 3 percentage.', 1),
  (822, 'analytics.retention.day7_trend.d4_pct', '58', 'NUMBER', 'analytics', 'ADMIN', 'L2 Day 7 retention trend day 4 percentage.', 1),
  (823, 'analytics.retention.day7_trend.d5_pct', '57', 'NUMBER', 'analytics', 'ADMIN', 'L2 Day 7 retention trend day 5 percentage.', 1),
  (824, 'analytics.retention.day7_trend.d6_pct', '59', 'NUMBER', 'analytics', 'ADMIN', 'L2 Day 7 retention trend day 6 percentage.', 1),
  (825, 'analytics.retention.day7_trend.d7_pct', '58.2', 'NUMBER', 'analytics', 'ADMIN', 'L2 Day 7 retention trend day 7 percentage.', 1),
  (826, 'analytics.retention.day7_trend_note', '连续下滑', 'STRING', 'analytics', 'ADMIN', 'L2 Day 7 retention trend note.', 1),
  (827, 'analytics.retention.weakest_cohort_label', 'W22', 'STRING', 'analytics', 'ADMIN', 'L2 weakest retention cohort label.', 1),
  (828, 'analytics.retention.weakest_cohort_pct', '57', 'NUMBER', 'analytics', 'ADMIN', 'L2 weakest retention cohort percentage.', 1),
  (829, 'analytics.retention.root_cause', 'P3 拉新放量、质量下降', 'STRING', 'analytics', 'ADMIN', 'L2 retention root-cause summary.', 1),
  (830, 'analytics.ops.completed_jobs_7d.d1_units', '160', 'NUMBER', 'analytics', 'ADMIN', 'L4 completed jobs trend day 1 normalized units.', 1),
  (831, 'analytics.ops.completed_jobs_7d.d2_units', '170', 'NUMBER', 'analytics', 'ADMIN', 'L4 completed jobs trend day 2 normalized units.', 1),
  (832, 'analytics.ops.completed_jobs_7d.d3_units', '172', 'NUMBER', 'analytics', 'ADMIN', 'L4 completed jobs trend day 3 normalized units.', 1),
  (833, 'analytics.ops.completed_jobs_7d.d4_units', '180', 'NUMBER', 'analytics', 'ADMIN', 'L4 completed jobs trend day 4 normalized units.', 1),
  (834, 'analytics.ops.completed_jobs_7d.d5_units', '178', 'NUMBER', 'analytics', 'ADMIN', 'L4 completed jobs trend day 5 normalized units.', 1),
  (835, 'analytics.ops.completed_jobs_7d.d6_units', '182', 'NUMBER', 'analytics', 'ADMIN', 'L4 completed jobs trend day 6 normalized units.', 1),
  (836, 'analytics.ops.completed_jobs_7d.d7_units', '184', 'NUMBER', 'analytics', 'ADMIN', 'L4 completed jobs trend day 7 normalized units.', 1),
  (837, 'analytics.ops.dc.us_east_2.load_pct', '45', 'NUMBER', 'analytics', 'ADMIN', 'L4 DC load percentage for us-east-2.', 1),
  (838, 'analytics.ops.dc.eu_west_1.load_pct', '31', 'NUMBER', 'analytics', 'ADMIN', 'L4 DC load percentage for eu-west-1.', 1),
  (839, 'analytics.ops.dc.ap_southeast_1.load_pct', '24', 'NUMBER', 'analytics', 'ADMIN', 'L4 DC load percentage for ap-southeast-1.', 1),
  (348, 'command.phase.current.code', 'P3', 'STRING', 'command', 'ADMIN', 'Command center current phase code.', 1),
  (349, 'command.phase.current.name', '扩张期', 'STRING', 'command', 'ADMIN', 'Command center current phase name.', 1),
  (350, 'command.phase.current.index', '2', 'NUMBER', 'command', 'ADMIN', 'Command center current phase zero-based index.', 1),
  (351, 'command.phase.current.month', '7', 'NUMBER', 'command', 'ADMIN', 'Command center current phase month.', 1),
  (352, 'command.phase.current.total', '12', 'NUMBER', 'command', 'ADMIN', 'Command center total phase months.', 1),
  (353, 'command.phase.current.focus', '重心:拉新 + 首购转化,放宽试用,谨慎放大资金流出', 'STRING', 'command', 'ADMIN', 'Command center current phase focus.', 1),
  (354, 'command.phase.dial.acq', '高', 'STRING', 'command', 'ADMIN', 'Command center acquisition budget dial.', 1),
  (355, 'command.phase.dial.trial', '放宽', 'STRING', 'command', 'ADMIN', 'Command center trial quota dial.', 1),
  (356, 'command.phase.dial.firstbuy', '中高', 'STRING', 'command', 'ADMIN', 'Command center first-purchase incentive dial.', 1),
  (357, 'command.phase.dial.outflow', '谨慎', 'STRING', 'command', 'ADMIN', 'Command center outflow expansion dial.', 1),
  (358, 'command.phase.dial.wd_cap', '标准档', 'STRING', 'command', 'ADMIN', 'Command center withdrawal daily cap dial.', 1),
  (359, 'command.phase.dial.apy', '标准', 'STRING', 'command', 'ADMIN', 'Command center staking APY dial.', 1),
  (360, 'command.phase.dial.decay', 'P3 档', 'STRING', 'command', 'ADMIN', 'Command center device decay dial.', 1),
  (361, 'command.phase.dial.genesis', '渐进', 'STRING', 'command', 'ADMIN', 'Command center Genesis release dial.', 1),
  (362, 'command.funnel.register.count', '1240', 'NUMBER', 'command', 'ADMIN', 'Command center funnel registered count.', 1),
  (363, 'command.funnel.register.prev', '1180', 'NUMBER', 'command', 'ADMIN', 'Command center funnel registered previous count.', 1),
  (364, 'command.funnel.kyc.count', '769', 'NUMBER', 'command', 'ADMIN', 'Command center funnel KYC count.', 1),
  (365, 'command.funnel.kyc.prev', '742', 'NUMBER', 'command', 'ADMIN', 'Command center funnel KYC previous count.', 1),
  (366, 'command.funnel.first_buy.count', '223', 'NUMBER', 'command', 'ADMIN', 'Command center funnel first-buy count.', 1),
  (367, 'command.funnel.first_buy.prev', '240', 'NUMBER', 'command', 'ADMIN', 'Command center funnel first-buy previous count.', 1),
  (368, 'command.funnel.repurchase.count', '78', 'NUMBER', 'command', 'ADMIN', 'Command center funnel repurchase count.', 1),
  (369, 'command.funnel.repurchase.prev', '71', 'NUMBER', 'command', 'ADMIN', 'Command center funnel repurchase previous count.', 1),
  (370, 'command.funnel.withdraw.count', '41', 'NUMBER', 'command', 'ADMIN', 'Command center funnel withdrawal count.', 1),
  (371, 'command.funnel.withdraw.prev', '38', 'NUMBER', 'command', 'ADMIN', 'Command center funnel withdrawal previous count.', 1),
  (372, 'command.kpi.day0.value', '87', 'NUMBER', 'command', 'ADMIN', 'Command center KPI Day-0 onboarding value.', 1),
  (373, 'command.kpi.day7.value', '58', 'NUMBER', 'command', 'ADMIN', 'Command center KPI Day-7 retention value.', 1),
  (374, 'command.kpi.l2l3.value', '18', 'NUMBER', 'command', 'ADMIN', 'Command center KPI L2 to L3 first-buy conversion value.', 1),
  (375, 'command.kpi.l3l4.value', '35', 'NUMBER', 'command', 'ADMIN', 'Command center KPI L3 to L4 repurchase value.', 1),
  (376, 'command.kpi.l4l5.value', '22', 'NUMBER', 'command', 'ADMIN', 'Command center KPI L4 to L5 referral value.', 1),
  (377, 'command.kpi.nova.value', '31', 'NUMBER', 'command', 'ADMIN', 'Command center KPI Nova push CTR value.', 1),
  (378, 'command.kpi.tvl.value', '1.64', 'NUMBER', 'command', 'ADMIN', 'Command center KPI staking TVL value in million USD.', 1),
  (379, 'command.kpi.genesis.value', '84', 'NUMBER', 'command', 'ADMIN', 'Command center KPI Genesis sellout value.', 1),
  (380, 'command.maturity.d1.withdraw', '62000', 'NUMBER', 'command', 'ADMIN', 'Command center maturity day 1 withdrawal amount.', 1),
  (381, 'command.maturity.d1.interest', '18000', 'NUMBER', 'command', 'ADMIN', 'Command center maturity day 1 interest amount.', 1),
  (382, 'command.maturity.d1.genesis', '9000', 'NUMBER', 'command', 'ADMIN', 'Command center maturity day 1 Genesis amount.', 1),
  (383, 'command.maturity.d2.withdraw', '48000', 'NUMBER', 'command', 'ADMIN', 'Command center maturity day 2 withdrawal amount.', 1),
  (384, 'command.maturity.d2.interest', '18000', 'NUMBER', 'command', 'ADMIN', 'Command center maturity day 2 interest amount.', 1),
  (385, 'command.maturity.d2.genesis', '9000', 'NUMBER', 'command', 'ADMIN', 'Command center maturity day 2 Genesis amount.', 1),
  (386, 'command.maturity.d3.withdraw', '71000', 'NUMBER', 'command', 'ADMIN', 'Command center maturity day 3 withdrawal amount.', 1),
  (387, 'command.maturity.d3.interest', '22000', 'NUMBER', 'command', 'ADMIN', 'Command center maturity day 3 interest amount.', 1),
  (388, 'command.maturity.d3.genesis', '9000', 'NUMBER', 'command', 'ADMIN', 'Command center maturity day 3 Genesis amount.', 1),
  (389, 'command.maturity.d4.withdraw', '55000', 'NUMBER', 'command', 'ADMIN', 'Command center maturity day 4 withdrawal amount.', 1),
  (390, 'command.maturity.d4.interest', '18000', 'NUMBER', 'command', 'ADMIN', 'Command center maturity day 4 interest amount.', 1),
  (391, 'command.maturity.d4.genesis', '9000', 'NUMBER', 'command', 'ADMIN', 'Command center maturity day 4 Genesis amount.', 1),
  (392, 'command.maturity.d5.withdraw', '88000', 'NUMBER', 'command', 'ADMIN', 'Command center maturity day 5 withdrawal amount.', 1),
  (393, 'command.maturity.d5.interest', '26000', 'NUMBER', 'command', 'ADMIN', 'Command center maturity day 5 interest amount.', 1),
  (394, 'command.maturity.d5.genesis', '9000', 'NUMBER', 'command', 'ADMIN', 'Command center maturity day 5 Genesis amount.', 1),
  (395, 'command.maturity.d6.withdraw', '40000', 'NUMBER', 'command', 'ADMIN', 'Command center maturity day 6 withdrawal amount.', 1),
  (396, 'command.maturity.d6.interest', '16000', 'NUMBER', 'command', 'ADMIN', 'Command center maturity day 6 interest amount.', 1),
  (397, 'command.maturity.d6.genesis', '9000', 'NUMBER', 'command', 'ADMIN', 'Command center maturity day 6 Genesis amount.', 1),
  (398, 'command.maturity.d7.withdraw', '52000', 'NUMBER', 'command', 'ADMIN', 'Command center maturity day 7 withdrawal amount.', 1),
  (399, 'command.maturity.d7.interest', '19000', 'NUMBER', 'command', 'ADMIN', 'Command center maturity day 7 interest amount.', 1),
  (400, 'command.maturity.d7.genesis', '9000', 'NUMBER', 'command', 'ADMIN', 'Command center maturity day 7 Genesis amount.', 1),
  (401, 'command.approval.bigout.detail', '2 单 · 合计 $52.3K', 'STRING', 'command', 'ADMIN', 'Command center big-out approval detail.', 1),
  (402, 'command.approval.param.detail', '日限上调申请 · 财务发起', 'STRING', 'command', 'ADMIN', 'Command center parameter approval detail.', 1),
  (403, 'command.approval.genesis.detail', '需覆盖率核验通过', 'STRING', 'command', 'ADMIN', 'Command center Genesis approval detail.', 1),
  (404, 'command.withdrawal.high_amount_threshold', '5000', 'NUMBER', 'command', 'ADMIN', 'Command center high-amount withdrawal alert threshold.', 1),
  (405, 'command.risk.flagged_accounts', '14', 'NUMBER', 'command', 'ADMIN', 'Command center K-domain flagged accounts count.', 1),
  (406, 'command.alert.multi_account', '多账户关联风险待合规核查', 'STRING', 'command', 'ADMIN', 'Command center multi-account alert text.', 1),
  (407, 'command.domain.A', '操作员 12 · 今日审计 86', 'STRING', 'command', 'ADMIN', 'Command center domain A pulse.', 1),
  (408, 'command.domain.C', '活跃用户 28.4K · 高风险 2', 'STRING', 'command', 'ADMIN', 'Command center domain C pulse.', 1),
  (409, 'command.domain.E', '在售 SKU 6 · 库存正常', 'STRING', 'command', 'ADMIN', 'Command center domain E pulse.', 1),
  (410, 'command.domain.F', '佣金待结 $18.2K', 'STRING', 'command', 'ADMIN', 'Command center domain F pulse.', 1),
  (411, 'command.domain.G', 'Staking TVL $1.64M · APY 正常', 'STRING', 'command', 'ADMIN', 'Command center domain G pulse.', 1),
  (412, 'command.domain.H', 'P3 扩张期 · 第 7/12 月', 'STRING', 'command', 'ADMIN', 'Command center domain H pulse.', 1),
  (413, 'command.domain.I', '推送 CTR 31% · 文案 A/B 2 组', 'STRING', 'command', 'ADMIN', 'Command center domain I pulse.', 1),
  (414, 'command.domain.K', '风险命中 14 · 待复核 5', 'STRING', 'command', 'ADMIN', 'Command center domain K pulse.', 1),
  (261, 'onboarding.day0.first_receipt_target_seconds', '90', 'NUMBER', 'onboarding', 'PUBLIC', 'Launch / Intro first receipt target in seconds.', 1),
  (262, 'onboarding.day0.first_receipt_usdt', '0.000300', 'NUMBER', 'onboarding', 'PUBLIC', 'Day-One first receipt display amount.', 1),
  (263, 'onboarding.day0.welcome_bonus_asset', 'USDT', 'STRING', 'onboarding', 'PUBLIC', 'Launch / Intro welcome bonus asset.', 1),
  (264, 'onboarding.day0.welcome_bonus_amount', '5.000000', 'NUMBER', 'onboarding', 'PUBLIC', 'Launch / Intro welcome bonus amount.', 1),
  (265, 'onboarding.day0.active_device_count', '28432', 'NUMBER', 'onboarding', 'PUBLIC', 'Intro / Home / Globe public active node count.', 1),
  (266, 'onboarding.day0.paid_today_usdt', '1247893', 'NUMBER', 'onboarding', 'PUBLIC', 'Intro / Home / Globe public paid-today USDT amount.', 1),
  (267, 'onboarding.day0.intro_refresh_seconds', '60', 'NUMBER', 'onboarding', 'PUBLIC', 'Intro public metric polling interval in seconds.', 1),
  (268, 'compliance.kyc.review_sla_seconds', '90', 'NUMBER', 'compliance', 'PUBLIC', 'KYC Express review SLA seconds shown in uniapp.', 1),
  (670, 'compliance.trust.tvl_usd', '847300000', 'NUMBER', 'compliance', 'PUBLIC', 'Trust Center TVL metric shown in uniapp.', 1),
  (671, 'compliance.trust.active_nodes', '28432', 'NUMBER', 'compliance', 'PUBLIC', 'Trust Center active nodes metric shown in uniapp.', 1),
  (672, 'compliance.trust.active_ai_clients', '1247', 'NUMBER', 'compliance', 'PUBLIC', 'Trust Center active AI client count shown in uniapp.', 1),
  (673, 'compliance.trust.mrr_usd', '4870000', 'NUMBER', 'compliance', 'PUBLIC', 'Trust Center quarterly report MRR in USD.', 1),
  (785, 'compliance.trust.mrr_growth_pct', '22', 'NUMBER', 'compliance', 'PUBLIC', 'Trust Center quarterly report MRR growth percentage.', 1),
  (674, 'compliance.trust.active_accounts', '184206', 'NUMBER', 'compliance', 'PUBLIC', 'Trust Center quarterly report active account count.', 1),
  (786, 'compliance.trust.active_accounts_growth_pct', '38', 'NUMBER', 'compliance', 'PUBLIC', 'Trust Center quarterly report active account growth percentage.', 1),
  (675, 'compliance.trust.devices_online', '28432', 'NUMBER', 'compliance', 'PUBLIC', 'Trust Center quarterly report online device count.', 1),
  (787, 'compliance.trust.devices_online_growth_pct', '12', 'NUMBER', 'compliance', 'PUBLIC', 'Trust Center quarterly report online device growth percentage.', 1),
  (676, 'compliance.trust.payouts_processed_usd', '31200000', 'NUMBER', 'compliance', 'PUBLIC', 'Trust Center quarterly report processed payout amount in USD.', 1),
  (788, 'compliance.trust.payouts_growth_pct', '27', 'NUMBER', 'compliance', 'PUBLIC', 'Trust Center quarterly report processed payout growth percentage.', 1),
  (677, 'compliance.trust.audit_period', 'Quarterly', 'STRING', 'compliance', 'PUBLIC', 'Trust Center audit cadence label.', 1),
  (678, 'compliance.trust.reserve_period', 'Monthly', 'STRING', 'compliance', 'PUBLIC', 'Trust Center reserve proof cadence label.', 1),
  (679, 'compliance.trust.bounty_min_usd', '500', 'NUMBER', 'compliance', 'PUBLIC', 'Trust Center bug bounty minimum USD.', 1),
  (680, 'compliance.trust.bounty_max_usd', '25000', 'NUMBER', 'compliance', 'PUBLIC', 'Trust Center bug bounty maximum USD.', 1),
  (681, 'compliance.trust.bounty_sla_days', '7', 'NUMBER', 'compliance', 'PUBLIC', 'Trust Center bug bounty review SLA days.', 1),
  (17, 'feature.trial.enabled', 'false', 'BOOLEAN', 'feature', 'PUBLIC', 'Free trial feature launch switch.', 1),
  (18, 'feature.nex_swap.enabled', 'false', 'BOOLEAN', 'feature', 'PUBLIC', 'NEX swap feature launch switch.', 1),
  (20, 'feature.nex_lock.enabled', 'false', 'BOOLEAN', 'feature', 'PUBLIC', 'NEX lock feature launch switch.', 1),
  (21, 'compute.active_device_slots.default', '6', 'NUMBER', 'compute', 'PUBLIC', 'Default max active compute devices per user.', 1),
  (60, 'platform.phase.config', '{"currentPhase":"P1","label":"月 1 · 极速拉新","phases":[{"id":"P1","name":"P1 极速拉新","monthsFrom":0,"monthsTo":1,"eta":"Q1","progressCurrent":1,"progressTotal":1,"progressPct":100,"queue":0},{"id":"P2","name":"P2 留存爬坡","monthsFrom":2,"monthsTo":3,"eta":"Q2","progressCurrent":1,"progressTotal":2,"progressPct":50,"queue":0},{"id":"P3","name":"P3 升级窗口","monthsFrom":4,"monthsTo":6,"eta":"Q3","progressCurrent":1,"progressTotal":3,"progressPct":33,"queue":1842},{"id":"P4","name":"P4 订阅化","monthsFrom":7,"monthsTo":8,"eta":"Q4","progressCurrent":1,"progressTotal":2,"progressPct":50,"queue":0},{"id":"P5","name":"P5 沉淀加深","monthsFrom":9,"monthsTo":10,"eta":"Q1 next year","progressCurrent":1,"progressTotal":5,"progressPct":20,"queue":614},{"id":"P6","name":"P6 软退场","monthsFrom":11,"monthsTo":12,"eta":"Q2 next year","progressCurrent":1,"progressTotal":6,"progressPct":16,"queue":0}],"dials":[{"key":"withdrawCooldownDays","name":"提现冷却(天)","value":"7","unit":"d","trend":"↑"},{"key":"withdrawDailyCapUSD","name":"提现日限","value":"$2,000","unit":"USD","trend":"—"},{"key":"withdrawPointsRatio","name":"提现积分比","value":"30%","unit":"pct","trend":"↑"},{"key":"binaryDailyCapUSD","name":"双轨日封顶","value":"$1,500","unit":"USD","trend":"—"},{"key":"stakingApyBoost","name":"Staking APY 加成","value":"1.0×","unit":"x","trend":"—"},{"key":"novaCadenceMult","name":"Nova 节奏乘数","value":"1.2×","unit":"x","trend":"↑"},{"key":"questRewardMult","name":"Quest 奖励乘数","value":"1.5×","unit":"x","trend":"↑"},{"key":"trialOffsetCapUSD","name":"试用抵扣上限","value":"$120","unit":"USD","trend":"—"},{"key":"storeDiscountLadder","name":"商城折扣 ladder","value":"T2","unit":"tier","trend":"—"},{"key":"genesisDividendRate","name":"Genesis 日分红率","value":"0.1%","unit":"pct","trend":"—"}]}', 'JSON', 'platform', 'PUBLIC', 'H1 platform phase config used by generation gates and Growth H1.', 1),
  (160, 'growth.trial.enabled', 'true', 'BOOLEAN', 'growth', 'PUBLIC', 'Whether free trial entry cards and the trial page are visible in uniapp.', 1),
  (161, 'growth.trial.device_name', 'NexionBox S1', 'STRING', 'growth', 'PUBLIC', 'Trial device name shown in uniapp trial cards.', 1),
  (162, 'growth.trial.duration_days', '3', 'NUMBER', 'growth', 'PUBLIC', 'Trial duration in days shown on trial cards and detail page.', 1),
  (163, 'growth.trial.daily_usdt', '38.50', 'NUMBER', 'growth', 'PUBLIC', 'Estimated daily shadow USDT during free trial.', 1),
  (164, 'growth.trial.daily_nex', '65', 'NUMBER', 'growth', 'PUBLIC', 'Estimated daily NEX during free trial.', 1),
  (165, 'growth.trial.seats_left_today', '47', 'NUMBER', 'growth', 'PUBLIC', 'Remaining trial quota shown today.', 1),
  (166, 'growth.trial.offset_cap_usdt', '50', 'NUMBER', 'growth', 'PUBLIC', 'Maximum trial shadow USDT that can offset hardware purchase price.', 1),
  (167, 'growth.trial.price_usdt', '1299', 'NUMBER', 'growth', 'PUBLIC', 'Reference purchase price for the trial device.', 1),
  (168, 'growth.trial.claim_enabled', 'false', 'BOOLEAN', 'growth', 'PUBLIC', 'Whether the trial claim action is enabled for app users.', 1),
  (169, 'growth.earn.device_rank_limit', '5', 'NUMBER', 'growth', 'PUBLIC', 'Maximum devices shown in the uniapp Earn ranking.', 1),
  (170, 'growth.earn.task_teaser_limit', '3', 'NUMBER', 'growth', 'PUBLIC', 'Maximum mission teaser rows shown in the uniapp Earn task center.', 1),
  (171, 'growth.earn.locked_task_limit', '3', 'NUMBER', 'growth', 'PUBLIC', 'Maximum locked mission rows shown in the uniapp Earn device card.', 1),
  (172, 'growth.earn.jobs_live_global', '8432', 'NUMBER', 'growth', 'PUBLIC', 'Global live job count shown in the uniapp Earn task center.', 1),
  (173, 'growth.earn.phone_battery_pct', '78', 'NUMBER', 'growth', 'PUBLIC', 'Fallback phone battery percentage shown before runtime telemetry is connected.', 1),
  (174, 'growth.earn.max_device_slots', '6', 'NUMBER', 'growth', 'PUBLIC', 'Maximum device slots shown on the uniapp Earn fleet card.', 1),
  (175, 'growth.earn.network_avg_daily_usdt', '143', 'NUMBER', 'growth', 'PUBLIC', 'Network average daily USDT shown on the uniapp Earn fleet card.', 1),
  (176, 'growth.earn.completed_rows_limit', '3', 'NUMBER', 'growth', 'PUBLIC', 'Maximum completed earning rows shown in the uniapp Earn task center.', 1),
  (244, 'growth.earn.production_share_pct', '76', 'NUMBER', 'growth', 'PUBLIC', 'Production compute share percentage used by the uniapp Earn breakdown.', 1),
  (245, 'growth.earn.ai_premium_share_pct', '24', 'NUMBER', 'growth', 'PUBLIC', 'AI Premium share percentage used by the uniapp Earn breakdown.', 1),
  (444, 'growth.earn.phone_daily_usdt', '0.06', 'NUMBER', 'growth', 'PUBLIC', 'Phone NPU daily USDT baseline used by onboarding estimator and connect calibration.', 1),
  (445, 'growth.earn.phone_npu_tops', '28', 'NUMBER', 'growth', 'PUBLIC', 'Phone NPU TOPS fallback shown by onboarding estimator and connect calibration.', 1),
  (446, 'growth.earn.phone_network_ping_ms', '62', 'NUMBER', 'growth', 'PUBLIC', 'Phone gateway ping fallback in milliseconds for onboarding connect calibration.', 1),
  (447, 'growth.earn.gateway_sg_ping_ms', '38', 'NUMBER', 'growth', 'PUBLIC', 'Singapore gateway ping shown by onboarding connect calibration.', 1),
  (448, 'growth.earn.gateway_tokyo_ping_ms', '42', 'NUMBER', 'growth', 'PUBLIC', 'Tokyo gateway ping shown by onboarding connect calibration.', 1),
  (449, 'growth.earn.gateway_us_west_ping_ms', '156', 'NUMBER', 'growth', 'PUBLIC', 'US West gateway ping shown by onboarding connect calibration.', 1),
  (450, 'growth.earn.estimator_detection_delay_ms', '700', 'NUMBER', 'growth', 'PUBLIC', 'Estimator detection animation duration in milliseconds.', 1),
  (451, 'growth.earn.calibration_duration_ms', '12000', 'NUMBER', 'growth', 'PUBLIC', 'Connect calibration animation duration in milliseconds.', 1),
  (452, 'growth.earn.calibration_base_score', '58', 'NUMBER', 'growth', 'PUBLIC', 'Base score for onboarding connect calibration.', 1),
  (453, 'growth.earn.calibration_active_device_score', '12', 'NUMBER', 'growth', 'PUBLIC', 'Score added per active device during onboarding connect calibration.', 1),
  (454, 'growth.earn.calibration_wallet_paired_score', '16', 'NUMBER', 'growth', 'PUBLIC', 'Score added when wallet is paired during onboarding connect calibration.', 1),
  (455, 'growth.earn.calibration_wallet_balance_score', '8', 'NUMBER', 'growth', 'PUBLIC', 'Score added when wallet has available USDT during onboarding connect calibration.', 1),
  (456, 'growth.earn.calibration_tier2_threshold', '75', 'NUMBER', 'growth', 'PUBLIC', 'Tier 2 score threshold for onboarding connect calibration.', 1),
  (457, 'growth.earn.calibration_tier3_threshold', '90', 'NUMBER', 'growth', 'PUBLIC', 'Tier 3 score threshold for onboarding connect calibration.', 1),
  (458, 'growth.globe.active_jobs_fallback', '8432', 'NUMBER', 'growth', 'PUBLIC', 'Globe active job fallback used when compute node-map has no busy task count.', 1),
  (459, 'growth.globe.jobs_per_node_min', '2', 'NUMBER', 'growth', 'PUBLIC', 'Minimum jobs per node used by the uniapp Globe regional task estimate.', 1),
  (460, 'growth.globe.paid_today_job_divisor', '18', 'NUMBER', 'growth', 'PUBLIC', 'Day-One paid-today divisor used to derive Globe active jobs when needed.', 1),
  (461, 'growth.globe.price_row_job_fallback', '1000', 'NUMBER', 'growth', 'PUBLIC', 'Fallback active job count per price-index row for the uniapp Globe page.', 1),
  (462, 'growth.globe.region.ap.name_zh', '亚太节点池', 'STRING', 'growth', 'PUBLIC', 'Globe AP region Chinese display name.', 1),
  (463, 'growth.globe.region.ap.name_en', 'Asia Pacific', 'STRING', 'growth', 'PUBLIC', 'Globe AP region English display name.', 1),
  (464, 'growth.globe.region.ap.cx_pct', '78', 'NUMBER', 'growth', 'PUBLIC', 'Globe AP region horizontal map position percentage.', 1),
  (465, 'growth.globe.region.ap.cy_pct', '52', 'NUMBER', 'growth', 'PUBLIC', 'Globe AP region vertical map position percentage.', 1),
  (466, 'growth.globe.region.ap.share_pct', '44', 'NUMBER', 'growth', 'PUBLIC', 'Globe AP fallback device and job share percentage.', 1),
  (467, 'growth.globe.region.ap.latency_ms', '24', 'NUMBER', 'growth', 'PUBLIC', 'Globe AP average latency display in milliseconds.', 1),
  (468, 'growth.globe.region.eu.name_zh', '欧洲节点池', 'STRING', 'growth', 'PUBLIC', 'Globe EU region Chinese display name.', 1),
  (469, 'growth.globe.region.eu.name_en', 'Europe', 'STRING', 'growth', 'PUBLIC', 'Globe EU region English display name.', 1),
  (470, 'growth.globe.region.eu.cx_pct', '50', 'NUMBER', 'growth', 'PUBLIC', 'Globe EU region horizontal map position percentage.', 1),
  (471, 'growth.globe.region.eu.cy_pct', '38', 'NUMBER', 'growth', 'PUBLIC', 'Globe EU region vertical map position percentage.', 1),
  (472, 'growth.globe.region.eu.share_pct', '24', 'NUMBER', 'growth', 'PUBLIC', 'Globe EU fallback device and job share percentage.', 1),
  (473, 'growth.globe.region.eu.latency_ms', '38', 'NUMBER', 'growth', 'PUBLIC', 'Globe EU average latency display in milliseconds.', 1),
  (474, 'growth.globe.region.na.name_zh', '北美节点池', 'STRING', 'growth', 'PUBLIC', 'Globe NA region Chinese display name.', 1),
  (475, 'growth.globe.region.na.name_en', 'North America', 'STRING', 'growth', 'PUBLIC', 'Globe NA region English display name.', 1),
  (476, 'growth.globe.region.na.cx_pct', '22', 'NUMBER', 'growth', 'PUBLIC', 'Globe NA region horizontal map position percentage.', 1),
  (477, 'growth.globe.region.na.cy_pct', '42', 'NUMBER', 'growth', 'PUBLIC', 'Globe NA region vertical map position percentage.', 1),
  (478, 'growth.globe.region.na.share_pct', '21', 'NUMBER', 'growth', 'PUBLIC', 'Globe NA fallback device and job share percentage.', 1),
  (479, 'growth.globe.region.na.latency_ms', '31', 'NUMBER', 'growth', 'PUBLIC', 'Globe NA average latency display in milliseconds.', 1),
  (480, 'growth.globe.region.me.name_zh', '中东节点池', 'STRING', 'growth', 'PUBLIC', 'Globe ME region Chinese display name.', 1),
  (481, 'growth.globe.region.me.name_en', 'Middle East', 'STRING', 'growth', 'PUBLIC', 'Globe ME region English display name.', 1),
  (482, 'growth.globe.region.me.cx_pct', '60', 'NUMBER', 'growth', 'PUBLIC', 'Globe ME region horizontal map position percentage.', 1),
  (483, 'growth.globe.region.me.cy_pct', '52', 'NUMBER', 'growth', 'PUBLIC', 'Globe ME region vertical map position percentage.', 1),
  (484, 'growth.globe.region.me.share_pct', '5', 'NUMBER', 'growth', 'PUBLIC', 'Globe ME fallback device and job share percentage.', 1),
  (485, 'growth.globe.region.me.latency_ms', '45', 'NUMBER', 'growth', 'PUBLIC', 'Globe ME average latency display in milliseconds.', 1),
  (486, 'growth.globe.region.sa.name_zh', '南美节点池', 'STRING', 'growth', 'PUBLIC', 'Globe SA region Chinese display name.', 1),
  (487, 'growth.globe.region.sa.name_en', 'South America', 'STRING', 'growth', 'PUBLIC', 'Globe SA region English display name.', 1),
  (488, 'growth.globe.region.sa.cx_pct', '30', 'NUMBER', 'growth', 'PUBLIC', 'Globe SA region horizontal map position percentage.', 1),
  (489, 'growth.globe.region.sa.cy_pct', '72', 'NUMBER', 'growth', 'PUBLIC', 'Globe SA region vertical map position percentage.', 1),
  (490, 'growth.globe.region.sa.share_pct', '4', 'NUMBER', 'growth', 'PUBLIC', 'Globe SA fallback device and job share percentage.', 1),
  (491, 'growth.globe.region.sa.latency_ms', '78', 'NUMBER', 'growth', 'PUBLIC', 'Globe SA average latency display in milliseconds.', 1),
  (492, 'growth.globe.region.af.name_zh', '非洲节点池', 'STRING', 'growth', 'PUBLIC', 'Globe AF region Chinese display name.', 1),
  (493, 'growth.globe.region.af.name_en', 'Africa', 'STRING', 'growth', 'PUBLIC', 'Globe AF region English display name.', 1),
  (494, 'growth.globe.region.af.cx_pct', '54', 'NUMBER', 'growth', 'PUBLIC', 'Globe AF region horizontal map position percentage.', 1),
  (495, 'growth.globe.region.af.cy_pct', '66', 'NUMBER', 'growth', 'PUBLIC', 'Globe AF region vertical map position percentage.', 1),
  (496, 'growth.globe.region.af.share_pct', '2', 'NUMBER', 'growth', 'PUBLIC', 'Globe AF fallback device and job share percentage.', 1),
  (497, 'growth.globe.region.af.latency_ms', '92', 'NUMBER', 'growth', 'PUBLIC', 'Globe AF average latency display in milliseconds.', 1),
  (177, 'growth.home.phone_daily_usdt', '0.06', 'NUMBER', 'growth', 'PUBLIC', 'Phone daily USDT baseline shown on the uniapp Home page.', 1),
  (178, 'growth.home.peer_avg_daily_usdt', '38.50', 'NUMBER', 'growth', 'PUBLIC', 'NexionBox S1 peer average daily USDT shown on the uniapp Home page.', 1),
  (179, 'growth.home.quick_stake_apy_pct', '180', 'NUMBER', 'growth', 'PUBLIC', 'Quick staking APY percentage shown on the uniapp Home page.', 1),
  (180, 'growth.home.weekly_multiplier', '1.5', 'NUMBER', 'growth', 'PUBLIC', 'Weekly quest multiplier shown on the uniapp Home page.', 1),
  (181, 'growth.home.weekly_reward_nex', '1200', 'NUMBER', 'growth', 'PUBLIC', 'Weekly quest NEX reward shown on the uniapp Home page.', 1),
  (182, 'growth.home.weekly_ends_label', '4d 11h', 'STRING', 'growth', 'PUBLIC', 'Weekly quest countdown label shown on the uniapp Home page.', 1),
  (183, 'growth.home.phone_task_progress_pct', '28', 'NUMBER', 'growth', 'PUBLIC', 'Phone task progress percentage shown on the uniapp Home page.', 1),
  (184, 'growth.home.phone_task_left_seconds', '28', 'NUMBER', 'growth', 'PUBLIC', 'Phone task remaining seconds shown on the uniapp Home page.', 1),
  (246, 'growth.home.day_task_left_seconds', '66240', 'NUMBER', 'growth', 'PUBLIC', 'First-day task countdown seconds shown on the uniapp Home page.', 1),
  (185, 'growth.home.grid_online_count', '28432', 'NUMBER', 'growth', 'PUBLIC', 'On-grid online count shown on the uniapp Home page.', 1),
  (186, 'growth.home.grid_earn_per_sec_usdt', '215', 'NUMBER', 'growth', 'PUBLIC', 'Network earning per second shown on the uniapp Home page.', 1),
  (187, 'growth.home.rank_progress_pct', '42', 'NUMBER', 'growth', 'PUBLIC', 'V-rank progress percentage shown on the uniapp Home page.', 1),
  (188, 'growth.home.leadership_pool_usdt', '42700', 'NUMBER', 'growth', 'PUBLIC', 'Leadership pool USDT amount shown on the uniapp Home page.', 1),
  (189, 'growth.home.leadership_pool_ends_label', '5d', 'STRING', 'growth', 'PUBLIC', 'Leadership pool countdown label shown on the uniapp Home page.', 1),
  (190, 'growth.home.trust_chips', 'NVIDIA,Intel,AMD,CertiK verified,SOC 2,GDPR,ISO 27001', 'STRING', 'growth', 'PUBLIC', 'Comma-separated trust chips shown on the uniapp Home page.', 1),
  (191, 'growth.home.phone_fleet_today_usdt', '0.040', 'NUMBER', 'growth', 'PUBLIC', 'Phone device today USDT amount shown in the uniapp Home fleet card.', 1),
  (192, 'growth.home.phone_task_delta_usdt', '0.00032', 'NUMBER', 'growth', 'PUBLIC', 'Small current task earning increment shown in the uniapp Home phone task card.', 1),
  (193, 'growth.home.phone_task_streak_days', '7', 'NUMBER', 'growth', 'PUBLIC', 'Phone task streak days shown in the uniapp Home phone task card.', 1),
  (194, 'growth.home.math_target_phone_days', '27', 'NUMBER', 'growth', 'PUBLIC', 'Phone-day target used by the uniapp Home Do the Math comparison.', 1),
  (220, 'growth.home.grid_client_1_name', 'Pocket Studios', 'STRING', 'growth', 'PUBLIC', 'Home On-grid client row 1 customer name.', 1),
  (221, 'growth.home.grid_client_1_model', 'SDXL Turbo', 'STRING', 'growth', 'PUBLIC', 'Home On-grid client row 1 model or workload.', 1),
  (222, 'growth.home.grid_client_1_city', 'Berlin', 'STRING', 'growth', 'PUBLIC', 'Home On-grid client row 1 city label.', 1),
  (223, 'growth.home.grid_client_1_gpus', '30 GPUs', 'STRING', 'growth', 'PUBLIC', 'Home On-grid client row 1 GPU count label.', 1),
  (224, 'growth.home.grid_client_2_name', 'Helix Labs', 'STRING', 'growth', 'PUBLIC', 'Home On-grid client row 2 customer name.', 1),
  (225, 'growth.home.grid_client_2_model', 'Llama 3.2 3B', 'STRING', 'growth', 'PUBLIC', 'Home On-grid client row 2 model or workload.', 1),
  (226, 'growth.home.grid_client_2_city', 'SF', 'STRING', 'growth', 'PUBLIC', 'Home On-grid client row 2 city label.', 1),
  (227, 'growth.home.grid_client_2_gpus', '57 GPUs', 'STRING', 'growth', 'PUBLIC', 'Home On-grid client row 2 GPU count label.', 1),
  (228, 'growth.home.grid_client_3_name', 'Echo Earbuds', 'STRING', 'growth', 'PUBLIC', 'Home On-grid client row 3 customer name.', 1),
  (229, 'growth.home.grid_client_3_model', 'Whisper tiny', 'STRING', 'growth', 'PUBLIC', 'Home On-grid client row 3 model or workload.', 1),
  (230, 'growth.home.grid_client_3_city', 'Tokyo', 'STRING', 'growth', 'PUBLIC', 'Home On-grid client row 3 city label.', 1),
  (231, 'growth.home.grid_client_3_gpus', '84 GPUs', 'STRING', 'growth', 'PUBLIC', 'Home On-grid client row 3 GPU count label.', 1),
  (240, 'growth.goals.fallback_target_points', '100', 'NUMBER', 'growth', 'PUBLIC', 'Fallback target points used by uniapp Goals when no monthly or event targets are configured.', 1),
  (780, 'growth.goals.default_target_usdt', '1000', 'NUMBER', 'growth', 'PUBLIC', 'Default USDT target prefilled by uniapp Goals earning-goal form.', 1),
  (781, 'growth.goals.min_target_usdt', '100', 'NUMBER', 'growth', 'PUBLIC', 'Minimum USDT target allowed by uniapp Goals earning-goal form.', 1),
  (782, 'growth.goals.target_presets_usdt', '500,1000,5000,10000', 'STRING', 'growth', 'PUBLIC', 'Comma-separated USDT target presets shown by uniapp Goals.', 1),
  (783, 'growth.goals.default_deadline_days', '90', 'NUMBER', 'growth', 'PUBLIC', 'Default deadline in days prefilled by uniapp Goals earning-goal form.', 1),
  (784, 'growth.goals.deadline_presets_days', '30,90,180,365', 'STRING', 'growth', 'PUBLIC', 'Comma-separated deadline day presets shown by uniapp Goals.', 1),
  (241, 'growth.goals.hero_copy', 'Your goal progress is calculated from mission points, daily streak, monthly challenges, and event quests configured in PC Growth.', 'STRING', 'growth', 'PUBLIC', 'Goals page hero copy shown in uniapp.', 1),
  (242, 'growth.goals.empty_recommendation', 'All configured goals are clear. Watch PC Growth for new campaigns.', 'STRING', 'growth', 'PUBLIC', 'Goals recommended path when all configured goals are clear.', 1),
  (243, 'growth.goals.pc_source_label', 'PC Growth', 'STRING', 'growth', 'PUBLIC', 'Goals empty-state backend source label.', 1),
  (560, 'growth.goals.title', 'Goals', 'STRING', 'growth', 'PUBLIC', 'Goals page title shown in uniapp.', 1),
  (561, 'growth.goals.eyebrow', 'Goal cockpit', 'STRING', 'growth', 'PUBLIC', 'Goals hero eyebrow.', 1),
  (562, 'growth.goals.active_suffix', 'active', 'STRING', 'growth', 'PUBLIC', 'Goals active goal suffix.', 1),
  (563, 'growth.goals.progress_suffix', 'progress', 'STRING', 'growth', 'PUBLIC', 'Goals progress suffix.', 1),
  (564, 'growth.goals.target_suffix', 'target', 'STRING', 'growth', 'PUBLIC', 'Goals target suffix.', 1),
  (565, 'growth.goals.loading_copy', 'Loading goal data...', 'STRING', 'growth', 'PUBLIC', 'Goals loading-state copy.', 1),
  (566, 'growth.goals.error_copy', 'Goals request failed', 'STRING', 'growth', 'PUBLIC', 'Goals request error fallback copy.', 1),
  (567, 'growth.goals.total_points_label', 'Total points', 'STRING', 'growth', 'PUBLIC', 'Goals total points metric label.', 1),
  (568, 'growth.goals.mission_completion_label', 'Mission completion', 'STRING', 'growth', 'PUBLIC', 'Goals mission completion metric label.', 1),
  (569, 'growth.goals.current_streak_label', 'Current streak', 'STRING', 'growth', 'PUBLIC', 'Goals current streak metric label.', 1),
  (570, 'growth.goals.today_label', 'Today', 'STRING', 'growth', 'PUBLIC', 'Goals today metric label.', 1),
  (571, 'growth.goals.today_done_label', 'Done', 'STRING', 'growth', 'PUBLIC', 'Goals today done label.', 1),
  (572, 'growth.goals.today_open_label', 'Open', 'STRING', 'growth', 'PUBLIC', 'Goals today open label.', 1),
  (573, 'growth.goals.recommended_path_label', 'Recommended path', 'STRING', 'growth', 'PUBLIC', 'Goals recommended path section label.', 1),
  (574, 'growth.goals.daily_button_label', 'Daily', 'STRING', 'growth', 'PUBLIC', 'Goals daily navigation button label.', 1),
  (575, 'growth.goals.missions_button_label', 'Missions', 'STRING', 'growth', 'PUBLIC', 'Goals missions navigation button label.', 1),
  (576, 'growth.goals.monthly_title', 'Monthly goals', 'STRING', 'growth', 'PUBLIC', 'Goals monthly section title.', 1),
  (577, 'growth.goals.monthly_empty_copy', 'No monthly challenge configuration yet in {source}.', 'STRING', 'growth', 'PUBLIC', 'Goals monthly empty-state copy.', 1),
  (578, 'growth.goals.event_title', 'Event goals', 'STRING', 'growth', 'PUBLIC', 'Goals event section title.', 1),
  (579, 'growth.goals.event_empty_copy', 'No event quest configuration yet in {source}.', 'STRING', 'growth', 'PUBLIC', 'Goals event empty-state copy.', 1),
  (580, 'growth.goals.mission_title', 'Mission goals', 'STRING', 'growth', 'PUBLIC', 'Goals mission section title.', 1),
  (581, 'growth.goals.mission_empty_copy', 'No daily mission records yet.', 'STRING', 'growth', 'PUBLIC', 'Goals mission empty-state copy.', 1),
  (582, 'growth.goals.ledger_title', 'Recent point ledger', 'STRING', 'growth', 'PUBLIC', 'Goals point ledger section title.', 1),
  (583, 'growth.goals.ledger_empty_copy', 'No points ledger rows yet.', 'STRING', 'growth', 'PUBLIC', 'Goals point ledger empty-state copy.', 1),
  (584, 'growth.goals.checkin_recommendation', 'Check in today to keep the streak goal alive.', 'STRING', 'growth', 'PUBLIC', 'Goals recommendation when daily check-in is open.', 1),
  (585, 'growth.goals.claim_monthly_template', 'Claim {name} from monthly goals.', 'STRING', 'growth', 'PUBLIC', 'Goals monthly claim recommendation template.', 1),
  (586, 'growth.goals.claim_event_template', 'Claim {name} from event goals.', 'STRING', 'growth', 'PUBLIC', 'Goals event claim recommendation template.', 1),
  (587, 'growth.goals.finish_mission_template', 'Finish {name} for {points} points.', 'STRING', 'growth', 'PUBLIC', 'Goals mission recommendation template.', 1),
  (588, 'growth.goals.claim_label', 'Claim', 'STRING', 'growth', 'PUBLIC', 'Goals claim button label.', 1),
  (589, 'growth.goals.claiming_label', 'Claiming', 'STRING', 'growth', 'PUBLIC', 'Goals claiming button label.', 1),
  (590, 'growth.goals.claimed_toast', 'Goal claimed', 'STRING', 'growth', 'PUBLIC', 'Goals claim success toast.', 1),
  (591, 'growth.goals.claim_failed_toast', 'Claim failed', 'STRING', 'growth', 'PUBLIC', 'Goals claim failure fallback toast.', 1),
  (592, 'growth.goals.points_suffix', 'points', 'STRING', 'growth', 'PUBLIC', 'Goals points suffix.', 1),
  (593, 'growth.goals.reward_fallback', 'Reward', 'STRING', 'growth', 'PUBLIC', 'Goals reward fallback label.', 1),
  (594, 'growth.goals.status_open_label', 'OPEN', 'STRING', 'growth', 'PUBLIC', 'Goals open status fallback.', 1),
  (595, 'growth.goals.mission_type_fallback', 'MISSION', 'STRING', 'growth', 'PUBLIC', 'Goals mission type fallback.', 1),
  (596, 'growth.goals.ledger_type_fallback', 'POINTS', 'STRING', 'growth', 'PUBLIC', 'Goals ledger type fallback.', 1),
  (597, 'growth.goals.ledger_remark_fallback', 'Mission points event', 'STRING', 'growth', 'PUBLIC', 'Goals ledger remark fallback.', 1),
  (598, 'growth.goals.custom_hero_label', 'Earning target', 'STRING', 'growth', 'PUBLIC', 'Goals custom earning target hero label.', 1),
  (599, 'growth.goals.custom_hero_title', 'Set your next income goal', 'STRING', 'growth', 'PUBLIC', 'Goals custom earning target hero title.', 1),
  (600, 'growth.goals.custom_hero_subtitle', 'Lifetime earnings are {current}. Build a target and deadline from live earnings data.', 'STRING', 'growth', 'PUBLIC', 'Goals custom earning target subtitle.', 1),
  (601, 'growth.goals.target_input_label', 'Target amount', 'STRING', 'growth', 'PUBLIC', 'Goals target amount input label.', 1),
  (602, 'growth.goals.deadline_input_label', 'Deadline', 'STRING', 'growth', 'PUBLIC', 'Goals deadline input label.', 1),
  (603, 'growth.goals.recommendation_header', 'Recommended earning path', 'STRING', 'growth', 'PUBLIC', 'Goals recommendation section header.', 1),
  (604, 'growth.goals.recommendation_template', 'To reach {target} in {days}d, aim for {tier} at about {perDay}/day.', 'STRING', 'growth', 'PUBLIC', 'Goals recommendation template.', 1),
  (605, 'growth.goals.shop_cta_label', 'Shop compute', 'STRING', 'growth', 'PUBLIC', 'Goals fixed shop CTA label.', 1),
  (606, 'growth.goals.save_goal_label', 'Save goal', 'STRING', 'growth', 'PUBLIC', 'Goals save earning goal button label.', 1),
  (607, 'growth.goals.saving_goal_label', 'Saving', 'STRING', 'growth', 'PUBLIC', 'Goals saving earning goal button label.', 1),
  (608, 'growth.goals.min_target_warn', 'Minimum target is $100', 'STRING', 'growth', 'PUBLIC', 'Goals minimum target warning.', 1),
  (609, 'growth.goals.saved_toast_template', 'Goal saved: {amount} in {days}d', 'STRING', 'growth', 'PUBLIC', 'Goals saved toast template.', 1),
  (610, 'growth.goals.delete_goal_toast', 'Goal removed', 'STRING', 'growth', 'PUBLIC', 'Goals delete earning goal toast.', 1),
  (611, 'growth.goals.active_goals_title', 'Active earning goals', 'STRING', 'growth', 'PUBLIC', 'Goals active earning goals title.', 1),
  (612, 'growth.goals.deadline_row_template', '{n} days left', 'STRING', 'growth', 'PUBLIC', 'Goals deadline row template.', 1),
  (613, 'growth.goals.achieved_badge_label', 'Achieved', 'STRING', 'growth', 'PUBLIC', 'Goals achieved badge label.', 1),
  (614, 'growth.goals.rec_cloud_share_name', 'Cloud Share', 'STRING', 'growth', 'PUBLIC', 'Goals Cloud Share recommendation name.', 1),
  (615, 'growth.goals.rec_cloud_share_max_per_day', '1', 'NUMBER', 'growth', 'PUBLIC', 'Goals Cloud Share max daily USDT target.', 1),
  (616, 'growth.goals.rec_cloud_share_reason', 'Start with shared cloud exposure while your target stays light.', 'STRING', 'growth', 'PUBLIC', 'Goals Cloud Share recommendation reason.', 1),
  (617, 'growth.goals.rec_s1_name', 'NexionBox S1', 'STRING', 'growth', 'PUBLIC', 'Goals S1 recommendation name.', 1),
  (618, 'growth.goals.rec_s1_max_per_day', '38.5', 'NUMBER', 'growth', 'PUBLIC', 'Goals S1 max daily USDT target.', 1),
  (619, 'growth.goals.rec_s1_reason', 'S1 daily output is the default path for a steady home compute goal.', 'STRING', 'growth', 'PUBLIC', 'Goals S1 recommendation reason.', 1),
  (620, 'growth.goals.rec_pro_name', 'NexionBox Pro', 'STRING', 'growth', 'PUBLIC', 'Goals Pro recommendation name.', 1),
  (621, 'growth.goals.rec_pro_max_per_day', '76', 'NUMBER', 'growth', 'PUBLIC', 'Goals Pro max daily USDT target.', 1),
  (622, 'growth.goals.rec_pro_reason', 'Pro capacity fits higher daily USDT targets without jumping to rack operations.', 'STRING', 'growth', 'PUBLIC', 'Goals Pro recommendation reason.', 1),
  (623, 'growth.goals.rec_rack_name', 'NexionRack P1', 'STRING', 'growth', 'PUBLIC', 'Goals Rack recommendation name.', 1),
  (624, 'growth.goals.rec_rack_reason', 'Rack capacity is recommended when the daily target exceeds single-device economics.', 'STRING', 'growth', 'PUBLIC', 'Goals Rack recommendation reason.', 1),
  (498, 'growth.daily.hero_copy', 'Keep today active, recover missed streaks, and unlock configured milestone rewards.', 'STRING', 'growth', 'PUBLIC', 'Daily page hero copy shown in uniapp.', 1),
  (499, 'growth.daily.loading_copy', 'Loading daily rewards...', 'STRING', 'growth', 'PUBLIC', 'Daily page loading-state copy.', 1),
  (500, 'growth.daily.next_milestone_done_label', 'All caught up', 'STRING', 'growth', 'PUBLIC', 'Daily next milestone label when every configured milestone is complete.', 1),
  (501, 'growth.daily.saver_title', 'Streak saver available', 'STRING', 'growth', 'PUBLIC', 'Daily streak saver card title.', 1),
  (502, 'growth.daily.saver_copy', 'Recover yesterday''s missed activity and keep the current reward chain alive.', 'STRING', 'growth', 'PUBLIC', 'Daily streak saver card body copy.', 1),
  (503, 'growth.daily.baseline_points', '10', 'NUMBER', 'growth', 'PUBLIC', 'Daily baseline check-in points shown in uniapp.', 1),
  (504, 'growth.daily.streak_bonus_7d_points', '20', 'NUMBER', 'growth', 'PUBLIC', 'Daily 7-day streak bonus points shown in uniapp.', 1),
  (505, 'growth.daily.lucky_multiplier_max', '3', 'NUMBER', 'growth', 'PUBLIC', 'Daily lucky multiplier cap shown in uniapp and PC Growth H5.', 1),
  (506, 'growth.daily.lucky_probability_pct', '18', 'NUMBER', 'growth', 'PUBLIC', 'Daily lucky multiplier probability percentage shown in uniapp.', 1),
  (507, 'growth.daily.points_redeem_rate', '100 pts = $1', 'STRING', 'growth', 'PUBLIC', 'Daily points redemption rate shown in uniapp and PC Growth H5.', 1),
  (508, 'growth.daily.leaderboard_limit', '5', 'NUMBER', 'growth', 'PUBLIC', 'Daily top streakers row limit used by uniapp.', 1),
  (509, 'growth.daily.milestones_empty_copy', 'No milestone configuration yet.', 'STRING', 'growth', 'PUBLIC', 'Daily milestones empty-state copy.', 1),
  (510, 'growth.daily.powerups_empty_copy', 'No power-up configuration yet.', 'STRING', 'growth', 'PUBLIC', 'Daily power-up empty-state copy.', 1),
  (511, 'growth.daily.leaderboard_empty_copy', 'No leaderboard entries yet.', 'STRING', 'growth', 'PUBLIC', 'Daily top streakers empty-state copy.', 1),
  (512, 'growth.daily.metric_longest_label', 'Longest streak', 'STRING', 'growth', 'PUBLIC', 'Daily longest-streak metric label.', 1),
  (513, 'growth.daily.metric_next_label', 'Next milestone', 'STRING', 'growth', 'PUBLIC', 'Daily next-milestone metric label.', 1),
  (514, 'growth.daily.metric_savers_label', 'Streak savers', 'STRING', 'growth', 'PUBLIC', 'Daily streak-saver metric label.', 1),
  (515, 'growth.daily.metric_recoverable_label', 'Recoverable', 'STRING', 'growth', 'PUBLIC', 'Daily recoverable-streak metric label.', 1),
  (516, 'growth.daily.checkin_label', 'Check in', 'STRING', 'growth', 'PUBLIC', 'Daily check-in button label.', 1),
  (517, 'growth.daily.checked_in_label', 'Checked in', 'STRING', 'growth', 'PUBLIC', 'Daily checked-in button label.', 1),
  (518, 'growth.daily.streak_saver_action_label', 'Use', 'STRING', 'growth', 'PUBLIC', 'Daily streak saver action label.', 1),
  (519, 'growth.daily.checked_in_toast', 'Checked in', 'STRING', 'growth', 'PUBLIC', 'Daily check-in success toast.', 1),
  (520, 'growth.daily.streak_restored_toast', 'Streak restored', 'STRING', 'growth', 'PUBLIC', 'Daily streak saver success toast.', 1),
  (521, 'growth.daily.reward_claimed_toast', 'Reward claimed', 'STRING', 'growth', 'PUBLIC', 'Daily milestone claim success toast.', 1),
  (522, 'growth.daily.powerup_active_toast', 'Power-up active', 'STRING', 'growth', 'PUBLIC', 'Daily power-up activation success toast.', 1),
  (625, 'growth.daily.points_balance_label', 'Points balance', 'STRING', 'growth', 'PUBLIC', 'Daily points balance card label.', 1),
  (626, 'growth.daily.next_reset_label', 'Next check-in', 'STRING', 'growth', 'PUBLIC', 'Daily next check-in countdown label.', 1),
  (627, 'growth.daily.next_reward_label', 'Next reward', 'STRING', 'growth', 'PUBLIC', 'Daily next milestone reward label.', 1),
  (628, 'growth.daily.ready_now_label', 'Ready now', 'STRING', 'growth', 'PUBLIC', 'Daily ready-to-check-in state label.', 1),
  (629, 'growth.daily.week_calendar_title', 'This week', 'STRING', 'growth', 'PUBLIC', 'Daily seven-day calendar title.', 1),
  (630, 'growth.daily.roadmap_title', 'Streak roadmap', 'STRING', 'growth', 'PUBLIC', 'Daily streak milestone roadmap title.', 1),
  (631, 'growth.daily.points_history_title', 'Recent points', 'STRING', 'growth', 'PUBLIC', 'Daily recent points ledger title.', 1),
  (632, 'growth.daily.points_history_empty_copy', 'No points ledger rows yet.', 'STRING', 'growth', 'PUBLIC', 'Daily recent points ledger empty-state copy.', 1),
  (633, 'growth.events.title', 'Events', 'STRING', 'growth', 'PUBLIC', 'Events page title shown in uniapp.', 1),
  (634, 'growth.events.hero_label', 'Event cockpit', 'STRING', 'growth', 'PUBLIC', 'Events hero eyebrow.', 1),
  (635, 'growth.events.hero_copy', 'Track live campaigns, claim configured event rewards, and follow progress from PC Growth.', 'STRING', 'growth', 'PUBLIC', 'Events hero copy shown in uniapp.', 1),
  (636, 'growth.events.points_label', 'Points', 'STRING', 'growth', 'PUBLIC', 'Events points metric label.', 1),
  (637, 'growth.events.active_label', 'Active', 'STRING', 'growth', 'PUBLIC', 'Events active count label.', 1),
  (638, 'growth.events.claimable_label', 'Claimable', 'STRING', 'growth', 'PUBLIC', 'Events claimable count label.', 1),
  (639, 'growth.events.total_label', 'Total', 'STRING', 'growth', 'PUBLIC', 'Events total count label.', 1),
  (640, 'growth.events.featured_label', 'Featured', 'STRING', 'growth', 'PUBLIC', 'Events featured-card label.', 1),
  (641, 'growth.events.reward_label', 'Reward', 'STRING', 'growth', 'PUBLIC', 'Events reward field label.', 1),
  (642, 'growth.events.tabs_all', 'All', 'STRING', 'growth', 'PUBLIC', 'Events all tab label.', 1),
  (643, 'growth.events.tabs_ongoing', 'Ongoing', 'STRING', 'growth', 'PUBLIC', 'Events ongoing tab label.', 1),
  (644, 'growth.events.tabs_upcoming', 'Upcoming', 'STRING', 'growth', 'PUBLIC', 'Events upcoming tab label.', 1),
  (645, 'growth.events.tabs_joined', 'Joined', 'STRING', 'growth', 'PUBLIC', 'Events joined tab label.', 1),
  (646, 'growth.events.tabs_ended', 'Ended', 'STRING', 'growth', 'PUBLIC', 'Events ended tab label.', 1),
  (647, 'growth.events.empty_all', 'No event quests configured yet.', 'STRING', 'growth', 'PUBLIC', 'Events all tab empty-state copy.', 1),
  (648, 'growth.events.empty_ongoing', 'No ongoing event quests.', 'STRING', 'growth', 'PUBLIC', 'Events ongoing tab empty-state copy.', 1),
  (649, 'growth.events.empty_upcoming', 'No upcoming event quests.', 'STRING', 'growth', 'PUBLIC', 'Events upcoming tab empty-state copy.', 1),
  (650, 'growth.events.empty_joined', 'No joined event quests yet.', 'STRING', 'growth', 'PUBLIC', 'Events joined tab empty-state copy.', 1),
  (651, 'growth.events.empty_ended', 'No ended event quests.', 'STRING', 'growth', 'PUBLIC', 'Events ended tab empty-state copy.', 1),
  (652, 'growth.events.claim_label', 'Claim', 'STRING', 'growth', 'PUBLIC', 'Events claim button label.', 1),
  (653, 'growth.events.claiming_label', 'Claiming', 'STRING', 'growth', 'PUBLIC', 'Events claiming button label.', 1),
  (654, 'growth.events.claimed_label', 'Claimed', 'STRING', 'growth', 'PUBLIC', 'Events claimed status label.', 1),
  (655, 'growth.events.view_progress_label', 'View', 'STRING', 'growth', 'PUBLIC', 'Events featured progress button label.', 1),
  (656, 'growth.events.open_goals_label', 'Goals', 'STRING', 'growth', 'PUBLIC', 'Events goals navigation button label.', 1),
  (657, 'growth.events.claimed_toast', 'Event claimed', 'STRING', 'growth', 'PUBLIC', 'Events claim success toast.', 1),
  (658, 'growth.events.claim_failed_toast', 'Claim failed', 'STRING', 'growth', 'PUBLIC', 'Events claim failure fallback toast.', 1),
  (659, 'growth.events.reward_fallback', 'Reward', 'STRING', 'growth', 'PUBLIC', 'Events reward fallback label.', 1),
  (660, 'growth.events.status_open_label', 'Open', 'STRING', 'growth', 'PUBLIC', 'Events open status label.', 1),
  (661, 'growth.events.status_upcoming_label', 'Upcoming', 'STRING', 'growth', 'PUBLIC', 'Events upcoming status label.', 1),
  (662, 'growth.events.status_ended_label', 'Ended', 'STRING', 'growth', 'PUBLIC', 'Events ended status label.', 1),
  (663, 'growth.events.always_on_label', 'Always on', 'STRING', 'growth', 'PUBLIC', 'Events no-start-time label.', 1),
  (664, 'growth.events.ends_label', 'Ends', 'STRING', 'growth', 'PUBLIC', 'Events end-time prefix label.', 1),
  (665, 'growth.events.ledger_title', 'Recent points', 'STRING', 'growth', 'PUBLIC', 'Events points ledger title.', 1),
  (666, 'growth.events.ledger_empty_copy', 'No event point ledger yet.', 'STRING', 'growth', 'PUBLIC', 'Events points ledger empty-state copy.', 1),
  (667, 'growth.events.loading_copy', 'Loading events...', 'STRING', 'growth', 'PUBLIC', 'Events loading-state copy.', 1),
  (668, 'growth.events.error_copy', 'Events request failed', 'STRING', 'growth', 'PUBLIC', 'Events request error fallback copy.', 1),
  (669, 'growth.events.note', 'Server authoritative', 'STRING', 'growth', 'PUBLIC', 'Events points ledger note.', 1),
  (682, 'growth.me.level_progress_points_per_pct', '100', 'NUMBER', 'growth', 'PUBLIC', 'Me page fallback points required for one percent of level progress.', 1),
  (523, 'growth.achievement.hero_copy', 'Unlock status, progress, and reward points are loaded from the mission service.', 'STRING', 'growth', 'PUBLIC', 'Achievements page hero copy shown in uniapp.', 1),
  (524, 'growth.achievement.loading_copy', 'Loading achievements...', 'STRING', 'growth', 'PUBLIC', 'Achievements page loading-state copy.', 1),
  (525, 'growth.achievement.empty_copy', 'No achievement configuration yet.', 'STRING', 'growth', 'PUBLIC', 'Achievements page empty-state copy.', 1),
  (526, 'growth.achievement.unlocked_label', 'Unlocked', 'STRING', 'growth', 'PUBLIC', 'Achievements unlocked metric label.', 1),
  (527, 'growth.achievement.claimed_label', 'Claimed', 'STRING', 'growth', 'PUBLIC', 'Achievements claimed metric label.', 1),
  (528, 'growth.achievement.reward_points_label', 'Reward points', 'STRING', 'growth', 'PUBLIC', 'Achievements reward-points metric label.', 1),
  (529, 'growth.achievement.completion_label', 'Completion', 'STRING', 'growth', 'PUBLIC', 'Achievements completion metric label.', 1),
  (530, 'growth.achievement.progress_label', 'Progress', 'STRING', 'growth', 'PUBLIC', 'Achievements row progress fallback label.', 1),
  (531, 'growth.achievement.points_suffix', 'points', 'STRING', 'growth', 'PUBLIC', 'Achievements reward points suffix.', 1),
  (532, 'growth.achievement.claim_label', 'Claim', 'STRING', 'growth', 'PUBLIC', 'Achievements claim button label.', 1),
  (533, 'growth.achievement.claiming_label', 'Claiming', 'STRING', 'growth', 'PUBLIC', 'Achievements claiming button label.', 1),
  (534, 'growth.achievement.claimed_toast', 'Reward claimed', 'STRING', 'growth', 'PUBLIC', 'Achievements claim success toast.', 1),
  (535, 'growth.achievement.claim_failed_toast', 'Claim failed', 'STRING', 'growth', 'PUBLIC', 'Achievements claim failure fallback toast.', 1),
  (536, 'growth.achievement.default_accent_color', '#9EDC1D', 'STRING', 'growth', 'PUBLIC', 'Achievements default badge accent color.', 1),
  (537, 'growth.achievement.category.LOYALTY', 'Loyalty', 'STRING', 'growth', 'PUBLIC', 'Achievements LOYALTY category label.', 1),
  (538, 'growth.achievement.category.STREAK', 'Streak', 'STRING', 'growth', 'PUBLIC', 'Achievements STREAK category label.', 1),
  (539, 'growth.achievement.category.MISSION', 'Mission', 'STRING', 'growth', 'PUBLIC', 'Achievements MISSION category label.', 1),
  (540, 'growth.achievement.category.COMPUTE', 'Compute', 'STRING', 'growth', 'PUBLIC', 'Achievements COMPUTE category label.', 1),
  (541, 'growth.achievement.category.WALLET', 'Wallet', 'STRING', 'growth', 'PUBLIC', 'Achievements WALLET category label.', 1),
  (542, 'growth.achievement.category.PROFILE', 'Profile', 'STRING', 'growth', 'PUBLIC', 'Achievements PROFILE category label.', 1),
  (543, 'growth.earning_milestones.title', 'Earning milestones', 'STRING', 'growth', 'PUBLIC', 'Earning milestones page title shown in uniapp.', 1),
  (544, 'growth.earning_milestones.eyebrow', 'Lifetime rewards', 'STRING', 'growth', 'PUBLIC', 'Earning milestones hero eyebrow.', 1),
  (545, 'growth.earning_milestones.hero_copy', 'Milestone thresholds and NEX rewards are loaded from the earnings service.', 'STRING', 'growth', 'PUBLIC', 'Earning milestones hero copy shown in uniapp.', 1),
  (546, 'growth.earning_milestones.refresh_label', 'Refresh', 'STRING', 'growth', 'PUBLIC', 'Earning milestones refresh button label.', 1),
  (547, 'growth.earning_milestones.loading_copy', 'Loading earning milestones...', 'STRING', 'growth', 'PUBLIC', 'Earning milestones loading-state copy.', 1),
  (548, 'growth.earning_milestones.error_copy', 'Milestone request failed', 'STRING', 'growth', 'PUBLIC', 'Earning milestones error fallback copy.', 1),
  (549, 'growth.earning_milestones.next_label', 'Next milestone', 'STRING', 'growth', 'PUBLIC', 'Earning milestones next metric label.', 1),
  (550, 'growth.earning_milestones.remaining_label', 'Remaining', 'STRING', 'growth', 'PUBLIC', 'Earning milestones remaining metric label.', 1),
  (551, 'growth.earning_milestones.progress_label', 'Progress', 'STRING', 'growth', 'PUBLIC', 'Earning milestones progress metric label.', 1),
  (552, 'growth.earning_milestones.count_label', 'Milestones', 'STRING', 'growth', 'PUBLIC', 'Earning milestones count metric label.', 1),
  (553, 'growth.earning_milestones.complete_label', 'Complete', 'STRING', 'growth', 'PUBLIC', 'Earning milestones completed next-state label.', 1),
  (554, 'growth.earning_milestones.path_progress_label', 'Path progress', 'STRING', 'growth', 'PUBLIC', 'Earning milestones path progress label.', 1),
  (555, 'growth.earning_milestones.ladder_title', 'Reward ladder', 'STRING', 'growth', 'PUBLIC', 'Earning milestones ladder title.', 1),
  (556, 'growth.earning_milestones.empty_copy', 'No earning milestone configuration yet.', 'STRING', 'growth', 'PUBLIC', 'Earning milestones empty-state copy.', 1),
  (557, 'growth.earning_milestones.achieved_label', 'Achieved', 'STRING', 'growth', 'PUBLIC', 'Earning milestones achieved row label.', 1),
  (558, 'growth.earning_milestones.lifetime_suffix', 'lifetime', 'STRING', 'growth', 'PUBLIC', 'Earning milestones threshold suffix.', 1),
  (559, 'growth.earning_milestones.reward_suffix', 'NEX', 'STRING', 'growth', 'PUBLIC', 'Earning milestones reward suffix.', 1),
  (420, 'growth.phase.pin', '自动跟随', 'STRING', 'growth', 'ADMIN', 'Manual H1 phase pin. 自动跟随 restores scheduler authority.', 1),
  (421, 'growth.phase.cohort_override', '', 'STRING', 'growth', 'ADMIN', 'Optional cohort-specific H1 phase override.', 1),
  (422, 'growth.phase.dial.withdraw_cooldown_days', '7', 'STRING', 'growth', 'ADMIN', 'H1 phase withdrawal cooldown dial.', 1),
  (423, 'growth.phase.dial.withdraw_daily_cap_usd', '$2,000', 'STRING', 'growth', 'ADMIN', 'H1 phase withdrawal daily cap dial.', 1),
  (424, 'growth.phase.dial.withdraw_points_ratio', '30%', 'STRING', 'growth', 'ADMIN', 'H1 phase withdrawal points ratio dial.', 1),
  (425, 'growth.phase.dial.binary_daily_cap_usd', '$1,500', 'STRING', 'growth', 'ADMIN', 'H1 phase binary daily cap dial.', 1),
  (426, 'growth.phase.dial.staking_apy_boost', '1.0x', 'STRING', 'growth', 'ADMIN', 'H1 phase staking APY boost dial.', 1),
  (427, 'growth.phase.dial.nova_cadence_mult', '1.2x', 'STRING', 'growth', 'ADMIN', 'H1 phase Nova cadence multiplier dial.', 1),
  (428, 'growth.phase.dial.quest_reward_mult', '1.5x', 'STRING', 'growth', 'ADMIN', 'H1 phase quest reward multiplier dial.', 1),
  (429, 'growth.phase.dial.trial_offset_cap_usd', '$50', 'STRING', 'growth', 'ADMIN', 'H1 phase trial offset cap dial.', 1),
  (430, 'growth.phase.dial.store_discount_ladder', 'T2', 'STRING', 'growth', 'ADMIN', 'H1 phase store discount ladder dial.', 1),
  (431, 'growth.phase.dial.genesis_dividend_rate', '0.1%', 'STRING', 'growth', 'ADMIN', 'H1 phase Genesis dividend rate dial.', 1),
  (232, 'openapi.developer.partner_integrations', 'AWS,Google Cloud,Azure,Terraform,Kubernetes,Grafana,Slack,PagerDuty', 'STRING', 'openapi', 'PUBLIC', 'Developer page partner integration chips.', 1),
  (233, 'openapi.developer.docs_endpoint', 'POST /api/openapi/v1/compute/receipts', 'STRING', 'openapi', 'PUBLIC', 'Developer docs endpoint sample.', 1),
  (234, 'openapi.developer.docs_auth_header', 'X-Nexion-App-Key + X-Nexion-Signature', 'STRING', 'openapi', 'PUBLIC', 'Developer docs auth header label.', 1),
  (432, 'openapi.developer.docs_example_task_type', 'INFERENCE', 'STRING', 'openapi', 'PUBLIC', 'Developer docs example taskType field.', 1),
  (433, 'openapi.developer.docs_example_reward_usdt', '0.060000', 'NUMBER', 'openapi', 'PUBLIC', 'Developer docs example rewardUsdt field.', 1),
  (434, 'openapi.developer.docs_example_metadata', 'partner-demo', 'STRING', 'openapi', 'PUBLIC', 'Developer docs example metadata field.', 1),
  (236, 'openapi.developer.docs_note', 'Signing, nonce, and webhook rules are maintained by backend OpenAPI config.', 'STRING', 'openapi', 'PUBLIC', 'Developer docs note.', 1),
  (237, 'openapi.developer.default_qps_limit', '20', 'NUMBER', 'openapi', 'PUBLIC', 'Default QPS limit shown when the user has no OpenAPI app.', 1),
  (238, 'openapi.developer.default_daily_limit', '10000', 'NUMBER', 'openapi', 'PUBLIC', 'Default daily limit shown when the user has no OpenAPI app.', 1),
  (239, 'openapi.developer.form_use_case_min_length', '8', 'NUMBER', 'openapi', 'PUBLIC', 'Minimum Developer access request use-case length.', 1),
  (300, 'emergency.killswitch.withdraw', 'on', 'STRING', 'emergency', 'PRIVATE', 'J1 Kill-Switch for platform withdrawal outflow.', 1),
  (301, 'emergency.killswitch.staking', 'on', 'STRING', 'emergency', 'PRIVATE', 'J1 Kill-Switch for staking products.', 1),
  (302, 'emergency.killswitch.genesis', 'on', 'STRING', 'emergency', 'PRIVATE', 'J1 Kill-Switch for Genesis economy.', 1),
  (303, 'emergency.killswitch.exchange', 'on', 'STRING', 'emergency', 'PRIVATE', 'J1 Kill-Switch for NEX exchange.', 1),
  (304, 'emergency.killswitch.trial', 'on', 'STRING', 'emergency', 'PRIVATE', 'J1 Kill-Switch for free trial engine.', 1),
  (305, 'emergency.killswitch.nexv2', 'off', 'STRING', 'emergency', 'PRIVATE', 'J1 Kill-Switch for NEX v2 Vault.', 1),
  (307, 'emergency.geo.US', 'allowed', 'STRING', 'emergency', 'PRIVATE', 'J2 Geo-block state for United States.', 1),
  (308, 'emergency.geo.CN', 'blocked', 'STRING', 'emergency', 'PRIVATE', 'J2 Geo-block state for China.', 1),
  (309, 'emergency.geo.KP', 'blocked', 'STRING', 'emergency', 'PRIVATE', 'J2 Geo-block state for North Korea.', 1),
  (310, 'emergency.geo.IR', 'blocked', 'STRING', 'emergency', 'PRIVATE', 'J2 Geo-block state for Iran.', 1),
  (311, 'emergency.geo.IN', 'allowed', 'STRING', 'emergency', 'PRIVATE', 'J2 Geo-block state for India.', 1),
  (312, 'emergency.geo.BR', 'allowed', 'STRING', 'emergency', 'PRIVATE', 'J2 Geo-block state for Brazil.', 1)
ON DUPLICATE KEY UPDATE
  config_value = VALUES(config_value),
  value_type = VALUES(value_type),
  config_group = VALUES(config_group),
  visibility = VALUES(visibility),
  remark = VALUES(remark),
  status = VALUES(status);

INSERT INTO nx_i18n_message (id, message_key, locale, message_value, status)
VALUES
  (1, 'app.name', 'en-US', 'Nexion', 1),
  (2, 'app.name', 'zh-CN', 'Nexion', 1),
  (3, 'home.compute_marketplace.title', 'en-US', 'Distributed AI compute marketplace', 1),
  (4, 'home.compute_marketplace.title', 'zh-CN', '分布式 AI 算力商城', 1)
ON DUPLICATE KEY UPDATE
  message_value = VALUES(message_value),
  status = VALUES(status);

INSERT INTO nx_i18n_message (message_key, locale, message_value, status)
VALUES
  ('milestones.earnCross', 'en-US', 'You just crossed {amount} in lifetime earnings - {nex} NEX is on its way.', 1),
  ('milestones.earnCross', 'zh-CN', '累计收益突破 {amount},奖励 {nex} NEX 马上到账。', 1),
  ('learn.what-is-nexion.title', 'en-US', 'What is Nexion - the 5-minute crash course', 1),
  ('learn.what-is-nexion.title', 'zh-CN', 'What is Nexion - 5 分钟速成', 1),
  ('learn.what-is-nexion.body', 'en-US', 'Understand devices, verified compute work, settlement, NEX rewards, and USDT balances.', 1),
  ('learn.what-is-nexion.body', 'zh-CN', '了解设备、可验证算力、结算、NEX 奖励和 USDT 余额如何协同。', 1)
ON DUPLICATE KEY UPDATE
  message_value = VALUES(message_value),
  status = VALUES(status);

INSERT INTO nx_i18n_namespace (namespace_code, key_count, coverage_pct, variants, last_change, status, sort_order)
VALUES
  ('home', 128, 100, '-', '06-09', 1, 10),
  ('binaryHowItWorks', 30, 100, '-', '05-30', 1, 20),
  ('exchangeHowItWorks', 35, 100, '-', '06-02', 1, 30),
  ('device', 42, 99, '-', '06-06', 1, 40),
  ('marketing', 64, 95, '多版 x3', '06-05', 1, 50),
  ('milestones', 22, 100, '多版 x1', '06-09', 1, 60),
  ('team', 41, 100, '-', '05-30', 1, 70),
  ('wallet', 52, 98, '-', '06-05', 1, 80),
  ('trust', 38, 95, '-', '05-12', 1, 90),
  ('genesis', 29, 99, '-', '05-26', 1, 100),
  ('riskDisclosure', 44, 100, '-', '06-08', 1, 110),
  ('learn', 36, 100, '-', '06-01', 1, 120),
  ('premium_legacy', 18, 100, 'historical compatibility only', '05-14', 0, 900)
ON DUPLICATE KEY UPDATE
  key_count = VALUES(key_count),
  coverage_pct = VALUES(coverage_pct),
  variants = VALUES(variants),
  last_change = VALUES(last_change),
  status = VALUES(status),
  sort_order = VALUES(sort_order);

INSERT INTO nx_i18n_integrity_issue (issue_code, issue_kind, issue_count, samples_text, status, sort_order)
VALUES
  ('missing-zh', '缺镜像 (zh)', 3, 'marketing.referral.tagline\nwallet.lowBalance\ntrust.heroSub', 'open', 10),
  ('missing-en', '缺镜像 (en)', 1, 'genesis.dividendNote', 'open', 20),
  ('placeholder', '占位符不匹配', 2, 'milestones.earnCross({n} 词序异常)\nmarketing.bundle.cta({amount} 缺失)', 'open', 30),
  ('hardcoded', '疑似硬编码', 4, 'store/bundle 页脚 Limited time only\nwallet 空态 No transactions yet\nteam 邀请卡 Invite and earn\nearn 任务卡角标 NEW', 'open', 40)
ON DUPLICATE KEY UPDATE
  issue_kind = VALUES(issue_kind),
  issue_count = VALUES(issue_count),
  samples_text = VALUES(samples_text),
  status = VALUES(status),
  sort_order = VALUES(sort_order);

INSERT INTO nx_i18n_hardcoded_finding (location, raw_copy, suggested_key, status, sort_order)
VALUES
  ('store/bundle 页脚', 'Limited time only', 'store.bundleUrgency', 'open', 10),
  ('wallet 空态', 'No transactions yet', 'wallet.emptyState', 'open', 20),
  ('team 邀请卡', 'Invite and earn', 'team.inviteCta', 'open', 30),
  ('earn 任务卡角标', 'NEW', 'earn.newBadge', 'open', 40)
ON DUPLICATE KEY UPDATE
  raw_copy = VALUES(raw_copy),
  suggested_key = VALUES(suggested_key),
  status = VALUES(status),
  sort_order = VALUES(sort_order);

INSERT INTO nx_content_page (id, page_code, title, content, status)
VALUES
  (1, 'terms.service', 'Terms of Service', 'Nexion service terms baseline content.', 1),
  (2, 'privacy.policy', 'Privacy Policy', 'Nexion privacy policy baseline content.', 1)
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  content = VALUES(content),
  status = VALUES(status);

INSERT INTO nx_content_page (page_code, title, content, status)
VALUES
  ('trust.center', 'Trust Center', 'Compliance contact: compliance@nexion.ai.
SOC 2 Type II, ISO 27001, MSB registration, and KYT monitoring are tracked by the content and compliance team.
Reserve, ledger, and risk disclosures are published from server-managed content pages and reviewed before release.
Nexion keeps product, wallet, and reward claims neutral and evidence-based.', 1),
  ('trust.nex', 'NEX Token Trust', 'NEX utility, lock, and reward pages are backed by wallet ledger records and published disclosure text.
Locked NEX, pending rewards, and exchange balances are displayed from the wallet service.
Token-related content is maintained as a versioned CMS page so compliance can update public wording without app releases.', 1),
  ('trust.compliance.soc2', 'SOC 2 Type II', 'Q1 2026 audit complete with control evidence maintained by compliance operations.', 1),
  ('trust.compliance.iso27001', 'ISO 27001', 'Certification renewed and monitored through the security management program.', 1),
  ('trust.compliance.gdpr', 'GDPR', 'EU data residency, retention, and subject-right controls are maintained for eligible users.', 1),
  ('trust.compliance.hipaa', 'HIPAA', 'Healthcare vertical controls are applied to applicable enterprise compute workloads.', 1),
  ('trust.compliance.msb', 'MSB License', 'FinCEN MSB1234567 registration is tracked by the compliance team.', 1),
  ('trust.compliance.kyt', 'KYT Monitoring', 'Real-time Chainalysis monitoring is used for wallet and transfer risk review.', 1),
  ('trust.assurance.audit', 'Independent audit', 'Control evidence, ledger policy, and operator access are reviewed every quarter.', 1),
  ('trust.assurance.reserve', 'Reserve proof', 'Wallet, reward, and payout disclosures are published from server-managed records.', 1),
  ('trust.assurance.kyc', 'KYC / AML', 'Identity, withdrawal, and KYT checks are enforced before sensitive money movement.', 1),
  ('trust.partner.nvidia', 'NVIDIA', 'GPU ecosystem partner for acceleration and production compute compatibility.', 1),
  ('trust.partner.intel', 'Intel', 'Edge compute hardware partner for consumer and datacenter device classes.', 1),
  ('trust.partner.amd', 'AMD', 'Compute acceleration partner for heterogeneous GPU supply.', 1),
  ('trust.partner.pocket', 'Pocket', 'Decentralized infrastructure partner for network operations.', 1),
  ('trust.investor.a16z', 'a16z crypto', 'Series A lead investor supporting protocol and marketplace governance.', 1),
  ('trust.investor.sequoia', 'Sequoia', 'Growth investor supporting international operating expansion.', 1),
  ('trust.investor.pantera', 'Pantera', 'Strategic investor supporting crypto market infrastructure.', 1),
  ('trust.investor.coinbase', 'Coinbase Ventures', 'Ecosystem investor supporting compliant token utility growth.', 1),
  ('trust.leadership.james', 'James Chen', 'CEO · ex-Google Cloud distributed systems.', 1),
  ('trust.leadership.sarah', 'Sarah Park', 'CPO · ex-Stripe risk and compliance.', 1),
  ('trust.leadership.marcus', 'Marcus Reid', 'CTO · ex-NVIDIA edge AI.', 1),
  ('trust.leadership.aisha', 'Aisha Tariq', 'GC · fintech regulatory counsel.', 1),
  ('trust.press.techcrunch', 'TechCrunch', 'Nexion turns idle devices into AI compute supply.', 1),
  ('trust.press.coindesk', 'CoinDesk', 'NEX utility model draws enterprise AI demand.', 1),
  ('trust.press.forbes', 'Forbes', 'Distributed edge compute enters the consumer wallet.', 1),
  ('trust.client.helix', 'Helix Labs', '1,240,000 NEX monthly compute demand from AI research workloads.', 1),
  ('trust.client.mosaic', 'Mosaic Studios', '412,000 NEX monthly rendering demand from creative production workloads.', 1),
  ('trust.client.echo', 'Echo Earbuds', '287,000 NEX monthly inference demand from consumer audio workloads.', 1),
  ('trust.listing.pancakeswap', 'PancakeSwap', 'Live.', 1),
  ('trust.listing.uniswap', 'Uniswap V3', 'Live.', 1),
  ('trust.listing.coingecko', 'CoinGecko', 'Listed.', 1),
  ('trust.listing.coinmarketcap', 'CoinMarketCap', 'Listed.', 1),
  ('trust.listing.binance', 'Binance', 'Tier-1 review.', 1),
  ('trust.listing.coinbase', 'Coinbase', 'Application in Q3.', 1),
  ('trust.nex.compare.price_basis', 'Price basis', 'USDT: Fixed settlement reference.
NEX: Variable token price from backend wallet market configuration.', 1),
  ('trust.nex.compare.role', 'Role', 'USDT: Wallet settlement and deposit reference.
NEX: Compute utility, reward accounting, staking locks, and Genesis utility are disclosed through CMS and wallet-ledger records.', 1),
  ('trust.nex.compare.supply', 'Supply', 'USDT: Reserve and issuer policy disclosure.
NEX: Circulating supply is read from backend wallet market configuration.', 1),
  ('trust.nex.compare.market_state', 'Market state', 'USDT: Settlement reference.
NEX: Pause and live-market state are read from backend wallet market configuration.', 1),
  ('trust.nex.source.compute', 'Compute rewards', 'NEX is earned from validated compute work and mission reward records.', 1),
  ('trust.nex.source.exchange', 'Wallet exchange', 'Eligible users can exchange USDT into NEX through the wallet service.', 1),
  ('trust.nex.source.campaign', 'Campaign rewards', 'Operations can publish NEX rewards through missions and event quests.', 1),
  ('trust.nex.use.lock', 'Locked staking', 'Lock NEX for configured terms and transparent wallet-ledger accounting.', 1),
  ('trust.nex.use.fee', 'Fee discounts', 'NEX balance and premium tiers can reduce selected service fees.', 1),
  ('trust.nex.use.compute', 'Compute demand', 'AI buyers consume NEX-denominated compute capacity from the network.', 1),
  ('trust.nex.use.genesis', 'Genesis utility', 'Genesis holder benefits and market controls are backed by commerce records.', 1),
  ('trust.nex.faq.stable', 'Is NEX a stablecoin?', 'No. NEX is a variable utility token. USDT remains the fixed-value settlement reference.', 1),
  ('trust.nex.faq.locked', 'What is locked NEX?', 'Locked NEX is held by wallet-ledger records during the selected term and cannot be spent until released.', 1),
  ('trust.nex.faq.burn', 'How does demand affect supply?', 'Repurchase, burn, and liquidity controls are configured by backend operations and disclosed before release.', 1),
  ('risk.disclosure.global', 'Global Risk Disclosure', 'Nexion device output, NEX rewards, wallet balances, staking, Genesis holdings, and team commissions can fluctuate and may be delayed, reduced, paused, or unavailable.
Estimated income, hashrate, price indexes, leaderboards, and projected returns are informational displays, not guaranteed returns or financial advice.
Purchases, withdrawals, exchanges, staking actions, and reward claims may require KYC, risk review, liquidity checks, network confirmation, compliance review, or manual operations handling.
You are responsible for understanding local rules, market volatility, platform risk, device lifecycle degradation, liquidity limits, and smart-contract or infrastructure incidents before using Nexion features.', 1)
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  content = VALUES(content),
  status = VALUES(status);

INSERT INTO nx_notification_cap_rule (tier, cap_label, policy, locked, sort_order, status, last_operator)
VALUES
  ('critical', '∞ 永不淘汰', '合规重确认 / 风控异动 / 资金账户异动 - 合规硬约束:不可调降,一条都不能丢', 1, 10, 1, 'seed'),
  ('high', '50 条', 'tier 内 LIFO - 高优运营事件优先保留', 0, 20, 1, 'seed'),
  ('normal', '200 条', '通知中心总上限 - 常规运营公告', 0, 30, 1, 'seed'),
  ('low', '30 条 · TTL 24-48h', '教程提示等低优 - 数量+时间双闸,过期自动清', 0, 40, 1, 'seed')
ON DUPLICATE KEY UPDATE
  cap_label = VALUES(cap_label),
  policy = VALUES(policy),
  locked = VALUES(locked),
  sort_order = VALUES(sort_order),
  status = VALUES(status),
  last_operator = VALUES(last_operator);

INSERT INTO nx_notification_campaign (campaign_no, name, kind, tier, audience, reach_label, status, schedule_text, sent_label, read_label, body_en, body_zh, swipe_to, budget_usd, created_by, last_operator)
VALUES
  ('CMP-2618', '6/15 钱包维护窗口公告', 'system', 'high', '全量', '182K', 'SCHEDULED', '06-15 02:00 排期', '-', '-', 'Scheduled wallet maintenance Jun 15 02:00-04:00 UTC. Withdrawals paused during the window.', '6 月 15 日 02:00-04:00(UTC)钱包例行维护,期间暂停提现。', '/me/notifications/maintenance-0615', NULL, 'seed', 'seed'),
  ('CMP-2617', '风险披露 v12 重确认提醒(SFC 辖区)', 'system', 'critical', 'SFC 辖区 · 未重确认用户', '9.4K', 'SCHEDULED', '06-12 10:00 排期', '-', '-', 'Updated risk disclosure requires re-acknowledgement before your next withdrawal.', '风险披露条款已更新,下次提现前需要重新确认。', '/me/risk-disclosure', NULL, 'seed', 'seed'),
  ('CMP-2615', 'KYC 二级认证引导(大额用户)', 'system', 'high', '近 30 天提现 >$1k', '12.6K', 'SENT', '06-08 已发', '12.4K', '9.1K', 'Complete advanced verification to keep higher withdrawal limits.', '完成进阶认证,保住更高的提现额度。', '/me/kyc', NULL, 'seed', 'seed'),
  ('CMP-2612', 'P3 阶段运营公告 · 周任务上新', 'system', 'normal', '全量', '182K', 'SENT', '06-02 已发', '178K', '104K', 'New weekly quests are live - check this week''s board.', '本周新任务已上线,去看看任务板。', '/earn/quests', NULL, 'seed', 'seed'),
  ('CMP-2609', '监管通告 · 服务条款更新', 'system', 'critical', '全量', '181K', 'SENT', '05-28 已发', '181K', '152K', 'Our Terms of Service have been updated effective Jun 1.', '服务条款已更新,6 月 1 日生效。', '/trust', NULL, 'seed', 'seed'),
  ('CMP-2606', '低优 · 教程中心上新提示', 'system', 'low', '注册 ≤14 天', '21K', 'SENT', '05-21 已发', '20.6K', '7.8K', 'New beginner lessons in Learn - earn NEX for finishing.', '教程中心上新了,学完还能领 NEX。', '/learn', NULL, 'seed', 'seed'),
  ('CMP-2619', '7 月费率说明公告(草稿)', 'system', 'normal', '全量', '-', 'DRAFT', '-', '-', '-', '(草稿撰写中)', '(草稿撰写中)', '-', NULL, 'seed', 'seed')
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  kind = VALUES(kind),
  tier = VALUES(tier),
  audience = VALUES(audience),
  reach_label = VALUES(reach_label),
  status = VALUES(status),
  schedule_text = VALUES(schedule_text),
  sent_label = VALUES(sent_label),
  read_label = VALUES(read_label),
  body_en = VALUES(body_en),
  body_zh = VALUES(body_zh),
  swipe_to = VALUES(swipe_to),
  budget_usd = VALUES(budget_usd),
  last_operator = VALUES(last_operator);

INSERT INTO nx_help_article (id, article_code, title, content, category, level, format, duration_min, reward_nex, progress_pct, featured, emoji, tint, sort_order, status)
VALUES
  (1, 'compute.getting_started', 'Getting started with compute devices', 'Activate a device after a paid order, then wait for task dispatch.', 'help', 'beginner', 'article', 5, 0.000000, 0, 0, '📘', '#c6ff3a', 10, 1),
  (2, 'wallet.withdrawal', 'Withdrawal basics', 'Complete KYC and submit a withdrawal request for review and broadcast.', 'help', 'beginner', 'article', 5, 0.000000, 0, 0, '📘', '#c6ff3a', 20, 1)
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  content = VALUES(content),
  category = VALUES(category),
  level = VALUES(level),
  format = VALUES(format),
  duration_min = VALUES(duration_min),
  reward_nex = VALUES(reward_nex),
  progress_pct = VALUES(progress_pct),
  featured = VALUES(featured),
  emoji = VALUES(emoji),
  tint = VALUES(tint),
  sort_order = VALUES(sort_order),
  status = VALUES(status);

INSERT INTO nx_help_article (article_code, title, content, category, level, format, surface, duration_min, reward_nex, progress_pct, featured, emoji, tint, sort_order, status)
VALUES
  ('support.withdrawal.pending', 'Why is my withdrawal still pending?', 'Most pending withdrawals are waiting for payment desk review, network settlement, KYC re-check, or liquidity queue release. Open a withdrawal ticket with chain, amount, and request time if the SLA is exceeded.', 'withdrawal', 'support', 'faq', 'Help Center', 3, 0.000000, 0, 0, '📘', '#c6ff3a', 10, 1),
  ('support.kyc.retry', 'What should I do after a KYC rejection?', 'Upload a clear document image, keep every corner visible, and avoid cropped or edited files. Support can reset the review link when the retry window is exhausted.', 'kyc', 'support', 'faq', 'Ticket Create', 3, 0.000000, 0, 0, '📘', '#c6ff3a', 20, 1),
  ('support.hardware.offline', 'How do I recover a disconnected NexionBox?', 'Hold power for 10 seconds, re-pair the device in the app, and attach the LED pattern to a hardware support ticket if it stays offline.', 'hardware', 'support', 'faq', 'Nova', 3, 0.000000, 0, 0, '📘', '#c6ff3a', 30, 1)
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  content = VALUES(content),
  category = VALUES(category),
  level = VALUES(level),
  format = VALUES(format),
  surface = VALUES(surface),
  duration_min = VALUES(duration_min),
  reward_nex = VALUES(reward_nex),
  progress_pct = VALUES(progress_pct),
  featured = VALUES(featured),
  emoji = VALUES(emoji),
  tint = VALUES(tint),
  sort_order = VALUES(sort_order),
  status = VALUES(status);

INSERT INTO nx_config_item (config_key, config_value, value_type, config_group, visibility, remark, status)
VALUES
  ('I.session.cat.advisor.enabled', 'on', 'BOOLEAN', 'content-session', 'ADMIN', 'M5 advisor conversation category enabled.', 1),
  ('I.session.cat.support.enabled', 'on', 'BOOLEAN', 'content-session', 'ADMIN', 'M5 support conversation category enabled.', 1),
  ('I.session.cat.ai.enabled', 'on', 'BOOLEAN', 'content-session', 'ADMIN', 'M5 read-only Nova AI conversation category mirror.', 1),
  ('I.session.advisor.policy.enabled', 'on', 'BOOLEAN', 'content-session', 'ADMIN', 'M5 advisor proactive push master switch.', 1),
  ('I.session.advisor.policy.delayMs', '1500', 'NUMBER', 'content-session', 'ADMIN', 'M5 advisor proactive push first delay in milliseconds.', 1),
  ('I.session.advisor.policy.cooldownHours', '24', 'NUMBER', 'content-session', 'ADMIN', 'M5 advisor proactive push cooldown hours.', 1),
  ('I.session.advisor.policy.maxPerSession', '1', 'NUMBER', 'content-session', 'ADMIN', 'M5 advisor proactive push max messages per session.', 1),
  ('I.session.advisor.policy.audience', '全量', 'STRING', 'content-session', 'ADMIN', 'M5 advisor proactive push audience segment.', 1)
ON DUPLICATE KEY UPDATE
  config_value = VALUES(config_value),
  value_type = VALUES(value_type),
  config_group = VALUES(config_group),
  visibility = VALUES(visibility),
  remark = VALUES(remark),
  status = VALUES(status);

INSERT INTO nx_help_article (article_code, title, content, category, level, format, surface, duration_min, reward_nex, progress_pct, featured, emoji, tint, sort_order, status)
VALUES
  ('AS-001', '开场', '你好,我是你的专属顾问。你可以把当前目标告诉我,我会按设备、锁仓和复投给你拆路径。', 'advisor', '全量', 'session_script', '—', 1, 0.000000, 0, 0, '💬', '#7dd3fc', 10, 1),
  ('AS-002', '升级', '你的设备近期有不少时段闲置,升级后能减少空窗并提升可接任务范围。', 'advisor', '注册 ≤14 天', 'session_script', '/store', 1, 0.000000, 0, 0, '💬', '#7dd3fc', 20, 1),
  ('AS-003', '锁仓', '如果你准备长期持有 NEX,可以先看锁仓档位、释放规则和风险披露,再决定是否进入。', 'advisor', 'P3 阶段活跃', 'session_script', '/staking', 1, 0.000000, 0, 0, '💬', '#7dd3fc', 30, 1),
  ('AS-004', '复投', '当前收益可以拆成设备容量、锁仓和 Genesis 暴露三条线,我可以先给你列一个保守方案。', 'advisor', '近 30 天提现偏高', 'session_script', '/genesis', 1, 0.000000, 0, 0, '💬', '#7dd3fc', 40, 0),
  ('RT-S1', 'support', '收到,我先调出你的账户和最近工单记录核对。', 'reply-template', 'support', 'session_reply_template', 'Session Workbench', 1, 0.000000, 0, 0, '💬', '#7dd3fc', 10, 1),
  ('RT-S2', 'support', '这个问题需要关联账单/设备/风控记录,我会先补充查询再回复你。', 'reply-template', 'support', 'session_reply_template', 'Session Workbench', 1, 0.000000, 0, 0, '💬', '#7dd3fc', 20, 1),
  ('RT-A1', 'advisor', '我看到了你的目标,建议先确认预算、可接受锁定周期和风险偏好。', 'reply-template', 'advisor', 'session_reply_template', 'Session Workbench', 1, 0.000000, 0, 0, '💬', '#7dd3fc', 30, 1)
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  content = VALUES(content),
  category = VALUES(category),
  level = VALUES(level),
  format = VALUES(format),
  surface = VALUES(surface),
  duration_min = VALUES(duration_min),
  reward_nex = VALUES(reward_nex),
  progress_pct = VALUES(progress_pct),
  featured = VALUES(featured),
  emoji = VALUES(emoji),
  tint = VALUES(tint),
  sort_order = VALUES(sort_order),
  status = VALUES(status);

INSERT INTO nx_conversation (id, conversation_no, user_id, conversation_type, status, owner_agent_id, owner_agent_name, unread_count, last_message, last_message_at, created_at, updated_at, is_deleted)
VALUES
  (9101, 'CV-I9-1001', 1001, 'support', 'OPEN', 'agent-1', 'Marina K.', 2, 'My withdrawal has been pending for too long.', DATE_SUB(NOW(), INTERVAL 18 MINUTE), DATE_SUB(NOW(), INTERVAL 2 HOUR), DATE_SUB(NOW(), INTERVAL 18 MINUTE), 0),
  (9102, 'CV-I9-1002', 1002, 'advisor', 'TRANSFERRED', 'agent-2', 'Agent Two', 1, 'I need an earning plan before upgrading hardware.', DATE_SUB(NOW(), INTERVAL 48 MINUTE), DATE_SUB(NOW(), INTERVAL 3 HOUR), DATE_SUB(NOW(), INTERVAL 48 MINUTE), 0),
  (9103, 'CV-I9-1003', 1003, 'support', 'RESOLVED', 'agent-1', 'Marina K.', 0, 'Thanks, this can be closed.', DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_SUB(NOW(), INTERVAL 5 HOUR), DATE_SUB(NOW(), INTERVAL 1 HOUR), 0)
ON DUPLICATE KEY UPDATE
  user_id = VALUES(user_id),
  conversation_type = VALUES(conversation_type),
  status = VALUES(status),
  owner_agent_id = VALUES(owner_agent_id),
  owner_agent_name = VALUES(owner_agent_name),
  unread_count = VALUES(unread_count),
  last_message = VALUES(last_message),
  last_message_at = VALUES(last_message_at),
  updated_at = VALUES(updated_at),
  is_deleted = VALUES(is_deleted);

INSERT INTO nx_conversation_transfer (id, conversation_no, from_agent_id, from_agent_name, to_type, to_id, to_name, reason, status, operator, transferred_at, created_at, updated_at, is_deleted)
VALUES
  (9101, 'CV-I9-1002', 'agent-1', 'Marina K.', 'agent', 'agent-2', 'Agent Two', 'advisor specialist review', 'PENDING', 'Marina K.', DATE_SUB(NOW(), INTERVAL 48 MINUTE), DATE_SUB(NOW(), INTERVAL 48 MINUTE), DATE_SUB(NOW(), INTERVAL 48 MINUTE), 0)
ON DUPLICATE KEY UPDATE
  from_agent_id = VALUES(from_agent_id),
  from_agent_name = VALUES(from_agent_name),
  to_type = VALUES(to_type),
  to_id = VALUES(to_id),
  to_name = VALUES(to_name),
  reason = VALUES(reason),
  status = VALUES(status),
  operator = VALUES(operator),
  transferred_at = VALUES(transferred_at),
  updated_at = VALUES(updated_at),
  is_deleted = VALUES(is_deleted);

INSERT INTO nx_conversation_message (id, conversation_id, conversation_no, sender_id, sender_type, sender_name, content, created_at, updated_at, is_deleted)
VALUES
  (9101, 9101, 'CV-I9-1001', 1001, 'user', 'User 1001', 'My withdrawal has been pending for too long.', DATE_SUB(NOW(), INTERVAL 18 MINUTE), DATE_SUB(NOW(), INTERVAL 18 MINUTE), 0),
  (9102, 9101, 'CV-I9-1001', NULL, 'agent', 'Marina K.', 'I am checking the payment desk and liquidity queue now.', DATE_SUB(NOW(), INTERVAL 12 MINUTE), DATE_SUB(NOW(), INTERVAL 12 MINUTE), 0),
  (9103, 9102, 'CV-I9-1002', 1002, 'user', 'User 1002', 'I need an earning plan before upgrading hardware.', DATE_SUB(NOW(), INTERVAL 48 MINUTE), DATE_SUB(NOW(), INTERVAL 48 MINUTE), 0),
  (9104, 9103, 'CV-I9-1003', 1003, 'user', 'User 1003', 'Thanks, this can be closed.', DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_SUB(NOW(), INTERVAL 1 HOUR), 0)
ON DUPLICATE KEY UPDATE
  conversation_id = VALUES(conversation_id),
  conversation_no = VALUES(conversation_no),
  sender_id = VALUES(sender_id),
  sender_type = VALUES(sender_type),
  sender_name = VALUES(sender_name),
  content = VALUES(content),
  updated_at = VALUES(updated_at),
  is_deleted = VALUES(is_deleted);

INSERT INTO nx_support_sla_rule (category, first_response_mins, resolution_hours, queue, escalation, status)
VALUES
  ('withdrawal', 15, 12, '支付台', 'D2 withdrawal review', 1),
  ('deposit', 20, 12, '充值台', 'D1 deposit reconciliation', 1),
  ('kyc', 30, 24, '合规台', 'C4 KYC ledger', 1),
  ('hardware', 45, 48, '设备运维台', 'E5 device ops', 1),
  ('account', 30, 24, '账户台', 'C5 security', 1),
  ('earnings', 45, 48, '收益台', 'F2 earnings ledger', 1),
  ('genesis', 20, 18, '创世节点台', 'G4 Genesis economy', 1),
  ('technical', 60, 72, '技术支持台', 'A3 system config', 1),
  ('other', 60, 72, '一线客服', 'M2 support lead', 1)
ON DUPLICATE KEY UPDATE
  first_response_mins = VALUES(first_response_mins),
  resolution_hours = VALUES(resolution_hours),
  queue = VALUES(queue),
  escalation = VALUES(escalation),
  status = VALUES(status);

INSERT INTO nx_help_article (article_code, title, content, category, level, format, duration_min, reward_nex, progress_pct, featured, emoji, tint, sort_order, status)
VALUES
  ('team.unilevel', 'How unilevel royalty works', 'Direct and extended network rewards are calculated from team member volume and commission rules configured by operations.', 'help', 'beginner', 'article', 5, 0.000000, 0, 0, '📘', '#c6ff3a', 30, 1),
  ('team.binary', 'How binary matching works', 'Binary matching compares the first two direct legs and estimates rewards from weak-side tracked volume.', 'help', 'beginner', 'article', 5, 0.000000, 0, 0, '📘', '#c6ff3a', 40, 1),
  ('genesis.holder', 'Genesis holder basics', 'Genesis series, orders, and holdings are managed by the commerce service and surfaced in the app from backend records.', 'help', 'beginner', 'article', 5, 0.000000, 0, 0, '📘', '#c6ff3a', 50, 1),
  ('trust.disclosure', 'Reading trust disclosures', 'Trust pages are published CMS records. Updated wording is reviewed before it becomes visible in the app.', 'help', 'beginner', 'article', 5, 0.000000, 0, 0, '📘', '#c6ff3a', 60, 1),
  ('learn.basics.what-is-nexion', 'What is Nexion · The 5-minute crash course', 'Understand how devices, verified compute work, settlement, NEX rewards, and USDT balances fit together in the Nexion operating model.', 'basics', 'beginner', 'video', 5, 20.000000, 100, 1, '🚀', '#c6ff3a', 70, 1),
  ('learn.basics.first-device', 'Your first device · Phone vs NexionBox vs Rack', 'Compare mobile NPU contribution, NexionBox home hardware, and rack capacity so the app can explain device choices from real product records.', 'basics', 'beginner', 'article', 6, 30.000000, 80, 0, '📱', '#8be9ff', 80, 1),
  ('learn.basics.roi-calculator', 'ROI Calculator walkthrough', 'Read estimated return, device cost, lifecycle degradation, and settlement assumptions without treating projections as guaranteed income.', 'basics', 'beginner', 'interactive', 7, 35.000000, 0, 0, '🧮', '#ffd166', 90, 1),
  ('learn.earn.maximize-yield', 'Maximize daily yield · Peak hours + AI Drop alerts', 'Learn how task availability, online windows, and notification settings can raise visible compute yield while backend receipts remain canonical.', 'earn', 'intermediate', 'guide', 8, 45.000000, 45, 0, '⚡', '#8be9ff', 100, 1),
  ('learn.earn.workload-pricing', 'Workload pricing 101 · From SDXL to LLM 70B', 'See why different workload classes carry different reward rates and why the app must show backend task and receipt data instead of local estimates.', 'earn', 'intermediate', 'article', 9, 50.000000, 0, 0, '🧠', '#c9a7ff', 110, 1),
  ('learn.earn.nex-vs-usdt', 'Why your fleet sometimes earns NEX instead of USDT', 'Distinguish fixed USDT settlement from variable NEX rewards and understand which backend ledger records drive each balance.', 'earn', 'beginner', 'video', 6, 30.000000, 0, 0, '💱', '#69e6a7', 120, 1),
  ('learn.team.inviting-friends', 'Inviting friends · How your network keeps paying you', 'Connect referral codes, qualified team volume, and ongoing commission rules to the team dashboard numbers shown in the app.', 'team', 'beginner', 'article', 6, 35.000000, 0, 0, '🤝', '#f4a7ff', 130, 1),
  ('learn.team.balance-match', 'Balance Match explained in 7 minutes', 'Learn how binary matching compares legs, weak-side volume, rank gates, and payout limits before commission rows become claimable.', 'team', 'intermediate', 'video', 7, 55.000000, 0, 0, '🧬', '#c9a7ff', 140, 1),
  ('learn.team.v-rank', 'V-Rank ladder · From V0 Cadet to V12 Singularity', 'Review rank thresholds, team depth, and leadership progression so visible rank numbers come from backend rules and user performance.', 'team', 'advanced', 'guide', 10, 70.000000, 0, 0, '🏆', '#ffcf5a', 150, 1),
  ('learn.wealth.staking-tiers', 'Staking 4-tier · 30d to 365d, when to lock?', 'Compare lock durations, release rules, and reward timing before moving NEX into a locked staking position.', 'wealth', 'intermediate', 'article', 8, 45.000000, 0, 0, '🔒', '#ffd166', 160, 1),
  ('learn.wealth.genesis-node', 'Genesis Node deep-dive · the 1,000-seat permanent share', 'Understand Genesis series capacity, holdings, marketplace transfer, and permanent share economics from commerce service records.', 'wealth', 'advanced', 'video', 12, 90.000000, 0, 0, '💎', '#ffd166', 170, 1),
  ('learn.wealth.reinvest-boost', 'Re-invest Boost · turn $100 into 4 layered rewards', 'Trace how reinvested spend may affect device capacity, staking, referrals, and Genesis exposure while risk disclosures stay visible.', 'wealth', 'advanced', 'interactive', 11, 80.000000, 0, 0, '📈', '#69e6a7', 180, 1),
  ('learn.security.kyc-express', 'KYC-Express · why it triggers and how to clear it', 'Learn which wallet, withdrawal, purchase, and risk-review events can trigger KYC-Express and how to follow the clearance flow.', 'security', 'beginner', 'guide', 5, 25.000000, 0, 0, '🪪', '#ff9f9f', 190, 1),
  ('learn.security.lock-account', 'Lock down your account · 2FA, hardware wallet, anti-phishing', 'Use layered account protection, wallet hygiene, and anti-phishing checks before enabling high-value operations.', 'security', 'beginner', 'article', 6, 30.000000, 0, 0, '🛡', '#ff9f9f', 200, 1),
  ('learn.security.proves-compute', 'How Nexion proves compute · TEE attestations + receipts', 'Follow attestation, receipt, and settlement evidence from compute proof to user-visible earning records.', 'security', 'advanced', 'article', 9, 60.000000, 0, 0, '🔐', '#8be9ff', 210, 1)
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  content = VALUES(content),
  category = VALUES(category),
  level = VALUES(level),
  format = VALUES(format),
  duration_min = VALUES(duration_min),
  reward_nex = VALUES(reward_nex),
  progress_pct = VALUES(progress_pct),
  featured = VALUES(featured),
  emoji = VALUES(emoji),
  tint = VALUES(tint),
  sort_order = VALUES(sort_order),
  status = VALUES(status);

INSERT INTO nx_content_copy (copy_key, description, surface, current_version, status, i18n_key, experiment_id, last_change, draft_version, draft_zh, draft_en, draft_surface, draft_audience, draft_traffic_split, draft_note, last_operator)
VALUES
  ('home.conversionBanner', '主转化横幅 · 激活设备每日收益话术', 'Home', 'v7', 'published', 'marketing.home.convBanner', 'EXP-2611', '05-28', 'v8', '完成 {amount} USDT 复投并获得 {nex} NEX 奖励', 'Reinvest {amount} USDT and earn {nex} NEX', 'Home', 'P3 · 全语言', '50', '草稿:复投奖励强调', 'seed'),
  ('home.missedIncome', '错过收益条 · 与基准机型的日产差额', 'Home', 'v4', 'published', 'marketing.home.missed', 'EXP-2612', '05-30', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'seed'),
  ('home.heroCta', '首屏主按钮文案', 'Home', 'v9', 'published', 'marketing.home.heroCta', NULL, '05-21', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'seed'),
  ('home.trialNudge', '试用引导条(试用资格用户可见)', 'Home', 'v3', 'published', 'marketing.home.trial', NULL, '04-30', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'seed'),
  ('home.paybackChip', '回本周期角标', 'Home', 'v2', 'published', 'marketing.home.payback', NULL, '04-02', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'seed'),
  ('me.upgradeCard', '「该升级了」卡片 · 按机队推荐', 'Me', 'v5', 'published', 'marketing.me.upgrade', 'EXP-2613', '06-02', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'seed'),
  ('me.referralNudge', '邀请返佣提示条', 'Me', 'v6', 'published', 'marketing.me.referral', NULL, '05-11', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'seed'),
  ('me.vrankProgress', 'V 等级进度话术', 'Me', 'v2', 'published', 'marketing.me.vrank', NULL, '03-19', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'seed'),
  ('me.walletEmpty', '钱包空态引导', 'Me', 'v3', 'published', 'marketing.me.walletEmpty', NULL, '02-27', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'seed'),
  ('store.paybackHint', '商品卡回本提示', '商城', 'v4', 'published', 'marketing.store.payback', NULL, '05-06', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'seed'),
  ('store.bundleBanner', '捆绑购横幅', '商城', 'v3', 'published', 'marketing.store.bundle', NULL, '04-22', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'seed'),
  ('store.tradeinHook', '以旧换新钩子文案', '商城', 'v2', 'published', 'marketing.store.tradein', NULL, '03-30', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'seed')
ON DUPLICATE KEY UPDATE
  description = VALUES(description),
  surface = VALUES(surface),
  current_version = VALUES(current_version),
  status = VALUES(status),
  i18n_key = VALUES(i18n_key),
  experiment_id = VALUES(experiment_id),
  last_change = VALUES(last_change),
  draft_version = VALUES(draft_version),
  draft_zh = VALUES(draft_zh),
  draft_en = VALUES(draft_en),
  draft_surface = VALUES(draft_surface),
  draft_audience = VALUES(draft_audience),
  draft_traffic_split = VALUES(draft_traffic_split),
  draft_note = VALUES(draft_note),
  last_operator = VALUES(last_operator);

INSERT INTO nx_content_copy_version (copy_key, version, status, chain, ts_label, zh_text, en_text, surface, audience, traffic_split, version_note, last_operator)
VALUES
  ('home.conversionBanner', 'v8', 'draft', 'li.wen / -', '06-10 18:22', '完成 {amount} USDT 复投并获得 {nex} NEX 奖励', 'Reinvest {amount} USDT and earn {nex} NEX', 'Home', 'P3 · 全语言', '50', '草稿:复投奖励强调', 'seed'),
  ('home.conversionBanner', 'v7', 'published', 'li.wen / chen.r(lead)', '05-28 11:04', '激活 {targetName},每天赚 ${targetDaily},约 {paybackDays} 天回本,收益是 {lowestName} 的 {multiplier} 倍', 'Activate {targetName} · earn ${targetDaily}/day · payback ~{paybackDays} days · {multiplier}x {lowestName}', 'Home', 'P3 · 全语言', '50', '现行发布版', 'seed'),
  ('home.conversionBanner', 'v6', 'archived', 'zhao.m / chen.r(lead)', '04-12 09:40', '激活 {targetName},每日预计收益 ${targetDaily}', 'Activate {targetName} for estimated ${targetDaily}/day', 'Home', 'P2-P3', '50', '归档版', 'seed'),
  ('home.conversionBanner', 'v5', 'archived', 'zhao.m / superadmin', '03-02 15:17', '升级到 {targetName},解锁更高算力', 'Upgrade to {targetName} and unlock higher compute', 'Home', '全量', '50', '归档版', 'seed')
ON DUPLICATE KEY UPDATE
  status = VALUES(status),
  chain = VALUES(chain),
  ts_label = VALUES(ts_label),
  zh_text = VALUES(zh_text),
  en_text = VALUES(en_text),
  surface = VALUES(surface),
  audience = VALUES(audience),
  traffic_split = VALUES(traffic_split),
  version_note = VALUES(version_note),
  last_operator = VALUES(last_operator);

INSERT INTO nx_content_experiment_framework (param_key, param_name, current_value, description, sort_order, last_operator)
VALUES
  ('split', '分流比例默认', '变体等分', '新实验默认变体等分;可改成任意组合,但合计必须 = 100%,超了服务器拒', 10, 'seed'),
  ('aud', '受众定向默认', '全量', '默认全量;可按注册周 / 运营阶段 / 语言圈定,只对圈定后新进入的用户生效', 20, 'seed'),
  ('sample', '最小样本量', '5,000 / 变体', '每个变体曝光不够这个数就出结论,统计上不可信', 30, 'seed'),
  ('maxrun', '最长运行期', '90 天', '到期未手动结算自动转已结,防止僵尸实验长期分裂用户体验', 40, 'seed')
ON DUPLICATE KEY UPDATE
  param_name = VALUES(param_name),
  current_value = VALUES(current_value),
  description = VALUES(description),
  sort_order = VALUES(sort_order),
  last_operator = VALUES(last_operator);

INSERT INTO nx_content_experiment (experiment_id, copy_key, audience, impressions_label, conversions_label, state, note, last_operator)
VALUES
  ('EXP-2611', 'home.conversionBanner', 'P3 · 全语言', '412K', '18.3K', 'running', '05-29 起 · 已 13 天', 'seed'),
  ('EXP-2612', 'home.missedIncome', '全量', '388K', '13.6K', 'running', '05-30 起 · 已 12 天', 'seed'),
  ('EXP-2613', 'me.upgradeCard', 'zh · 注册>30天', '96K', '2.2K', 'running', '06-02 起 · 样本不足', 'seed'),
  ('EXP-2607', 'home.conversionBanner', 'P2-P3', '1.02M', '37.8K', 'adopted', '已结 · B 胜出已采纳为 v7', 'seed'),
  ('EXP-2598', 'store.paybackHint', '全量', '640K', '17.6K', 'discarded', '已结 · 无显著差异,弃用', 'seed')
ON DUPLICATE KEY UPDATE
  copy_key = VALUES(copy_key),
  audience = VALUES(audience),
  impressions_label = VALUES(impressions_label),
  conversions_label = VALUES(conversions_label),
  state = VALUES(state),
  note = VALUES(note),
  last_operator = VALUES(last_operator);

INSERT INTO nx_content_experiment_variant (experiment_id, variant_name, split_pct, cvr_pct, sort_order)
VALUES
  ('EXP-2611', 'A(v7 现版)', 50, 4.10, 10),
  ('EXP-2611', 'B(v8 候选)', 50, 4.80, 20),
  ('EXP-2612', 'A(差额话术)', 34, 3.20, 10),
  ('EXP-2612', 'B(倍数话术)', 33, 3.90, 20),
  ('EXP-2612', 'C(回本话术)', 33, 3.40, 30),
  ('EXP-2613', 'A(现版)', 50, 2.10, 10),
  ('EXP-2613', 'B(机队对比)', 50, 2.60, 20),
  ('EXP-2607', 'A(v6)', 50, 3.40, 10),
  ('EXP-2607', 'B(v7)', 50, 4.00, 20),
  ('EXP-2598', 'A', 50, 2.80, 10),
  ('EXP-2598', 'B', 50, 2.70, 20)
ON DUPLICATE KEY UPDATE
  split_pct = VALUES(split_pct),
  cvr_pct = VALUES(cvr_pct),
  sort_order = VALUES(sort_order);

INSERT INTO nx_trust_section (section_key, description, struct_text, version_label, status, role_gate, high_sensitivity, last_change, sort_order, last_operator)
VALUES
  ('financials', '财务透明数字组', '数字组 + 脚注', 'v5', 'published', '合规 / 超管', 1, '05-12', 10, 'seed'),
  ('leadership', '管理团队', '人员卡 ×5(姓名/职务/前公司/占位链接)', 'v3', 'published', '内容主管', 0, '03-08', 20, 'seed'),
  ('nexNarrative', 'NEX 代币叙事', '叙事文案 + 行情 stats + top3 客户榜', 'v6', 'published', '合规 / 超管', 1, '05-26', 30, 'seed'),
  ('complianceBadges', '合规徽章', '徽章组(SOC2 / ISO27001 / CertiK)', 'v2', 'published', '合规 / 超管', 1, '02-14', 40, 'seed'),
  ('auditsReserves', '审计与储备证明', '储备证明与审计报告外部引用由媒体/文档资产托管', 'v4', 'published', '合规 / 超管', 1, '04-20', 50, 'seed'),
  ('listings', '交易所与行情外链', '交易所与行情页引用由资产表托管', 'v2', 'published', '内容主管', 0, '01-30', 60, 'seed')
ON DUPLICATE KEY UPDATE
  description = VALUES(description),
  struct_text = VALUES(struct_text),
  version_label = VALUES(version_label),
  status = VALUES(status),
  role_gate = VALUES(role_gate),
  high_sensitivity = VALUES(high_sensitivity),
  last_change = VALUES(last_change),
  sort_order = VALUES(sort_order),
  last_operator = VALUES(last_operator);

INSERT INTO nx_trust_section_field (section_key, field_key, field_value, field_delta, sort_order, last_operator)
VALUES
  ('financials', 'MRR', '$4.87M', '+22%', 10, 'seed'),
  ('financials', 'Active', '184,206', '+38%', 20, 'seed'),
  ('financials', 'Devices', '28,432', '+12%', 30, 'seed'),
  ('financials', 'Payouts', '$31.2M', '+27%', 40, 'seed'),
  ('leadership', '成员数', '5 行高管卡', NULL, 10, 'seed'),
  ('leadership', '字段', '姓名 / 职务 / 前公司 / LinkedIn 资料引用', NULL, 20, 'seed'),
  ('leadership', '示例', 'CEO / ex-AWS / profile asset key', NULL, 30, 'seed'),
  ('nexNarrative', '行情 stats', '24h 量 / 市值 / 流通量实时部分走行情源', NULL, 10, 'seed'),
  ('nexNarrative', '客户榜', '前 3 大 AI 客户 NEX 消费', NULL, 20, 'seed'),
  ('nexNarrative', '叙事', '30% 收入回购销毁', NULL, 30, 'seed'),
  ('nexNarrative', '口径对齐', '市值/流通量须与金融产品域(G)一致', NULL, 40, 'seed'),
  ('complianceBadges', '徽章', 'SOC2 · ISO27001 · CertiK', NULL, 10, 'seed'),
  ('complianceBadges', '属性', '对外合规声明需要合规或超管执行', NULL, 20, 'seed'),
  ('auditsReserves', '资产', '链上储备证明 / 审计报告文档资产', NULL, 10, 'seed'),
  ('listings', '资产', 'PancakeSwap 等行情页资产引用', NULL, 10, 'seed')
ON DUPLICATE KEY UPDATE
  field_value = VALUES(field_value),
  field_delta = VALUES(field_delta),
  sort_order = VALUES(sort_order),
  last_operator = VALUES(last_operator);

INSERT INTO nx_disclosure_jurisdiction (jurisdiction_code, jurisdiction_name, version_label, status, published_at_label, affected_count, ack_progress_pct, blocked_count, last_operator)
VALUES
  ('MAS', '新加坡', 'v11', 'published', '05-02', 41200, 100.00, 0, 'seed'),
  ('BaFin', '德国', 'v11', 'published', '05-02', 18600, 99.70, 4, 'seed'),
  ('FINCEN', '美国', 'v10', 'published', '03-18', 52800, 100.00, 0, 'seed'),
  ('SFC', '香港', 'v12', 'published', '06-08', 9400, 72.00, 312, 'seed')
ON DUPLICATE KEY UPDATE
  jurisdiction_name = VALUES(jurisdiction_name),
  version_label = VALUES(version_label),
  status = VALUES(status),
  published_at_label = VALUES(published_at_label),
  affected_count = VALUES(affected_count),
  ack_progress_pct = VALUES(ack_progress_pct),
  blocked_count = VALUES(blocked_count),
  last_operator = VALUES(last_operator);

INSERT INTO nx_disclosure_chapter (jurisdiction_code, version_label, chapter_no, zh_title, en_title, zh_body, en_body, sort_order, last_operator)
VALUES
  ('SFC', 'v12', '01', '收益预估不构成承诺', 'Earnings estimates are not guarantees', '本章节为受管合规文案。所有收益数字均为基于历史网络数据的估算,不构成对未来收益的承诺;实际产出受全网算力、设备状态与市场价格影响。', 'This section is managed compliance copy. Earnings figures are estimates from historical network data and are not promises of future income. Actual output depends on network compute, device state, and market price.', 10, 'seed'),
  ('SFC', 'v12', '02', '硬件衰减与产量波动', 'Hardware decay and output variance', '设备生命周期、在线率、维修和网络任务分布都会影响产量。后台展示必须以服务器账本和设备状态为准。', 'Device lifecycle, uptime, maintenance, and network task distribution can affect output. Admin and app views must use server ledger and device state as the source of truth.', 20, 'seed'),
  ('SFC', 'v12', '03', 'NEX 市场风险', 'NEX market risk', 'NEX 价格存在市场波动风险。NEX 奖励、回购叙事和行情展示不构成投资建议。', 'NEX price is subject to market volatility. NEX rewards, buyback narratives, and market displays are not investment advice.', 30, 'seed'),
  ('SFC', 'v12', '04', '提现窗口与合规审查', 'Withdrawal windows and compliance review', '提现可能因合规审查、流动性排队、链上拥堵或 KYC 复核而延迟。', 'Withdrawals may be delayed by compliance review, liquidity queue, chain congestion, or KYC re-check.', 40, 'seed'),
  ('SFC', 'v12', '05', '质押不可撤销', 'Staking is irrevocable', '质押锁仓在对应周期内不可随意撤销,提前退出可能产生罚款或收益损失。', 'Locked staking cannot be freely revoked during its term. Early exit can incur penalties or reward loss.', 50, 'seed'),
  ('SFC', 'v12', '06', '网络经济与推荐激励', 'Network economy and referral incentives', '推荐、团队与网络经济收益受规则、资格、反作弊和账本确认影响。', 'Referral, team, and network economy rewards depend on rules, eligibility, anti-abuse checks, and ledger confirmation.', 60, 'seed'),
  ('SFC', 'v12', '07', '托管、KYC 与监管管辖', 'Custody, KYC and regulatory jurisdiction', '用户辖区由 IP、KYC 和风险判定共同决定。变更条款后,用户必须重新确认才能继续受限动作。', 'User jurisdiction is determined by IP, KYC, and risk signals. After terms change, users must re-acknowledge before restricted actions continue.', 70, 'seed')
ON DUPLICATE KEY UPDATE
  zh_title = VALUES(zh_title),
  en_title = VALUES(en_title),
  zh_body = VALUES(zh_body),
  en_body = VALUES(en_body),
  sort_order = VALUES(sort_order),
  last_operator = VALUES(last_operator);

INSERT INTO nx_disclosure_gate_action (action_key, action_name, description, status_label, tone, active, sort_order, last_operator)
VALUES
  ('withdraw', '提现', '提交提现前服务器先验披露确认', '已实装', 'ok', 1, 10, 'seed'),
  ('staking', '质押锁仓', '确认状态过期时拦截质押入口', '已实装', 'warn', 0, 20, 'seed'),
  ('nexv2', 'NEX v2 历史锁仓', '已下线历史兼容项,只读展示,不允许重新启用', '已下线 · 历史兼容', 'dim', 0, 30, 'seed')
ON DUPLICATE KEY UPDATE
  action_name = VALUES(action_name),
  description = VALUES(description),
  status_label = VALUES(status_label),
  tone = VALUES(tone),
  active = IF(action_key = 'nexv2', 0, VALUES(active)),
  sort_order = VALUES(sort_order),
  last_operator = VALUES(last_operator);

INSERT INTO nx_disclosure_draft (jurisdiction_code, version_label, language_scope, effective_date, requires_reack, zh_body, en_body, status, last_operator)
VALUES
  ('SFC', 'v13', 'en+zh', '2026-06-30', 1, 'SFC v13 草稿。收益估算不构成承诺,用户在下次提现前需要重新确认 {version}。', 'SFC v13 draft. Earnings estimates are not guarantees and users must re-acknowledge {version} before the next withdrawal.', 'DRAFT', 'seed')
ON DUPLICATE KEY UPDATE
  language_scope = VALUES(language_scope),
  effective_date = VALUES(effective_date),
  requires_reack = VALUES(requires_reack),
  zh_body = VALUES(zh_body),
  en_body = VALUES(en_body),
  status = VALUES(status),
  last_operator = VALUES(last_operator);

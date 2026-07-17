-- J3 跳转 C2/K1 后可按业务权限创建 A2 待确认操作；创建权不等于审批或执行权。

INSERT INTO nx_admin_permission
  (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted)
VALUES
  ('platform_a2_proposal_create', 'A2 按业务权限创建待确认操作', 'API', '/platform/audit', 'WRITE', 0, 1, 0)
ON DUPLICATE KEY UPDATE
  permission_name = VALUES(permission_name),
  resource_type = VALUES(resource_type),
  resource_path = VALUES(resource_path),
  perm_type = VALUES(perm_type),
  amplifies = VALUES(amplifies),
  status = 1,
  is_deleted = 0;

INSERT INTO nx_admin_permission
  (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted)
VALUES
  ('user_c2_impersonate_start', 'C2 发起只读模拟登录', 'API', '/users/actions', 'HIGH', 0, 1, 0)
ON DUPLICATE KEY UPDATE
  permission_name = VALUES(permission_name),
  resource_type = VALUES(resource_type),
  resource_path = VALUES(resource_path),
  perm_type = VALUES(perm_type),
  amplifies = VALUES(amplifies),
  status = 1,
  is_deleted = 0;

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT role_row.id, permission_row.id
  FROM nx_admin_role role_row
  JOIN nx_admin_permission permission_row
    ON permission_row.permission_code = 'user_c2_impersonate_start'
 WHERE role_row.role_code IN ('SUPER_ADMIN', 'RISK')
   AND role_row.status = 1 AND role_row.is_deleted = 0
   AND permission_row.status = 1 AND permission_row.is_deleted = 0;

UPDATE nx_admin_role_permission role_permission
JOIN nx_admin_role role_row ON role_row.id = role_permission.role_id
JOIN nx_admin_permission permission_row ON permission_row.id = role_permission.permission_id
   SET role_permission.is_deleted = 0
 WHERE role_row.role_code IN ('SUPER_ADMIN', 'RISK')
   AND permission_row.permission_code = 'user_c2_impersonate_start';

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT role_row.id, permission_row.id
  FROM nx_admin_role role_row
  JOIN nx_admin_permission permission_row
    ON permission_row.permission_code IN ('platform_a2_read', 'platform_a2_proposal_create')
 WHERE role_row.role_code IN ('SUPER_ADMIN', 'RISK')
   AND role_row.status = 1 AND role_row.is_deleted = 0
   AND permission_row.status = 1 AND permission_row.is_deleted = 0;

UPDATE nx_admin_role_permission role_permission
JOIN nx_admin_role role_row ON role_row.id = role_permission.role_id
JOIN nx_admin_permission permission_row ON permission_row.id = role_permission.permission_id
   SET role_permission.is_deleted = 0
 WHERE role_row.role_code IN ('SUPER_ADMIN', 'RISK')
   AND permission_row.permission_code IN ('platform_a2_read', 'platform_a2_proposal_create');

DELETE role_permission
  FROM nx_admin_role_permission role_permission
  JOIN nx_admin_role role_row ON role_row.id = role_permission.role_id
  JOIN nx_admin_permission permission_row ON permission_row.id = role_permission.permission_id
 WHERE permission_row.permission_code = 'platform_a2_proposal_create'
   AND role_row.role_code NOT IN ('SUPER_ADMIN', 'RISK');

-- 委托提案人与审批执行人必须分离；清理历史环境中 RISK 可能残留的 A2 写/审批权。
DELETE role_permission
  FROM nx_admin_role_permission role_permission
  JOIN nx_admin_role role_row ON role_row.id = role_permission.role_id
  JOIN nx_admin_permission permission_row ON permission_row.id = role_permission.permission_id
 WHERE role_row.role_code = 'RISK'
   AND permission_row.permission_code IN ('platform_a2_write', 'platform_a2_operation_approve');

-- RISK 发起提案后必须能从菜单进入 A2 只读跟踪；只补父级 A 与 A2，不开放其他平台基础页面。
INSERT IGNORE INTO nx_admin_role_menu (role_id, menu_id)
SELECT role_row.id, menu_row.id
  FROM nx_admin_role role_row
  JOIN nx_admin_menu menu_row ON menu_row.menu_code IN ('A', 'A2')
 WHERE role_row.role_code = 'RISK'
   AND role_row.status = 1 AND role_row.is_deleted = 0
   AND menu_row.status = 1 AND menu_row.is_deleted = 0;

UPDATE nx_admin_role_menu role_menu
JOIN nx_admin_role role_row ON role_row.id = role_menu.role_id
JOIN nx_admin_menu menu_row ON menu_row.id = role_menu.menu_id
   SET role_menu.is_deleted = 0
 WHERE role_row.role_code = 'RISK'
   AND menu_row.menu_code IN ('A', 'A2');

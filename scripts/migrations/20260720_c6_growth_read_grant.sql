-- C6 least-privilege repair: Growth may open the C6 page and read its overview.
-- The role intentionally receives no C6 mutation permission.
INSERT INTO nx_admin_permission (
  permission_code, permission_name, resource_type, resource_path,
  perm_type, amplifies, status, is_deleted
)
VALUES ('user_c6_read', 'C6 注册/登录风控-页面读', 'API', '/users/reg-risk', 'READ', 0, 1, 0)
ON DUPLICATE KEY UPDATE
  permission_name=VALUES(permission_name),
  resource_type=VALUES(resource_type),
  resource_path=VALUES(resource_path),
  perm_type=VALUES(perm_type),
  amplifies=VALUES(amplifies),
  status=1,
  is_deleted=0,
  updated_at=NOW();

INSERT INTO nx_admin_role_permission (role_id, permission_id, is_deleted)
SELECT r.id, p.id, 0
  FROM nx_admin_role r
  JOIN nx_admin_permission p ON p.permission_code='user_c6_read'
 WHERE r.role_code='GROWTH'
   AND r.status=1 AND r.is_deleted=0
   AND p.status=1 AND p.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0, updated_at=NOW();

INSERT INTO nx_admin_role_menu (role_id, menu_id, is_deleted)
SELECT r.id, m.id, 0
  FROM nx_admin_role r
  JOIN nx_admin_menu m ON m.menu_code IN ('C','C1','C6')
 WHERE r.role_code='GROWTH'
   AND r.status=1 AND r.is_deleted=0
   AND m.status=1 AND m.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0, updated_at=NOW();

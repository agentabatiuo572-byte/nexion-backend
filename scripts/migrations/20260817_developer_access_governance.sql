-- P1 developer-access governance: durable command idempotency plus the complete
-- A9 menu/button/RBAC loop. The migration runner must execute this file after
-- 20260813_developer_access_request.sql and the classic RBAC tables exist.

CREATE TABLE IF NOT EXISTS nx_developer_access_review_idempotency (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  request_no VARCHAR(32) NOT NULL,
  action VARCHAR(16) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  result_status VARCHAR(32) NULL,
  result_reviewer VARCHAR(128) NULL,
  result_reason VARCHAR(500) NULL,
  result_reviewed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_developer_access_review_idempotency (request_no, idempotency_key),
  KEY idx_developer_access_review_idempotency_request (request_no, action),
  CONSTRAINT ck_developer_access_review_idempotency_status
    CHECK (status IN ('PENDING','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- A9 is a child of the existing platform/RBAC domain. Permissions below are
-- global-scope grants: the server controller enforces these exact authorities.
INSERT INTO nx_admin_menu
  (menu_code, menu_name, menu_name_zh, parent_id, route_path, icon, sort_order, remark, status, is_deleted)
SELECT 'A9', '开发者访问审批', '开发者访问审批', parent.id, '/platform/developer-access',
       'key-round', 9, '全局 developer access 请求治理；服务端权限强制', 1, 0
  FROM nx_admin_menu parent
 WHERE parent.menu_code='A'
ON DUPLICATE KEY UPDATE
  menu_name=VALUES(menu_name), menu_name_zh=VALUES(menu_name_zh), parent_id=VALUES(parent_id),
  route_path=VALUES(route_path), icon=VALUES(icon), sort_order=VALUES(sort_order),
  remark=VALUES(remark), status=1, is_deleted=0, updated_at=NOW();

INSERT INTO nx_admin_permission
  (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted)
VALUES
  ('developer_access_read', '开发者访问审批-列表查看（全局）', 'API', '/developer/access-requests', 'READ', 0, 1, 0),
  ('developer_access_approve', '开发者访问审批-批准（全局高敏）', 'API', '/developer/access-requests/*/approve', 'HIGH', 1, 1, 0),
  ('developer_access_reject', '开发者访问审批-拒绝（全局高敏）', 'API', '/developer/access-requests/*/reject', 'HIGH', 1, 1, 0),
  ('developer_access_revoke', '开发者访问审批-撤销（全局高敏）', 'API', '/developer/access-requests/*/revoke', 'HIGH', 1, 1, 0)
ON DUPLICATE KEY UPDATE
  permission_name=VALUES(permission_name), resource_path=VALUES(resource_path),
  perm_type=VALUES(perm_type), amplifies=VALUES(amplifies), status=1, is_deleted=0, updated_at=NOW();

UPDATE nx_admin_permission permission
JOIN nx_admin_menu menu ON menu.menu_code='A9' AND menu.is_deleted=0
   SET permission.menu_id=menu.id, permission.updated_at=NOW()
 WHERE permission.permission_code LIKE 'developer_access_%';

INSERT INTO nx_admin_role
  (role_code, role_name, remark, status, is_deleted)
VALUES ('DEVELOPER_ACCESS_ADMIN', '开发者访问治理管理员', '仅开发者访问审批全局权限', 1, 0)
ON DUPLICATE KEY UPDATE role_name=VALUES(role_name), remark=VALUES(remark), status=1, is_deleted=0, updated_at=NOW();

-- Super-admin retains explicit grants for authority projection; the dedicated
-- role is least-privilege and can be assigned without exposing other A-domain APIs.
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id, is_deleted)
SELECT role.id, permission.id, 0
  FROM nx_admin_role role
  JOIN nx_admin_permission permission
    ON permission.permission_code LIKE 'developer_access_%'
   AND permission.status=1 AND permission.is_deleted=0
 WHERE role.role_code IN ('SUPER_ADMIN','DEVELOPER_ACCESS_ADMIN');

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id, is_deleted)
SELECT role.id, permission.id, 0
  FROM nx_admin_role role
  JOIN nx_admin_permission permission ON permission.permission_code='developer_access_read'
 WHERE role.role_code='AUDITOR' AND permission.status=1 AND permission.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_menu (role_id, menu_id, is_deleted)
SELECT role.id, menu.id, 0
  FROM nx_admin_role role
  JOIN nx_admin_menu menu ON menu.menu_code='A9' AND menu.status=1 AND menu.is_deleted=0
 WHERE role.role_code IN ('SUPER_ADMIN','DEVELOPER_ACCESS_ADMIN','AUDITOR');

SELECT IF(
  (SELECT COUNT(*) FROM nx_admin_permission
    WHERE permission_code IN ('developer_access_read','developer_access_approve',
                              'developer_access_reject','developer_access_revoke')
      AND menu_id=(SELECT id FROM nx_admin_menu WHERE menu_code='A9' AND is_deleted=0 LIMIT 1)
      AND status=1 AND is_deleted=0)=4
  AND (SELECT COUNT(*) FROM nx_admin_role_permission rp
    JOIN nx_admin_role r ON r.id=rp.role_id AND r.role_code='DEVELOPER_ACCESS_ADMIN'
    JOIN nx_admin_permission p ON p.id=rp.permission_id AND p.permission_code LIKE 'developer_access_%'
    WHERE rp.is_deleted=0)=4,
  'DEVELOPER_ACCESS_GOVERNANCE_OK', 'DEVELOPER_ACCESS_GOVERNANCE_INCOMPLETE') AS migration_status;

-- B4 H1-canonical phase overview exact RBAC.
SET NAMES utf8mb4;

INSERT INTO nx_admin_permission
  (permission_code,permission_name,resource_type,resource_path,perm_type,amplifies,status,is_deleted)
VALUES
  ('overview_b4_read','B4 节奏状态-页面读','API','/overview/rhythm','READ',0,1,0),
  ('overview_b4_jump','B4 节奏状态-跳 H1','API','/overview/rhythm','WRITE',0,1,0),
  ('overview_b4_export','B4 节奏状态-Phase 分布导出','API','/overview/rhythm','WRITE',0,1,0)
ON DUPLICATE KEY UPDATE
  permission_name=VALUES(permission_name),
  resource_path=VALUES(resource_path),
  perm_type=VALUES(perm_type),
  status=1,
  is_deleted=0;

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code IN ('overview_b4_read','overview_b4_jump','overview_b4_export')
  AND r.role_code NOT IN ('SUPER_ADMIN','GROWTH','FINANCE','FINANCE_LEAD','RISK','AUDITOR');

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code='overview_b4_jump'
  AND r.role_code NOT IN ('SUPER_ADMIN','GROWTH');

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code='overview_b4_export'
  AND r.role_code NOT IN ('SUPER_ADMIN','GROWTH','FINANCE','FINANCE_LEAD','AUDITOR');

INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','GROWTH','FINANCE','FINANCE_LEAD','RISK','AUDITOR')
  AND p.permission_code='overview_b4_read'
  AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','GROWTH')
  AND p.permission_code='overview_b4_jump'
  AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','GROWTH','FINANCE','FINANCE_LEAD','AUDITOR')
  AND p.permission_code='overview_b4_export'
  AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

-- B5 risk radar exact RBAC and role matrix.
SET NAMES utf8mb4;

INSERT INTO nx_admin_permission
  (permission_code,permission_name,resource_type,resource_path,perm_type,amplifies,status,is_deleted)
VALUES
  ('overview_b5_read','B5 风险雷达-页面读','API','/overview/risk-radar','READ',0,1,0),
  ('overview_b5_triage','B5 风险雷达-分诊跳转','API','/overview/risk-radar','WRITE',0,1,0),
  ('overview_b5_subscribe','B5 风险雷达-告警订阅','API','/overview/risk-radar','WRITE',0,1,0),
  ('overview_b5_threshold_write','B5 挤兑阈值配置','API','/overview/risk-radar','HIGH',1,1,0)
ON DUPLICATE KEY UPDATE
  permission_name=VALUES(permission_name),
  resource_path=VALUES(resource_path),
  perm_type=VALUES(perm_type),
  amplifies=VALUES(amplifies),
  status=1,
  is_deleted=0;

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code='overview_b5_read'
  AND r.role_code NOT IN ('SUPER_ADMIN','RISK','FINANCE','FINANCE_LEAD','GROWTH','AUDITOR');

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code='overview_b5_triage'
  AND r.role_code NOT IN ('SUPER_ADMIN','RISK','RISK_LEAD','FINANCE','FINANCE_LEAD');

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code='overview_b5_subscribe'
  AND r.role_code NOT IN ('SUPER_ADMIN','RISK','RISK_LEAD','FINANCE','FINANCE_LEAD');

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code='overview_b5_threshold_write'
  AND r.role_code NOT IN ('SUPER_ADMIN','RISK_LEAD');

INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','RISK','FINANCE','FINANCE_LEAD','GROWTH','AUDITOR')
  AND p.permission_code='overview_b5_read'
  AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','RISK','RISK_LEAD','FINANCE','FINANCE_LEAD')
  AND p.permission_code='overview_b5_triage'
  AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','RISK','RISK_LEAD','FINANCE','FINANCE_LEAD')
  AND p.permission_code='overview_b5_subscribe'
  AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','RISK_LEAD')
  AND p.permission_code='overview_b5_threshold_write'
  AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

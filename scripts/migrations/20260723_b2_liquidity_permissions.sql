-- B2 authoritative liquidity dashboard permissions.
-- Read is intentionally separate from D3; write/export remain least-privilege.

INSERT INTO nx_admin_permission
  (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted)
VALUES
  ('overview_b2_write','B2 到期预测口径调整','API','/overview/liquidity','WRITE',0,1,0),
  ('overview_b2_export','B2 应付负债明细导出','API','/overview/liquidity','READ',0,1,0)
ON DUPLICATE KEY UPDATE
  permission_name=VALUES(permission_name),
  resource_type='API',
  resource_path='/overview/liquidity',
  perm_type=VALUES(perm_type),
  amplifies=0,
  status=1,
  is_deleted=0,
  updated_at=NOW();

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE r.role_code <> 'SUPER_ADMIN' AND p.permission_code LIKE 'overview_b2_%';

INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','GROWTH','AUDITOR')
  AND p.permission_code='overview_b2_read'
  AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR')
  AND p.permission_code='overview_b2_export'
  AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE_LEAD')
  AND p.permission_code='overview_b2_write'
  AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

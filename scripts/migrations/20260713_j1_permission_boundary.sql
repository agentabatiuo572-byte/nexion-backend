-- J1 权限边界校正（PRD v4 §J1⑥）
-- 超管：全部；风控：读/单闸熔断/批量熔断；财务：读/单闸熔断；审计：只读。

UPDATE nx_admin_role_permission relation
JOIN nx_admin_role role ON role.id = relation.role_id
JOIN nx_admin_permission permission ON permission.id = relation.permission_id
SET relation.is_deleted = 1, relation.updated_at = NOW()
WHERE permission.permission_code LIKE 'emergency_j1_%'
  AND role.role_code IN ('RISK', 'FINANCE', 'AUDITOR')
  AND relation.is_deleted = 0;

INSERT INTO nx_admin_role_permission (role_id, permission_id, created_at, updated_at, is_deleted)
SELECT role.id, permission.id, NOW(), NOW(), 0
FROM nx_admin_role role
JOIN nx_admin_permission permission
  ON permission.permission_code IN (
    'emergency_j1_read',
    'emergency_j1_gate_kill',
    'emergency_j1_batch_kill'
  )
WHERE role.role_code = 'RISK'
  AND role.is_deleted = 0
  AND permission.status = 1
  AND permission.is_deleted = 0
ON DUPLICATE KEY UPDATE is_deleted = 0, updated_at = NOW();

INSERT INTO nx_admin_role_permission (role_id, permission_id, created_at, updated_at, is_deleted)
SELECT role.id, permission.id, NOW(), NOW(), 0
FROM nx_admin_role role
JOIN nx_admin_permission permission
  ON permission.permission_code IN ('emergency_j1_read', 'emergency_j1_gate_kill')
WHERE role.role_code = 'FINANCE'
  AND role.is_deleted = 0
  AND permission.status = 1
  AND permission.is_deleted = 0
ON DUPLICATE KEY UPDATE is_deleted = 0, updated_at = NOW();

INSERT INTO nx_admin_role_permission (role_id, permission_id, created_at, updated_at, is_deleted)
SELECT role.id, permission.id, NOW(), NOW(), 0
FROM nx_admin_role role
JOIN nx_admin_permission permission ON permission.permission_code = 'emergency_j1_read'
WHERE role.role_code = 'AUDITOR'
  AND role.is_deleted = 0
  AND permission.status = 1
  AND permission.is_deleted = 0
ON DUPLICATE KEY UPDATE is_deleted = 0, updated_at = NOW();

-- SUPER_ADMIN 仍由全权限基线持有 J1 的读、写、熔断、恢复和批量熔断权限。

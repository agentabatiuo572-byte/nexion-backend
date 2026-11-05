-- BE-21: only an active SUPER_ADMIN role receives overview snapshot export recording.
-- Keep every other permission and role binding unchanged; safe to rerun after classic seeds.
INSERT INTO nx_admin_permission
  (permission_code,permission_name,resource_type,resource_path,perm_type,amplifies,status,is_deleted)
VALUES
  ('overview_b0_export','今日概览 JSON 快照导出留痕','API','/api/admin/ops-dashboard/exports','READ',0,1,0)
ON DUPLICATE KEY UPDATE
  permission_name=VALUES(permission_name),resource_type=VALUES(resource_type),
  resource_path=VALUES(resource_path),perm_type=VALUES(perm_type),
  amplifies=0,status=1,is_deleted=0;

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code='overview_b0_export'
  AND (r.role_code <> 'SUPER_ADMIN' OR r.status <> 1 OR r.is_deleted <> 0);

INSERT INTO nx_admin_role_permission(role_id,permission_id,is_deleted)
SELECT r.id,p.id,0
FROM nx_admin_role r
JOIN nx_admin_permission p ON p.permission_code='overview_b0_export'
WHERE r.role_code='SUPER_ADMIN' AND r.status=1 AND r.is_deleted=0
  AND p.resource_type='API' AND p.status=1 AND p.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0;

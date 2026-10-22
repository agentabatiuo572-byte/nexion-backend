-- H4 新促销沿用超级管理员的全域管理权限；普通角色仍由 A1 单独授权。
INSERT INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM nx_admin_role r
JOIN nx_admin_permission p ON LEFT(p.permission_code, 17) = 'growth_promotion_'
WHERE r.role_code = 'SUPER_ADMIN' AND r.status = 1 AND r.is_deleted = 0
  AND p.resource_type = 'API' AND p.status = 1 AND p.is_deleted = 0
ON DUPLICATE KEY UPDATE is_deleted = 0, updated_at = NOW();

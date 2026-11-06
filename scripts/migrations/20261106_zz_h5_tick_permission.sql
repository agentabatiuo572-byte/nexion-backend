SET NAMES utf8mb4;
START TRANSACTION;

INSERT INTO nx_admin_permission
    (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted)
VALUES ('growth_h5_tick_write', '修改签到收益检查间隔', 'API', '/api/admin/growth/earn-milestones/tick-interval', 'WRITE', 0, 1, 0)
ON DUPLICATE KEY UPDATE status = 1, is_deleted = 0;

INSERT INTO nx_admin_role_permission (role_id, permission_id, is_deleted)
SELECT r.id, p.id, 0
FROM nx_admin_role r
JOIN nx_admin_permission p ON p.permission_code = 'growth_h5_tick_write'
WHERE r.role_code = 'SUPER_ADMIN' AND r.status = 1 AND r.is_deleted = 0
ON DUPLICATE KEY UPDATE is_deleted = 0;

COMMIT;

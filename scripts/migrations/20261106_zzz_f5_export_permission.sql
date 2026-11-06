-- Copy the old export entitlement once. The old route required F5 read at the
-- controller and a network_ permission outside *_read at the RBAC filter.
START TRANSACTION;

SET @f5_export_first_install = NOT EXISTS (
    SELECT 1 FROM nx_admin_permission WHERE permission_code = 'network_f5_export'
);

INSERT IGNORE INTO nx_admin_permission
    (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted)
VALUES ('network_f5_export', 'F5 奖励记录核对 - 导出脱敏 CSV', 'API', '/network/commissions', 'HIGH', 0, 1, 0);

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, export_perm.id
FROM nx_admin_role r
JOIN nx_admin_role_permission reader ON reader.role_id = r.id AND reader.is_deleted = 0
JOIN nx_admin_permission read_perm ON read_perm.id = reader.permission_id
    AND BINARY read_perm.permission_code = BINARY 'network_f5_read'
    AND read_perm.resource_type = 'API' AND read_perm.status = 1 AND read_perm.is_deleted = 0
JOIN nx_admin_permission export_perm ON export_perm.permission_code = 'network_f5_export'
WHERE @f5_export_first_install = 1 AND r.status = 1 AND r.is_deleted = 0
    AND EXISTS (
        SELECT 1 FROM nx_admin_role_permission scope_grant
        JOIN nx_admin_permission scope_perm ON scope_perm.id = scope_grant.permission_id
            AND scope_perm.resource_type = 'API' AND scope_perm.status = 1 AND scope_perm.is_deleted = 0
        WHERE scope_grant.role_id = r.id AND scope_grant.is_deleted = 0
            AND BINARY LEFT(scope_perm.permission_code, 8) = BINARY 'network_'
            AND BINARY RIGHT(scope_perm.permission_code, 5) <> BINARY '_read'
    );

COMMIT;

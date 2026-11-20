-- Install the A2 and B4 permissions missing from the first-install RBAC catalog.
SET NAMES utf8mb4;
START TRANSACTION;

INSERT IGNORE INTO nx_admin_permission
    (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, menu_id, status, is_deleted)
SELECT defaults.permission_code, defaults.permission_name, 'API', defaults.resource_path,
       defaults.perm_type, 0, menu.id, 1, 0
FROM (
    SELECT 'platform_a2_export' permission_code, 'A2 审计日志导出' permission_name,
           '/platform/audit/exports' resource_path, 'READ' perm_type, 'A2' menu_code
    UNION ALL SELECT 'overview_b4_jump', 'B4 节奏状态-跳 H1', '/overview/rhythm', 'WRITE', 'B4'
    UNION ALL SELECT 'overview_b4_export', 'B4 节奏状态-Phase 分布导出', '/overview/rhythm', 'WRITE', 'B4'
) defaults
LEFT JOIN nx_admin_menu menu ON menu.menu_code = defaults.menu_code
    AND menu.status = 1 AND menu.is_deleted = 0;

-- Preserve existing bindings, including soft-deleted rows. B4 jump is a separate
-- authority from the H1 read permission required to use the destination page.
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id, is_deleted)
SELECT r.id, p.id, 0
FROM nx_admin_role r
JOIN nx_admin_permission p ON (
       (p.permission_code = 'platform_a2_export' AND r.role_code IN ('SUPER_ADMIN', 'AUDITOR', 'RISK'))
    OR (p.permission_code = 'overview_b4_jump' AND r.role_code IN (
        'SUPER_ADMIN', 'FINANCE', 'FINANCE_LEAD', 'RISK', 'GROWTH', 'CONTENT', 'CONFIG_ADMIN'
    ))
    OR (p.permission_code = 'overview_b4_export' AND r.role_code = 'SUPER_ADMIN')
)
WHERE r.status = 1 AND r.is_deleted = 0 AND p.status = 1 AND p.is_deleted = 0;

COMMIT;

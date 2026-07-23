-- L6 is a read-only behavior-analysis surface. PRD v4 L6 section 6 grants
-- matrix/drill-down visibility to Growth, Risk and read-only Auditor roles.
INSERT INTO nx_admin_role_permission (role_id, permission_id, is_deleted)
SELECT r.id, p.id, 0
FROM nx_admin_role r
JOIN nx_admin_permission p ON p.permission_code = 'bi_l6_read'
WHERE r.role_code IN ('GROWTH', 'RISK', 'AUDITOR')
  AND r.is_deleted = 0
  AND p.status = 1
  AND p.is_deleted = 0
ON DUPLICATE KEY UPDATE is_deleted = 0, updated_at = NOW();

INSERT INTO nx_admin_role_menu (role_id, menu_id, is_deleted)
SELECT r.id, m.id, 0
FROM nx_admin_role r
JOIN nx_admin_menu m ON m.menu_code IN ('L', 'L6')
WHERE r.role_code IN ('GROWTH', 'RISK', 'AUDITOR')
  AND r.is_deleted = 0
  AND m.status = 1
  AND m.is_deleted = 0
ON DUPLICATE KEY UPDATE is_deleted = 0, updated_at = NOW();

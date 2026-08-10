-- Incremental closure for databases seeded before CONTENT was restricted to M5.
-- The deletion is deliberately scoped to CONTENT + M-domain codes only; no other role is touched.

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id AND r.role_code='CONTENT'
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code LIKE 'service_m%';

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='CONTENT'
  AND r.is_deleted=0
  AND p.permission_code IN ('service_m5_read','service_m5_write')
  AND p.status=1 AND p.is_deleted=0;

-- Remove legacy M1-M4 menu visibility, then restore only the M parent and M5 page.
DELETE rm FROM nx_admin_role_menu rm
JOIN nx_admin_role r ON r.id=rm.role_id AND r.role_code='CONTENT'
JOIN nx_admin_menu m ON m.id=rm.menu_id
WHERE m.menu_code LIKE 'M%';

INSERT IGNORE INTO nx_admin_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM nx_admin_role r JOIN nx_admin_menu m
WHERE m.menu_code IN ('M','M5')
  AND r.role_code='CONTENT'
  AND r.is_deleted=0
  AND m.status=1 AND m.is_deleted=0;

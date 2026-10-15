-- Keep the registered D2 migration immutable; retire only its historical SUPPORT grants.
-- Other roles, permissions, menus, account qualifications and business rows are unchanged.
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE r.role_code='SUPPORT' AND p.permission_code='finance_d2_read';

DELETE rm FROM nx_admin_role_menu rm
JOIN nx_admin_role r ON r.id=rm.role_id
JOIN nx_admin_menu m ON m.id=rm.menu_id
WHERE r.role_code='SUPPORT' AND m.menu_code IN ('D','D2');

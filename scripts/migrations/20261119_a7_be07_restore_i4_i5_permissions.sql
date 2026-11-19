-- A7-BE-07: repair the earlier A7-BE-01 run that soft-deleted the canonical
-- MENU_CONTENT_I4 / MENU_CONTENT_I5 menus and the still-effective content_i4_* /
-- content_i5_* API permissions they host. Restore menus, role-menu bindings and
-- rebind those permissions to the canonical menu. Idempotent.
START TRANSACTION;

UPDATE nx_admin_menu
   SET status = 1, is_deleted = 0, updated_at = NOW()
 WHERE menu_code IN ('MENU_CONTENT_I4','MENU_CONTENT_I5')
   AND (status = 0 OR is_deleted = 1);

UPDATE nx_admin_role_menu rm
JOIN nx_admin_menu m ON m.id = rm.menu_id
   SET rm.is_deleted = 0, rm.updated_at = NOW()
 WHERE m.menu_code IN ('MENU_CONTENT_I4','MENU_CONTENT_I5')
   AND rm.is_deleted = 1;

UPDATE nx_admin_permission p
JOIN nx_admin_menu m
  ON m.menu_code = CASE
       WHEN p.permission_code LIKE 'content_i4_%' THEN 'MENU_CONTENT_I4'
       WHEN p.permission_code LIKE 'content_i5_%' THEN 'MENU_CONTENT_I5'
     END
   SET p.menu_id = m.id,
       p.status = 1,
       p.is_deleted = 0,
       p.updated_at = NOW()
 WHERE p.resource_type = 'API'
   AND p.permission_code REGEXP '^content_i[45]_'
   AND (p.status = 0 OR p.is_deleted = 1);

COMMIT;
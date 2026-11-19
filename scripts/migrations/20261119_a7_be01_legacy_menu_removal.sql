-- A7-BE-01: remove the legacy admin menu nodes that never appear in the PC left
-- navigation. Role bindings are cleared first, then permission rows still bound to
-- those legacy nodes, then the nodes themselves are soft-deleted. Idempotent.
-- The canonical MENU_CONTENT_I4 / MENU_CONTENT_I5 keep their effective content_i4_*
-- / content_i5_* API permissions; only the truly legacy UMS/HOME nodes are removed.
START TRANSACTION;

UPDATE nx_admin_role_menu rm
JOIN nx_admin_menu m ON m.id = rm.menu_id
   SET rm.is_deleted = 1, rm.updated_at = NOW()
 WHERE rm.is_deleted = 0
   AND m.menu_code IN ('MENU_HOME','MENU_UMS','MENU_UMS_ADMIN','MENU_UMS_ROLE',
                       'MENU_UMS_MENU','MENU_UMS_PERMISSION');

UPDATE nx_admin_permission p
JOIN nx_admin_menu m ON m.id = p.menu_id
   SET p.is_deleted = 1, p.status = 0, p.updated_at = NOW()
 WHERE p.is_deleted = 0
   AND m.menu_code IN ('MENU_HOME','MENU_UMS','MENU_UMS_ADMIN','MENU_UMS_ROLE',
                       'MENU_UMS_MENU','MENU_UMS_PERMISSION');

UPDATE nx_admin_menu
   SET status = 0, is_deleted = 1, updated_at = NOW()
 WHERE menu_code IN ('MENU_HOME','MENU_UMS','MENU_UMS_ADMIN','MENU_UMS_ROLE',
                     'MENU_UMS_MENU','MENU_UMS_PERMISSION')
   AND is_deleted = 0;

COMMIT;
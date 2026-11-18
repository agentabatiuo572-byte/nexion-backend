-- A7-BE-01: remove the legacy admin menu nodes that never appear in the PC left
-- navigation. Role bindings are cleared first, then any permission rows still bound
-- to those nodes, then the nodes themselves are soft-deleted. Idempotent.
START TRANSACTION;

UPDATE nx_admin_role_menu rm
JOIN nx_admin_menu m ON m.id = rm.menu_id
   SET rm.is_deleted = 1, rm.updated_at = NOW()
 WHERE rm.is_deleted = 0
   AND m.menu_code IN ('MENU_HOME','MENU_UMS','MENU_UMS_ADMIN','MENU_UMS_ROLE',
                       'MENU_UMS_MENU','MENU_UMS_PERMISSION','MENU_CONTENT_I4','MENU_CONTENT_I5');

UPDATE nx_admin_permission p
JOIN nx_admin_menu m ON m.id = p.menu_id
   SET p.is_deleted = 1, p.status = 0, p.updated_at = NOW()
 WHERE p.is_deleted = 0
   AND m.menu_code IN ('MENU_HOME','MENU_UMS','MENU_UMS_ADMIN','MENU_UMS_ROLE',
                       'MENU_UMS_MENU','MENU_UMS_PERMISSION','MENU_CONTENT_I4','MENU_CONTENT_I5');

UPDATE nx_admin_menu
   SET status = 0, is_deleted = 1, updated_at = NOW()
 WHERE menu_code IN ('MENU_HOME','MENU_UMS','MENU_UMS_ADMIN','MENU_UMS_ROLE',
                     'MENU_UMS_MENU','MENU_UMS_PERMISSION','MENU_CONTENT_I4','MENU_CONTENT_I5')
   AND is_deleted = 0;

COMMIT;

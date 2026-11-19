-- A7-BE-07/A7-BE-08: repair the earlier A7-BE-01 run that soft-deleted the canonical
-- MENU_CONTENT_I4 / MENU_CONTENT_I5 menus and the still-effective content_i4_* /
-- content_i5_* API permissions they host. Restore menus, role-menu bindings and
-- rebind those permissions to the canonical menu. Idempotent.
--
-- A7-BE-08: only the 8 current I4/I5 permissions are restored. The retired
-- content_i4_disclosure_publish / content_i4_gate_adjust codes stay retired and
-- deliberately deleted role-menu bindings are not revived.
START TRANSACTION;

UPDATE nx_admin_menu
   SET status = 1, is_deleted = 0, updated_at = NOW()
 WHERE menu_code IN ('MENU_CONTENT_I4','MENU_CONTENT_I5')
   AND (status = 0 OR is_deleted = 1);

UPDATE nx_admin_role_permission rp
JOIN nx_admin_permission p ON p.id = rp.permission_id
   SET rp.is_deleted = 0, rp.updated_at = NOW()
 WHERE p.resource_type = 'API'
   AND p.permission_code IN (
       'content_i4_read',
       'content_i4_write',
       'content_i4_publish_standard',
       'content_i4_trust_section_manage',
       'content_i5_read',
       'content_i5_write',
       'content_i5_disclosure_publish',
       'content_i5_gate_adjust'
     )
   AND rp.is_deleted = 1;

UPDATE nx_admin_role_menu rm
JOIN nx_admin_menu m ON m.id = rm.menu_id
JOIN nx_admin_role_permission rp ON rp.role_id = rm.role_id AND rp.is_deleted = 0
JOIN nx_admin_permission p ON p.id = rp.permission_id AND p.is_deleted = 0
   SET rm.is_deleted = 0, rm.updated_at = NOW()
 WHERE m.menu_code IN ('MENU_CONTENT_I4','MENU_CONTENT_I5')
   AND rm.is_deleted = 1
   AND p.permission_code IN (
       'content_i4_read',
       'content_i4_write',
       'content_i4_publish_standard',
       'content_i4_trust_section_manage',
       'content_i5_read',
       'content_i5_write',
       'content_i5_disclosure_publish',
       'content_i5_gate_adjust'
     )
   AND ((m.menu_code = 'MENU_CONTENT_I4' AND p.permission_code LIKE 'content_i4_%')
     OR (m.menu_code = 'MENU_CONTENT_I5' AND p.permission_code LIKE 'content_i5_%'));

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
   AND p.permission_code IN (
       'content_i4_read',
       'content_i4_write',
       'content_i4_publish_standard',
       'content_i4_trust_section_manage',
       'content_i5_read',
       'content_i5_write',
       'content_i5_disclosure_publish',
       'content_i5_gate_adjust'
     )
   AND (p.status = 0 OR p.is_deleted = 1);

COMMIT;
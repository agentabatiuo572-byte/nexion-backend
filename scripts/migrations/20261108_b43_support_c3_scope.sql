-- B43: the B30 SUPPORT role has no C3 permission or menu binding.
-- Forward-only correction: retain historical migration checksums and all other roles.
SET NAMES utf8mb4;
START TRANSACTION;

UPDATE nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
SET rp.is_deleted=1, rp.updated_at=NOW()
WHERE r.role_code='SUPPORT' AND LEFT(p.permission_code,8)='user_c3_'
  AND rp.is_deleted=0;

UPDATE nx_admin_role_menu rm
JOIN nx_admin_role r ON r.id=rm.role_id
JOIN nx_admin_menu m ON m.id=rm.menu_id
SET rm.is_deleted=1, rm.updated_at=NOW()
WHERE r.role_code='SUPPORT' AND m.menu_code='C3' AND rm.is_deleted=0;

COMMIT;

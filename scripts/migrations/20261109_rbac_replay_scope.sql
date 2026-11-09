-- RBAC-05: align only the four confirmed built-in-role replay differences.
-- Wallet-PIN grants are applied by the following dedicated, approved migration.
SET NAMES utf8mb4;
START TRANSACTION;

UPDATE nx_admin_role_menu rm
JOIN nx_admin_role r ON r.id=rm.role_id
JOIN nx_admin_menu m ON m.id=rm.menu_id
SET rm.is_deleted=1, rm.updated_at=NOW()
WHERE r.role_code='SUPPORT' AND m.menu_code='M2' AND rm.is_deleted=0;

-- Keep the canonical retired node retired after an older menu seed was replayed.
UPDATE nx_admin_menu
SET status=0, is_deleted=1, updated_at=NOW()
WHERE menu_code='M2' AND (status<>0 OR is_deleted<>1);

-- B30 retains the AUDITOR legal-terms read grant. Its permission is defined after
-- the first classic seed on a fresh database; do not overwrite an existing binding.
INSERT IGNORE INTO nx_admin_role_permission (role_id,permission_id,is_deleted)
SELECT r.id,p.id,0 FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='AUDITOR' AND p.permission_code='content_legal_terms_read'
  AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

-- L6's approved aggregate export belongs to GROWTH and AUDITOR as well as SUPER_ADMIN.
INSERT IGNORE INTO nx_admin_role_permission (role_id,permission_id,is_deleted)
SELECT r.id,p.id,0 FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('GROWTH','AUDITOR') AND p.permission_code='bi_l6_export'
  AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

COMMIT;

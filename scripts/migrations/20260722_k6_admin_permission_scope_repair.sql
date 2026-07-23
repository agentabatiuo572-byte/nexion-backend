-- K6 security repair: publishing and rollback are super-administrator-only.
-- This is an idempotent soft revocation; all read/write/senior relations remain untouched.
START TRANSACTION;

UPDATE nx_admin_role_permission relation
JOIN nx_admin_role role ON role.id=relation.role_id AND role.is_deleted=0
JOIN nx_admin_permission permission ON permission.id=relation.permission_id AND permission.is_deleted=0
SET relation.is_deleted=1,relation.updated_at=NOW()
WHERE permission.permission_code='risk_k6_admin'
  AND role.role_code<>'SUPER_ADMIN'
  AND relation.is_deleted=0;

COMMIT;

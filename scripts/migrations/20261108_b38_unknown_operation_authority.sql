-- User decision 2026-11-08: unknown-result investigation is SUPER_ADMIN only,
-- still requiring original business authority and authoritative evidence.
-- This does not grant financial execution or bypass original business approval.
SET NAMES utf8mb4;
START TRANSACTION;

INSERT INTO nx_admin_permission
  (permission_code,permission_name,resource_type,resource_path,perm_type,amplifies,status,is_deleted)
VALUES ('platform_a2_unknown_investigate','A2 未知结果核查结案','API','/commands/investigations','HIGH',0,1,0)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),resource_type='API',
  resource_path=VALUES(resource_path),perm_type='HIGH',amplifies=0,status=1,is_deleted=0;

UPDATE nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
SET rp.is_deleted=1,rp.updated_at=NOW()
WHERE p.permission_code='platform_a2_unknown_investigate' AND r.role_code<>'SUPER_ADMIN';

INSERT INTO nx_admin_role_permission (role_id,permission_id,is_deleted)
SELECT r.id,p.id,0 FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='SUPER_ADMIN' AND r.status=1 AND r.is_deleted=0
  AND p.permission_code='platform_a2_unknown_investigate' AND p.status=1 AND p.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0,updated_at=NOW();

COMMIT;

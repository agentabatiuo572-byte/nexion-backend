-- 2026-11-09 user decision: RISK and super-admin roles may change wallet-PIN policy and release its lock.
-- Apply after permission definitions; only these explicitly approved bindings may be restored.
SET NAMES utf8mb4;

INSERT INTO nx_admin_role_permission (role_id,permission_id,is_deleted)
SELECT r.id,p.id,0 FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('RISK','SUPER','SUPERADMIN','SUPER_ADMIN') AND r.status=1 AND r.is_deleted=0
  AND p.permission_code IN ('user_c5_payment_password_policy_write','user_c5_payment_password_release')
  AND p.status=1 AND p.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0;

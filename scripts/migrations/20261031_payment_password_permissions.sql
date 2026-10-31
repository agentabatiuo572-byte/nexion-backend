-- PIN release and policy changes never inherit login-unlock authorities.
INSERT INTO nx_admin_permission(permission_code,permission_name,resource_type,resource_path,perm_type,amplifies,status,is_deleted)
VALUES ('user_c5_payment_password_release','C5 解除钱包支付密码锁定','API','/users/security','HIGH',0,1,0),
 ('user_c5_payment_password_policy_write','C5 调整钱包支付密码策略','API','/users/security','HIGH',0,1,0)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r CROSS JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER','SUPERADMIN','SUPER_ADMIN') AND r.status=1 AND r.is_deleted=0
 AND p.permission_code IN ('user_c5_payment_password_release','user_c5_payment_password_policy_write') AND p.status=1 AND p.is_deleted=0;

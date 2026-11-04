-- D1 direct-purchase policy already requires this API permission in both clients and server.
-- Default only SUPER_ADMIN; preserve all other roles and any later explicit RBAC grants.
INSERT INTO nx_admin_permission
  (permission_code,permission_name,resource_type,resource_path,perm_type,amplifies,status,is_deleted)
VALUES
  ('finance_d1_fx_manage','订单直付费用与最低充值额配置','API','/finance/recon','HIGH',1,1,0)
ON DUPLICATE KEY UPDATE
  permission_name=VALUES(permission_name),resource_type=VALUES(resource_type),
  resource_path=VALUES(resource_path),perm_type=VALUES(perm_type),
  amplifies=VALUES(amplifies),status=1,is_deleted=0;

UPDATE nx_admin_permission p
JOIN nx_admin_menu m ON m.menu_code='D1' AND m.is_deleted=0
SET p.menu_id=m.id
WHERE p.permission_code='finance_d1_fx_manage';

INSERT INTO nx_admin_role_permission(role_id,permission_id,is_deleted)
SELECT r.id,p.id,0
FROM nx_admin_role r
JOIN nx_admin_permission p ON p.permission_code='finance_d1_fx_manage'
WHERE r.role_code='SUPER_ADMIN' AND r.status=1 AND r.is_deleted=0
  AND p.resource_type='API' AND p.status=1 AND p.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0;

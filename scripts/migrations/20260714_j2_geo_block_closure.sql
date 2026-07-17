-- J2 geo-block：边缘判定源权限仅授予超级管理员，并清理历史误授权。
INSERT INTO nx_admin_permission (
  permission_code, permission_name, resource_type, resource_path,
  perm_type, amplifies, status, is_deleted
) VALUES (
  'emergency_j2_edge_source_manage', '边缘IP判定源切换(仅超管)', 'API',
  '/emergency/geo-block', 'HIGH', 0, 1, 0
) ON DUPLICATE KEY UPDATE
  permission_name = VALUES(permission_name),
  resource_type = VALUES(resource_type),
  resource_path = VALUES(resource_path),
  perm_type = VALUES(perm_type),
  amplifies = VALUES(amplifies),
  status = 1,
  is_deleted = 0;

DELETE rp
  FROM nx_admin_role_permission rp
  JOIN nx_admin_role r ON r.id = rp.role_id
  JOIN nx_admin_permission p ON p.id = rp.permission_id
 WHERE p.permission_code = 'emergency_j2_edge_source_manage'
   AND r.role_code <> 'SUPER_ADMIN';

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id
  FROM nx_admin_role r
  JOIN nx_admin_permission p ON p.permission_code = 'emergency_j2_edge_source_manage'
 WHERE r.role_code = 'SUPER_ADMIN'
   AND r.status = 1 AND r.is_deleted = 0
   AND p.status = 1 AND p.is_deleted = 0;

-- PRD J2 ②(b)：仅 Genesis 与兑换已完成派生登记；其余候选入口在真实用户端落点确认前保持只读。
UPDATE nx_emergency_geo_endpoint_catalog
   SET status = CASE
       WHEN endpoint_key IN ('market.genesis', 'finance.swap') THEN 'ACTIVE'
       ELSE 'PENDING'
   END,
       updated_at = CURRENT_TIMESTAMP
 WHERE is_deleted = 0;

USE nexion;

UPDATE admin_permission
SET resource_type = 'MENU',
    status = 0,
    is_deleted = 1,
    updated_at = NOW()
WHERE permission_code LIKE 'MENU_%';

UPDATE admin_permission
SET permission_code = CONCAT(permission_code, '_LEGACY_', id),
    status = 0,
    is_deleted = 1,
    updated_at = NOW()
WHERE id BETWEEN 101 AND 110
  AND resource_type = 'API';

INSERT INTO admin_permission (id, permission_code, permission_name, resource_type, resource_path, remark, status)
VALUES
  (101, 'PERM_BFF_READ', 'Read BFF aggregation', 'API', '/bff/**', NULL, 1),
  (102, 'PERM_COMPUTE_READ', 'Read compute operations', 'API', '/compute/**', NULL, 1),
  (117, 'PERM_COMPUTE_WRITE', 'Write compute task and device status', 'API', '/compute/tasks/**,/compute/devices/*/status', NULL, 1),
  (103, 'PERM_COMMERCE_READ', 'Read commerce operations', 'API', '/commerce/**', NULL, 1),
  (104, 'PERM_COMMERCE_WRITE', 'Write commerce operations', 'API', '/commerce/orders/**', NULL, 1),
  (105, 'PERM_WALLET_READ', 'Read wallet operations', 'API', '/wallet/**', NULL, 1),
  (112, 'PERM_WALLET_WRITE', 'Write wallet operations', 'API', '/wallet/**', NULL, 1),
  (106, 'PERM_EARNINGS_READ', 'Read earnings operations', 'API', '/earnings/**', NULL, 1),
  (118, 'PERM_EARNINGS_WRITE', 'Write earnings tick and milestone operations', 'API', '/earnings/ticks/**,/earnings/milestones/**,/earnings/events/settle-receipt', NULL, 1),
  (107, 'PERM_NOTIFICATION_READ', 'Read notifications', 'API', '/notifications/**', NULL, 1),
  (119, 'PERM_NOTIFICATION_WRITE', 'Write notification operations', 'API', '/notifications/ops/**', NULL, 1),
  (108, 'PERM_TEAM_READ', 'Read team operations', 'API', '/team/**', NULL, 1),
  (116, 'PERM_TEAM_WRITE', 'Write team operations', 'API', '/team/**', NULL, 1),
  (109, 'PERM_MISSION_READ', 'Read mission operations', 'API', '/missions/**', NULL, 1),
  (110, 'PERM_COMPLIANCE_READ', 'Read compliance operations', 'API', '/compliance/**', NULL, 1),
  (113, 'PERM_COMPLIANCE_WRITE', 'Write compliance operations', 'API', '/compliance/**', NULL, 1),
  (111, 'PERM_SYSTEM_READ', 'Read system operations', 'API', '/system/**', NULL, 1),
  (115, 'PERM_SYSTEM_WRITE', 'Write system operations', 'API', '/system/**', NULL, 1),
  (114, 'PERM_OPENAPI_ADMIN', 'Admin OpenAPI apps, quotas, audits, and webhook delivery', 'API', '/openapi/ops/**,/openapi/webhooks/deliveries/**', NULL, 1)
ON DUPLICATE KEY UPDATE
  permission_code = VALUES(permission_code),
  permission_name = VALUES(permission_name),
  resource_type = VALUES(resource_type),
  resource_path = VALUES(resource_path),
  remark = VALUES(remark),
  status = VALUES(status),
  is_deleted = 0,
  updated_at = NOW();

INSERT IGNORE INTO admin_role_permission (role_id, permission_id)
SELECT 1, id
FROM admin_permission
WHERE permission_code IN (
  'PERM_BFF_READ',
  'PERM_COMPUTE_READ',
  'PERM_COMPUTE_WRITE',
  'PERM_COMMERCE_READ',
  'PERM_COMMERCE_WRITE',
  'PERM_WALLET_READ',
  'PERM_WALLET_WRITE',
  'PERM_EARNINGS_READ',
  'PERM_EARNINGS_WRITE',
  'PERM_NOTIFICATION_READ',
  'PERM_NOTIFICATION_WRITE',
  'PERM_TEAM_READ',
  'PERM_TEAM_WRITE',
  'PERM_MISSION_READ',
  'PERM_COMPLIANCE_READ',
  'PERM_COMPLIANCE_WRITE',
  'PERM_SYSTEM_READ',
  'PERM_SYSTEM_WRITE',
  'PERM_OPENAPI_ADMIN'
);

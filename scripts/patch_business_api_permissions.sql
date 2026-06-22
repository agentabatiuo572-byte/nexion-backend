USE nexion;

UPDATE nx_admin_permission
SET resource_type = 'MENU',
    status = 0,
    is_deleted = 1,
    updated_at = NOW()
WHERE permission_code LIKE 'MENU_%';

UPDATE nx_admin_permission
SET permission_code = CONCAT(permission_code, '_LEGACY_', id),
    status = 0,
    is_deleted = 1,
    updated_at = NOW()
WHERE id BETWEEN 101 AND 110
  AND resource_type = 'API';

INSERT INTO nx_admin_permission (id, permission_code, permission_name, resource_type, resource_path, remark, status)
VALUES
  (101, 'PERM_OVERVIEW_READ', 'Read ops dashboard overview', 'API', '/api/admin/ops-dashboard/**,/api/admin/treasury/overview,/api/admin/treasury/dual-ledger', NULL, 1),
  (102, 'PERM_USERS_READ', 'Read user operations', 'API', '/api/admin/users/**', NULL, 1),
  (117, 'PERM_USERS_WRITE', 'Write user operations', 'API', '/api/admin/users/**', NULL, 1),
  (103, 'PERM_FINANCE_READ', 'Read finance and treasury operations', 'API', '/api/admin/finance/**,/api/admin/treasury/**', NULL, 1),
  (104, 'PERM_FINANCE_WRITE', 'Write finance and treasury operations', 'API', '/api/admin/finance/**,/api/admin/treasury/**', NULL, 1),
  (105, 'PERM_DEVICES_READ', 'Read device and commerce operations', 'API', '/api/admin/devices/**', NULL, 1),
  (112, 'PERM_DEVICES_WRITE', 'Write device and commerce operations', 'API', '/api/admin/devices/**', NULL, 1),
  (106, 'PERM_MARKET_READ', 'Read financial product operations', 'API', '/api/admin/market/**', NULL, 1),
  (118, 'PERM_MARKET_WRITE', 'Write financial product operations', 'API', '/api/admin/market/**', NULL, 1),
  (107, 'PERM_CONTENT_READ', 'Read content and support operations', 'API', '/api/admin/content/**', NULL, 1),
  (119, 'PERM_CONTENT_WRITE', 'Write content and support operations', 'API', '/api/admin/content/**', NULL, 1),
  (108, 'PERM_TEAM_READ', 'Read team and commission operations', 'API', '/api/admin/teams/**', NULL, 1),
  (116, 'PERM_TEAM_WRITE', 'Write team and commission operations', 'API', '/api/admin/teams/**', NULL, 1),
  (109, 'PERM_GROWTH_READ', 'Read growth operations', 'API', '/api/admin/growth/**', NULL, 1),
  (121, 'PERM_GROWTH_WRITE', 'Write growth operations', 'API', '/api/admin/growth/**', NULL, 1),
  (110, 'PERM_RISK_READ', 'Read risk and emergency operations', 'API', '/api/admin/risk/**,/api/admin/emergency/**', NULL, 1),
  (113, 'PERM_RISK_WRITE', 'Write risk and emergency operations', 'API', '/api/admin/risk/**,/api/admin/emergency/**', NULL, 1),
  (111, 'PERM_SYSTEM_READ', 'Read platform operations', 'API', '/api/admin/platform/**,/api/admin/options/**,/api/admin/media/**,/api/admin/auth/**', NULL, 1),
  (115, 'PERM_SYSTEM_WRITE', 'Write platform operations', 'API', '/api/admin/platform/**,/api/admin/options/**,/api/admin/media/**,/api/admin/commands/**', NULL, 1),
  (114, 'PERM_BI_READ', 'Read BI and reporting operations', 'API', '/api/admin/bi/**', NULL, 1),
  (120, 'PERM_AUDIT_READ', 'Read platform audit logs', 'API', '/api/admin/platform/audit/**', NULL, 1)
ON DUPLICATE KEY UPDATE
  permission_code = VALUES(permission_code),
  permission_name = VALUES(permission_name),
  resource_type = VALUES(resource_type),
  resource_path = VALUES(resource_path),
  remark = VALUES(remark),
  status = VALUES(status),
  is_deleted = 0,
  updated_at = NOW();

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT 1, id
FROM nx_admin_permission
WHERE permission_code IN (
  'PERM_OVERVIEW_READ',
  'PERM_USERS_READ',
  'PERM_USERS_WRITE',
  'PERM_FINANCE_READ',
  'PERM_FINANCE_WRITE',
  'PERM_DEVICES_READ',
  'PERM_DEVICES_WRITE',
  'PERM_MARKET_READ',
  'PERM_MARKET_WRITE',
  'PERM_CONTENT_READ',
  'PERM_CONTENT_WRITE',
  'PERM_TEAM_READ',
  'PERM_TEAM_WRITE',
  'PERM_GROWTH_READ',
  'PERM_GROWTH_WRITE',
  'PERM_RISK_READ',
  'PERM_RISK_WRITE',
  'PERM_SYSTEM_READ',
  'PERM_SYSTEM_WRITE',
  'PERM_BI_READ',
  'PERM_AUDIT_READ'
);

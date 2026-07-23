-- D4 canonical seven-type, read-only reconciliation and least-privilege export closure.

INSERT INTO nx_admin_permission
  (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted)
VALUES
  ('finance_d4_read','全平台账本审计-读','API','/finance/ledger','READ',0,1,0),
  ('finance_d4_user_read','单用户账本审计-读','API','/finance/ledger','READ',0,1,0),
  ('finance_d4_export','脱敏账单导出','API','/finance/ledger','READ',0,1,0)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name), resource_type='API',
  resource_path='/finance/ledger', perm_type='READ', amplifies=0, status=1, is_deleted=0, updated_at=NOW();

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code LIKE 'finance_d4_%';

INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR')
  AND p.permission_code='finance_d4_read' AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR','SUPPORT')
  AND p.permission_code='finance_d4_user_read' AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','AUDITOR')
  AND p.permission_code='finance_d4_export' AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

-- Keep the page visible for the restricted support single-user read path.
INSERT IGNORE INTO nx_admin_role_menu(role_id,menu_id)
SELECT r.id,m.id FROM nx_admin_role r JOIN nx_admin_menu m
WHERE r.role_code='SUPPORT' AND m.menu_code IN ('D','D4')
  AND r.status=1 AND r.is_deleted=0 AND m.status=1 AND m.is_deleted=0;

-- D4 cross-module read links must be reachable for finance operators: C3 is the
-- only correction surface and A4 is the authoritative money-event catalog.
INSERT INTO nx_admin_role_menu(role_id,menu_id,is_deleted)
SELECT r.id,m.id,0 FROM nx_admin_role r JOIN nx_admin_menu m
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD')
  AND m.menu_code IN ('C','C3','A','A4')
  AND r.status=1 AND r.is_deleted=0 AND m.status=1 AND m.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0,updated_at=NOW();

INSERT INTO nx_admin_role_permission(role_id,permission_id,is_deleted)
SELECT r.id,p.id,0 FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD')
  AND p.permission_code IN ('user_c3_read','platform_a4_read')
  AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0,updated_at=NOW();

-- Retire every historical D4 mutation permission. C3 is the only asset correction entry.
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code='finance_d4_adjustment_create';
UPDATE nx_admin_permission SET status=0,is_deleted=1,updated_at=NOW()
WHERE permission_code='finance_d4_adjustment_create';

-- 高保真真实接口同步的 RBAC 增量。
-- 仅同步菜单与授权数据；业务表由各模块迁移负责。
START TRANSACTION;

INSERT INTO nx_admin_menu
  (menu_code, menu_name, menu_name_zh, parent_id, route_path, sort_order, remark, status, is_deleted)
SELECT 'D6', '汇率牌价', '汇率牌价', parent.id, '/finance/fx-rate', 6,
       'D6 汇率牌价；牌价仅影响新创建并锁价的入金意向单。', 1, 0
FROM nx_admin_menu parent
WHERE parent.menu_code='D' AND parent.is_deleted=0
ON DUPLICATE KEY UPDATE
  menu_name=VALUES(menu_name),
  menu_name_zh=VALUES(menu_name_zh),
  parent_id=VALUES(parent_id),
  route_path=VALUES(route_path),
  sort_order=VALUES(sort_order),
  remark=VALUES(remark),
  status=1,
  is_deleted=0,
  updated_at=NOW();

INSERT INTO nx_admin_permission
  (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted)
VALUES
  ('finance_d1_bank_reconcile','VietQR回单人工处置','API','/finance/recon','HIGH',1,1,0),
  ('finance_d1_bank_account_manage','VietQR收款账户池管理','API','/finance/recon','HIGH',1,1,0),
  ('finance_d1_bank_config_manage','VietQR匹配参数配置','API','/finance/recon','HIGH',1,1,0),
  ('finance_d6_read','汇率牌价-读','API','/finance/fx-rate','READ',0,1,0),
  ('finance_d6_manage','汇率牌价调整','API','/finance/fx-rate','HIGH',1,1,0),
  ('service_m3_timeout_manage','即时会话闲置提醒与自动结束策略','API','/service/sessions','HIGH',1,1,0),
  ('risk_k6_target_manage','Janus批准目标版本管理','API','/risk/janus-c2','HIGH',1,1,0)
ON DUPLICATE KEY UPDATE
  permission_name=VALUES(permission_name),
  resource_type=VALUES(resource_type),
  resource_path=VALUES(resource_path),
  perm_type=VALUES(perm_type),
  amplifies=VALUES(amplifies),
  status=1,
  is_deleted=0,
  updated_at=NOW();

UPDATE nx_admin_permission p
JOIN nx_admin_menu m ON m.route_path=p.resource_path AND m.is_deleted=0
SET p.menu_id=m.id, p.updated_at=NOW()
WHERE p.permission_code IN (
  'finance_d1_bank_reconcile',
  'finance_d1_bank_account_manage',
  'finance_d1_bank_config_manage',
  'finance_d6_read',
  'finance_d6_manage',
  'service_m3_timeout_manage',
  'risk_k6_target_manage'
);

INSERT INTO nx_admin_role_menu (role_id, menu_id, is_deleted)
SELECT role_row.id, menu_row.id, 0
FROM nx_admin_role role_row
JOIN nx_admin_menu menu_row ON menu_row.menu_code IN ('D','D6') AND menu_row.is_deleted=0
WHERE role_row.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR')
  AND role_row.status=1 AND role_row.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0, updated_at=NOW();

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE (p.permission_code IN (
         'finance_d1_bank_reconcile',
         'finance_d1_bank_account_manage',
         'finance_d1_bank_config_manage',
         'finance_d6_manage'
       ) AND r.role_code NOT IN ('SUPER_ADMIN','FINANCE_LEAD'))
   OR (p.permission_code IN ('service_m3_timeout_manage','risk_k6_target_manage')
       AND r.role_code <> 'SUPER_ADMIN')
   OR (p.permission_code='finance_d6_read'
       AND r.role_code NOT IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR'));

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT role_row.id, permission_row.id
FROM nx_admin_role role_row
JOIN nx_admin_permission permission_row
  ON permission_row.permission_code='finance_d6_read'
WHERE role_row.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR')
  AND role_row.status=1 AND role_row.is_deleted=0
  AND permission_row.status=1 AND permission_row.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT role_row.id, permission_row.id
FROM nx_admin_role role_row
JOIN nx_admin_permission permission_row
  ON permission_row.permission_code IN (
    'finance_d1_bank_reconcile',
    'finance_d1_bank_account_manage',
    'finance_d1_bank_config_manage',
    'finance_d6_manage'
  )
WHERE role_row.role_code IN ('SUPER_ADMIN','FINANCE_LEAD')
  AND role_row.status=1 AND role_row.is_deleted=0
  AND permission_row.status=1 AND permission_row.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT role_row.id, permission_row.id
FROM nx_admin_role role_row
JOIN nx_admin_permission permission_row
  ON permission_row.permission_code IN ('service_m3_timeout_manage','risk_k6_target_manage')
WHERE role_row.role_code='SUPER_ADMIN'
  AND role_row.status=1 AND role_row.is_deleted=0
  AND permission_row.status=1 AND permission_row.is_deleted=0;

COMMIT;

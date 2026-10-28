-- F5 commission actions get one permission per money effect: freeze/unfreeze and
-- user suspension (stop-loss, risk lead), early unlock/payment and reissue (pay out)
-- and reversal (claw back, finance lead). Growth keeps read and anomaly thresholds only.
-- The product has not launched, so the old shared keys are removed outright.
-- Runs after the classic RBAC seed in the startup chain. Safe to reapply.
START TRANSACTION;
INSERT INTO nx_admin_permission
  (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted)
VALUES
  ('network_f5_commission_freeze','佣金冻结与解冻','API','/network/commissions','HIGH',1,1,0),
  ('network_f5_commission_suspend','暂停用户佣金','API','/network/commissions','HIGH',1,1,0),
  ('network_f5_commission_release','佣金提前解锁与支付','API','/network/commissions','HIGH',1,1,0),
  ('network_f5_commission_reverse','佣金冲正','API','/network/commissions','HIGH',1,1,0),
  ('network_f5_commission_reissue','佣金补发','API','/network/commissions','HIGH',1,1,0)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name), resource_type=VALUES(resource_type),
  resource_path=VALUES(resource_path), perm_type=VALUES(perm_type), amplifies=VALUES(amplifies),
  status=1, is_deleted=0, updated_at=NOW();

DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_permission p ON p.id=rp.permission_id
LEFT JOIN nx_admin_role r ON r.id=rp.role_id
WHERE p.permission_code IN ('network_f5_commission_dispose','network_f5_commission_reject')
   OR (LEFT(p.permission_code,22)='network_f5_commission_' AND r.role_code='GROWTH');
DELETE FROM nx_admin_permission
WHERE permission_code IN ('network_f5_commission_dispose','network_f5_commission_reject');

INSERT INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='SUPER_ADMIN' AND LEFT(p.permission_code,22)='network_f5_commission_'
  AND p.status=1 AND p.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0, updated_at=NOW();
INSERT INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD','RISK','RISK_LEAD')
  AND p.permission_code='network_f5_read' AND p.status=1 AND p.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0, updated_at=NOW();
INSERT INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='RISK_LEAD'
  AND p.permission_code IN ('network_f5_write','network_f5_commission_freeze','network_f5_commission_suspend')
  AND p.status=1 AND p.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0, updated_at=NOW();
INSERT INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='FINANCE_LEAD'
  AND p.permission_code IN ('network_f5_commission_freeze','network_f5_commission_release',
                            'network_f5_commission_reverse','network_f5_commission_reissue')
  AND p.status=1 AND p.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0, updated_at=NOW();

INSERT INTO nx_admin_role_menu (role_id, menu_id, created_at, updated_at, is_deleted)
SELECT r.id, m.id, NOW(), NOW(), 0 FROM nx_admin_role r JOIN nx_admin_menu m
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD','RISK','RISK_LEAD')
  AND m.menu_code IN ('F','F5') AND m.status=1 AND m.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0, updated_at=NOW();
COMMIT;

-- B38 built-in-role deltas. No account, custom role or credential changes.
SET NAMES utf8mb4;
START TRANSACTION;

UPDATE nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
SET rp.is_deleted=1, rp.updated_at=NOW()
WHERE (r.role_code='FINANCE_LEAD' AND p.permission_code='platform_a2_operation_approve')
   OR (r.role_code='GROWTH' AND p.permission_code IN ('device_e4_order_refund','finance_d6_read'))
   OR (r.role_code IN ('CONFIG_ADMIN','CONTENT','SUPPORT') AND p.permission_code='finance_d6_read')
   OR (r.role_code='SUPPORT' AND p.permission_code LIKE 'finance_d4_%' AND p.permission_code<>'finance_d4_user_read')
   OR (r.role_code='RISK' AND p.permission_code IN ('content_i5_write','content_i5_disclosure_publish','content_i5_gate_adjust'));

INSERT INTO nx_admin_role_permission (role_id,permission_id,is_deleted)
SELECT r.id,p.id,0 FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0
  AND ((r.role_code='FINANCE_LEAD' AND p.permission_code='platform_a2_read')
    OR (r.role_code IN ('SUPER_ADMIN','FINANCE_LEAD') AND p.permission_code IN ('device_e4_read','device_e4_order_refund'))
    OR (r.role_code='SUPPORT' AND p.permission_code='finance_d4_user_read')
    OR (r.role_code='RISK' AND p.permission_code='content_i5_read')
    OR (r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR') AND p.permission_code='finance_d6_read'))
ON DUPLICATE KEY UPDATE is_deleted=0, updated_at=NOW();

UPDATE nx_admin_role_menu rm
JOIN nx_admin_role r ON r.id=rm.role_id
JOIN nx_admin_menu m ON m.id=rm.menu_id
SET rm.is_deleted=1, rm.updated_at=NOW()
WHERE r.role_code IN ('GROWTH','CONFIG_ADMIN','CONTENT','SUPPORT') AND m.menu_code='D6';

INSERT INTO nx_admin_role_menu (role_id,menu_id,is_deleted)
SELECT r.id,m.id,0 FROM nx_admin_role r JOIN nx_admin_menu m
WHERE r.status=1 AND r.is_deleted=0 AND m.status=1 AND m.is_deleted=0
  AND ((r.role_code='FINANCE_LEAD' AND m.menu_code IN ('A','A2','E','E4'))
    OR (r.role_code='SUPPORT' AND m.menu_code IN ('D','D4'))
    OR (r.role_code='RISK' AND m.menu_code IN ('I','I5'))
    OR (r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR') AND m.menu_code IN ('D','D6')))
ON DUPLICATE KEY UPDATE is_deleted=0, updated_at=NOW();

COMMIT;

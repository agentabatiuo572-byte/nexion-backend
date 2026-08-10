-- D7 server-canonical parameter management. Real payout provider remains blocked.
-- MySQL 8, rerunnable. Never flips provider_ready or channelEnabled on re-run.
SET NAMES utf8mb4;

INSERT INTO nx_config_item
  (config_key,config_value,value_type,config_group,visibility,remark,status,is_deleted)
VALUES
  ('finance.payout_vnd.values',
   '{"sellSpreadPct":1.5,"quoteTtlMinWithdraw":10,"requoteTolerancePct":2,"feeRatePct":1,"feeMinUsd":1,"feeMaxUsd":25,"minAmountUsd":20,"maxAmountUsd":5000,"channelEnabled":false,"effectiveAt":1786176000000,"lastUpdatedBy":"migration"}',
   'JSON','finance','ADMIN','D7 payout VND whole aggregate; provider blocked',1,0),
  ('finance.payout_vnd.version','1','NUMBER','finance','ADMIN','D7 payout VND aggregate version',1,0),
  ('finance.payout_vnd.provider_ready','false','BOOLEAN','finance','ADMIN','D7 server-controlled provider readiness; not operator editable',1,0)
ON DUPLICATE KEY UPDATE value_type=VALUES(value_type),config_group='finance',visibility='ADMIN',
  remark=VALUES(remark),updated_at=NOW();

UPDATE nx_admin_menu
   SET remark='D7 parameters are server-backed; payout provider remains unavailable'
 WHERE menu_code='D7';

INSERT INTO nx_admin_permission
  (permission_code,permission_name,resource_type,resource_path,perm_type,amplifies,menu_id,remark,status,is_deleted)
SELECT p.permission_code,p.permission_name,'API','/finance/payout-vnd',p.perm_type,p.amplifies,m.id,p.remark,1,0
  FROM nx_admin_menu m
  JOIN (
    SELECT 'finance_d7_read' permission_code,'D7 法币提现参数读取' permission_name,'READ' perm_type,0 amplifies,'读取服务端权威参数与供应商阻断状态' remark UNION ALL
    SELECT 'finance_d7_manage','D7 法币提现参数调整','HIGH',1,'整组CAS调整并写入强制审计' UNION ALL
    SELECT 'finance_d7_channel_toggle','D7 法币提现通道启停','CRITICAL',1,'供应商就绪与覆盖率门后的通道启停' UNION ALL
    SELECT 'finance_d7_force_inverted','D7 倒挂价差强制保存','CRITICAL',1,'仅超级管理员可强制倒挂'
  ) p
 WHERE m.menu_code='D7' AND m.is_deleted=0
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),resource_type='API',
  resource_path=VALUES(resource_path),perm_type=VALUES(perm_type),amplifies=VALUES(amplifies),
  menu_id=VALUES(menu_id),remark=VALUES(remark);

INSERT INTO nx_admin_role_permission(role_id,permission_id,is_deleted)
SELECT r.id,p.id,0
  FROM nx_admin_role r
  JOIN nx_admin_permission p ON p.permission_code='finance_d7_read'
 WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR')
   AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0
   AND NOT EXISTS (
     SELECT 1 FROM nx_admin_role_permission existing
      WHERE existing.role_id=r.id AND existing.permission_id=p.id
   );

INSERT INTO nx_admin_role_permission(role_id,permission_id,is_deleted)
SELECT r.id,p.id,0
  FROM nx_admin_role r
  JOIN nx_admin_permission p ON p.permission_code='finance_d7_manage'
 WHERE r.role_code IN ('SUPER_ADMIN','FINANCE_LEAD')
   AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0
   AND NOT EXISTS (
     SELECT 1 FROM nx_admin_role_permission existing
      WHERE existing.role_id=r.id AND existing.permission_id=p.id
   );

INSERT INTO nx_admin_role_permission(role_id,permission_id,is_deleted)
SELECT r.id,p.id,0
  FROM nx_admin_role r
  JOIN nx_admin_permission p ON p.permission_code IN ('finance_d7_channel_toggle','finance_d7_force_inverted')
 WHERE r.role_code='SUPER_ADMIN' AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0
   AND NOT EXISTS (
     SELECT 1 FROM nx_admin_role_permission existing
      WHERE existing.role_id=r.id AND existing.permission_id=p.id
   );

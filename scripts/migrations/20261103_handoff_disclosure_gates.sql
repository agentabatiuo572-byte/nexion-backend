-- Purchases and withdrawals retain their own security controls; these two
-- actions no longer require acknowledgement of the current risk disclosure.
SET NAMES utf8mb4;
START TRANSACTION;
INSERT INTO nx_disclosure_gate_action
  (action_key,action_name,description,status_label,tone,active,sort_order,last_operator,is_deleted)
VALUES
  ('staking','质押锁仓','不要求确认风险披露','REMOVED','dim',0,40,'migration:handoff-b12',0),
  ('withdraw','提现','不要求确认风险披露','REMOVED','dim',0,20,'migration:handoff-b12',0)
ON DUPLICATE KEY UPDATE active=0,status_label='REMOVED',tone='dim',last_operator='migration:handoff-b12',is_deleted=0;
INSERT INTO nx_config_item
  (config_key,config_value,value_type,config_group,visibility,remark,status,created_at,updated_at,is_deleted)
VALUES
  ('disclosure.gate.staking','false','BOOLEAN','content','ADMIN','Disclosure acknowledgement retired for staking',1,NOW(),NOW(),0),
  ('disclosure.gate.withdraw','false','BOOLEAN','content','ADMIN','Disclosure acknowledgement retired for withdrawals',1,NOW(),NOW(),0)
ON DUPLICATE KEY UPDATE config_value='false',status=1,is_deleted=0;
COMMIT;

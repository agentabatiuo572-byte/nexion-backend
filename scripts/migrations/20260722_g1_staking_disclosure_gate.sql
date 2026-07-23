USE nexion;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- I5 action rows describe per-user acknowledgement requirements. J1 remains
-- the only global staking stop; AppStakingService enforces this row server-side.
INSERT INTO nx_disclosure_gate_action
  (action_key,action_name,description,status_label,tone,active,sort_order,last_operator,is_deleted)
VALUES
  ('staking','质押锁仓','质押锁仓前必须确认当前司法辖区生效的风险披露','ACTIVE','warn',1,40,
   'migration:g1-staking-disclosure',0)
ON DUPLICATE KEY UPDATE
  action_name=VALUES(action_name),description=VALUES(description),status_label=VALUES(status_label),
  tone=VALUES(tone),active=VALUES(active),sort_order=VALUES(sort_order),
  last_operator=VALUES(last_operator),is_deleted=0;

INSERT INTO nx_config_item
  (config_key,config_value,value_type,config_group,visibility,remark,status,created_at,updated_at,is_deleted)
VALUES
  ('disclosure.gate.staking','true','BOOLEAN','content','ADMIN',
   'I5 per-user disclosure requirement; not a global staking stop',1,NOW(),NOW(),0)
ON DUPLICATE KEY UPDATE
  config_value=VALUES(config_value),value_type=VALUES(value_type),config_group=VALUES(config_group),
  visibility=VALUES(visibility),remark=VALUES(remark),status=1,is_deleted=0;

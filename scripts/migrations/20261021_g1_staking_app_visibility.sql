-- Seed the shared row lock used by the G1 admin visibility command and App opens.
-- Replaying startup migrations preserves the operator's current switch value.
INSERT INTO nx_config_item
  (config_key,config_value,value_type,config_group,visibility,remark,status,created_at,updated_at,is_deleted)
VALUES
  ('G.staking.app_visible','true','BOOLEAN','market','ADMIN',
   'App staking visibility and new-position gate',1,NOW(),NOW(),0)
ON DUPLICATE KEY UPDATE
  status=1,is_deleted=0;

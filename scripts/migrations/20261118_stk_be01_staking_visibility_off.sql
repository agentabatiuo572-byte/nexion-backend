-- STK-BE-01 forward close. Keep 20261021_g1_staking_app_visibility.sql unchanged:
-- that historical seed may run again but only re-enables/undeletes the row, never resets its value.
-- This one-time forward migration closes the shared visibility/gate key and makes the persisted
-- value agree with the already-merged Java default of false. It does not retire any other key.
INSERT INTO nx_config_item
  (config_key,config_value,value_type,config_group,visibility,remark,status,created_at,updated_at,is_deleted)
VALUES
  ('G.staking.app_visible','false','BOOLEAN','market','ADMIN',
   'App staking visibility and new-position gate',1,NOW(),NOW(),0)
ON DUPLICATE KEY UPDATE
  config_value='false',
  status=1,
  is_deleted=0,
  updated_at=NOW();

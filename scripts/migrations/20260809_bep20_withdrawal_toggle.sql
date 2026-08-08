-- Dedicated BEP20 withdrawal switch and downstream wallet mirror.
-- Preserve the historical EVM-switch behavior on first rollout: if an ERC20
-- switch already exists, BEP20 starts with the same value instead of silently
-- widening withdrawals. Metadata repair preserves later operator changes.
SET @bep20_enabled_default = COALESCE((
  SELECT config_value
    FROM nx_config_item
   WHERE config_key='withdrawal.erc20.enabled' AND status=1 AND is_deleted=0
   ORDER BY updated_at DESC
   LIMIT 1
), 'true');

INSERT INTO nx_config_item
  (config_key,config_value,value_type,config_group,visibility,remark,status,is_deleted)
VALUES
  ('withdrawal.bep20.enabled',@bep20_enabled_default,'BOOLEAN','wallet','ADMIN','D5 canonical BEP20 withdrawal switch',1,0),
  ('wallet.withdrawal.bep20.enabled',@bep20_enabled_default,'BOOLEAN','wallet','ADMIN','D5 BEP20 withdrawal switch downstream mirror',1,0)
ON DUPLICATE KEY UPDATE value_type='BOOLEAN',config_group='wallet',visibility='ADMIN',
  remark=VALUES(remark),status=1,is_deleted=0,updated_at=NOW();

-- Formal App Genesis storefront visibility is server-owned and PC-editable.
-- Seed the current visible behavior once; subsequent G4 edits are preserved.
INSERT INTO nx_config_item
  (config_key,config_value,value_type,config_group,visibility,remark,status,created_at,updated_at,is_deleted)
SELECT 'market.genesis.ops.showcase_enabled','true','BOOLEAN','MARKET_GENESIS_OPS','ADMIN',
       'G4 server-authoritative formal App Genesis storefront visibility',1,NOW(),NOW(),0
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM nx_config_item existing
   WHERE existing.config_key='market.genesis.ops.showcase_enabled'
);

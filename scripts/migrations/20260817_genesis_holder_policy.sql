-- Genesis holder projection policy. Reuses the existing G4 config authority;
-- INSERT IGNORE preserves operator-edited values and keeps the policy versioned.
INSERT IGNORE INTO nx_config_item
  (config_key,config_value,value_type,config_group,visibility,remark,status,is_deleted)
VALUES
  ('market.genesis.ops.holder.allocationNexPerHolding','80000','NUMBER','MARKET_GENESIS_OPS','ADMIN','G4 holder reserved allocation per active Genesis holding',1,0),
  ('market.genesis.ops.holder.priorityTop1Percent','1','NUMBER','MARKET_GENESIS_OPS','ADMIN','G4 holder priority top one percent threshold',1,0),
  ('market.genesis.ops.holder.priorityTop3Percent','3','NUMBER','MARKET_GENESIS_OPS','ADMIN','G4 holder priority top three percent threshold',1,0),
  ('market.genesis.ops.holder.priorityTop5Percent','5','NUMBER','MARKET_GENESIS_OPS','ADMIN','G4 holder priority top five percent threshold',1,0),
  ('market.genesis.ops.holder.policyVersion','genesis-holder-v1','STRING','MARKET_GENESIS_OPS','ADMIN','G4 holder projection policy version',1,0),
  ('market.genesis.ops.holder.effectiveAt','2026-08-17T00:00:00Z','STRING','MARKET_GENESIS_OPS','ADMIN','G4 holder projection policy UTC effective time',1,0);

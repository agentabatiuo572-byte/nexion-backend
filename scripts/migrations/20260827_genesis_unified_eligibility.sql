-- Genesis qualification has one server-authoritative policy. This migration is
-- rerunnable: it seeds absent canonical rows, preserves existing operator values,
-- and retires the former deposit/device/V-rank/invite any-of configuration.

INSERT INTO nx_config_item
  (config_key,config_value,value_type,config_group,visibility,remark,status,created_at,updated_at,is_deleted)
SELECT seed.config_key,seed.config_value,seed.value_type,'MARKET_GENESIS_OPS','ADMIN',seed.remark,1,NOW(),NOW(),0
  FROM (
    SELECT 'market.genesis.ops.eligibility.enabled' config_key,'true' config_value,'BOOLEAN' value_type,'G4 server-authoritative Genesis eligibility switch' remark
    UNION ALL SELECT 'market.genesis.ops.eligibility.minAccountAgeDays','0','INTEGER','G4 server-authoritative minimum account age in days'
    UNION ALL SELECT 'market.genesis.ops.presale.enabled','false','BOOLEAN','G4 Genesis presale switch'
    UNION ALL SELECT 'market.genesis.ops.presale.showCountdown','true','BOOLEAN','G4 Genesis presale countdown visibility'
    UNION ALL SELECT 'market.genesis.ops.presale.unitPrice','9999','NUMBER','G4 Genesis presale unit price'
    UNION ALL SELECT 'market.genesis.ops.presale.maxPerUser','5','INTEGER','G4 Genesis presale purchase cap per user'
  ) seed
 WHERE NOT EXISTS (
   SELECT 1 FROM nx_config_item existing WHERE existing.config_key=seed.config_key
 );

-- Preserve the only overlapping legacy value when an upgrade database has not
-- yet received the canonical holding-cap row. Clean installations use 5.
INSERT INTO nx_config_item
  (config_key,config_value,value_type,config_group,visibility,remark,status,created_at,updated_at,is_deleted)
SELECT
  'market.genesis.ops.eligibility.maxPerUser',
  COALESCE((
    SELECT legacy.config_value
      FROM nx_config_item legacy
     WHERE legacy.config_key='market.genesis.ops.eligibility.perUserCap'
       AND legacy.status=1 AND legacy.is_deleted=0
     LIMIT 1
  ), '5'),
  'INTEGER','MARKET_GENESIS_OPS','ADMIN','G4 server-authoritative Genesis holding cap per user',1,NOW(),NOW(),0
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM nx_config_item existing
   WHERE existing.config_key='market.genesis.ops.eligibility.maxPerUser'
);

-- Keep historical rows recoverable for audit, but remove them from every active
-- configuration read. None of these keys may grant Genesis qualification.
UPDATE nx_config_item
   SET status=0,is_deleted=1,updated_at=NOW(),
       remark='RETIRED 2026-08-27: legacy Genesis any-of qualification field; no runtime authority'
 WHERE config_key IN (
   'market.genesis.ops.eligibility.mode',
   'market.genesis.ops.eligibility.minDepositUsdt',
   'market.genesis.ops.eligibility.flagshipMin',
   'market.genesis.ops.eligibility.vRankMin',
   'market.genesis.ops.eligibility.inviteEnabled',
   'market.genesis.ops.eligibility.perUserCap',
   'market.genesis.ops.eligibility.appliesTo'
 );

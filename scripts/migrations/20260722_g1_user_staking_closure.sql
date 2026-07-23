USE nexion;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- G1 user staking mutations are emitted only after the wallet, position and D4
-- ledger rows have committed in one server-authoritative transaction.
INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('staking.opened','market','staking','server','G1/D4/A2/A4/App',1,'100%',62,
   'ACTIVE','migration:g1-user-staking','Server-canonical staking position opened',0),
  ('staking.claimed','market','staking','server','G1/D4/A2/A4/App',1,'100%',63,
   'ACTIVE','migration:g1-user-staking','Server-canonical matured staking claim',0),
  ('staking.early_withdrawn','market','staking','server','G1/D4/A2/A4/App',1,'100%',64,
   'ACTIVE','migration:g1-user-staking','Server-canonical early staking withdrawal',0)
ON DUPLICATE KEY UPDATE owner_domain=VALUES(owner_domain),family_key=VALUES(family_key),
  producer='server',consumers=VALUES(consumers),is_server_authoritative=1,
  sampling_policy='100%',current_revision=VALUES(current_revision),status='ACTIVE',
  updated_by='migration:g1-user-staking',reason=VALUES(reason),is_deleted=0;

UPDATE nx_event_schema_property property_row
JOIN nx_event_schema_registry schema_row ON schema_row.id=property_row.schema_id
   SET property_row.is_deleted=1,property_row.updated_at=NOW()
 WHERE schema_row.event_name IN ('staking.opened','staking.claimed','staking.early_withdrawn')
   AND NOT EXISTS (
     SELECT 1 FROM (
       SELECT 'staking.opened' event_name,'position_no' property_name UNION ALL
       SELECT 'staking.opened','tier_key' UNION ALL
       SELECT 'staking.opened','product_code' UNION ALL
       SELECT 'staking.opened','amount_usdt' UNION ALL
       SELECT 'staking.opened','apy_pct' UNION ALL
       SELECT 'staking.opened','term_days' UNION ALL
       SELECT 'staking.opened','unlock_at' UNION ALL
       SELECT 'staking.opened','wallet_balance_usdt' UNION ALL
       SELECT 'staking.claimed','position_no' UNION ALL
       SELECT 'staking.claimed','principal_usdt' UNION ALL
       SELECT 'staking.claimed','interest_usdt' UNION ALL
       SELECT 'staking.claimed','credited_usdt' UNION ALL
       SELECT 'staking.claimed','wallet_balance_usdt' UNION ALL
       SELECT 'staking.early_withdrawn','position_no' UNION ALL
       SELECT 'staking.early_withdrawn','principal_usdt' UNION ALL
       SELECT 'staking.early_withdrawn','penalty_usdt' UNION ALL
       SELECT 'staking.early_withdrawn','credited_usdt' UNION ALL
       SELECT 'staking.early_withdrawn','forfeited_interest_usdt' UNION ALL
       SELECT 'staking.early_withdrawn','wallet_balance_usdt'
     ) expected
     WHERE expected.event_name=schema_row.event_name
       AND expected.property_name=property_row.property_name);

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT schema_row.id,property_row.property_name,property_row.property_type,0,1,schema_row.current_revision,0
  FROM nx_event_schema_registry schema_row
  JOIN (
    SELECT 'staking.opened' event_name,'position_no' property_name,'id' property_type UNION ALL
    SELECT 'staking.opened','tier_key','string' UNION ALL
    SELECT 'staking.opened','product_code','id' UNION ALL
    SELECT 'staking.opened','amount_usdt','number' UNION ALL
    SELECT 'staking.opened','apy_pct','number' UNION ALL
    SELECT 'staking.opened','term_days','number' UNION ALL
    SELECT 'staking.opened','unlock_at','timestamp' UNION ALL
    SELECT 'staking.opened','wallet_balance_usdt','number' UNION ALL
    SELECT 'staking.claimed','position_no','id' UNION ALL
    SELECT 'staking.claimed','principal_usdt','number' UNION ALL
    SELECT 'staking.claimed','interest_usdt','number' UNION ALL
    SELECT 'staking.claimed','credited_usdt','number' UNION ALL
    SELECT 'staking.claimed','wallet_balance_usdt','number' UNION ALL
    SELECT 'staking.early_withdrawn','position_no','id' UNION ALL
    SELECT 'staking.early_withdrawn','principal_usdt','number' UNION ALL
    SELECT 'staking.early_withdrawn','penalty_usdt','number' UNION ALL
    SELECT 'staking.early_withdrawn','credited_usdt','number' UNION ALL
    SELECT 'staking.early_withdrawn','forfeited_interest_usdt','number' UNION ALL
    SELECT 'staking.early_withdrawn','wallet_balance_usdt','number'
  ) property_row ON property_row.event_name=schema_row.event_name
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_domain_extension
  (domain_name,event_name,producer,consumer,status,created_by,reason,is_deleted)
VALUES
  ('market','staking.opened','AppStakingService','G1/D4/A2/A4/App','REGISTERED',
   'migration:g1-user-staking','G1 staking open downstream contract',0),
  ('market','staking.claimed','AppStakingService','G1/D4/A2/A4/App','REGISTERED',
   'migration:g1-user-staking','G1 staking claim downstream contract',0),
  ('market','staking.early_withdrawn','AppStakingService','G1/D4/A2/A4/App','REGISTERED',
   'migration:g1-user-staking','G1 early withdrawal downstream contract',0)
ON DUPLICATE KEY UPDATE producer=VALUES(producer),consumer=VALUES(consumer),status='REGISTERED',
  reason=VALUES(reason),is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,64)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,64);

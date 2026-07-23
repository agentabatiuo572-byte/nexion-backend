USE nexion;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- G1 writes execute immediately after Confirm-with-Reason. Business state,
-- required A2 audit, durable idempotency and these outbox events share one transaction.
INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('admin.staking_pool_config_changed','admin','phase_admin','server','G1/B1/A2/A4/App',1,'100%',94,
   'ACTIVE','migration:g1-admin-staking','G1 pool parameter changed immediately',0),
  ('admin.staking_pool_enabled_changed','admin','phase_admin','server','G1/B1/J1/A2/A4/App',1,'100%',95,
   'ACTIVE','migration:g1-admin-staking','G1 pool sale state changed immediately',0),
  ('admin.staking_pool_killed','admin','phase_admin','server','G1/J1/B1/A2/A4/App',1,'100%',96,
   'ACTIVE','migration:g1-admin-staking','G1 pool killed with structured position disposition',0)
ON DUPLICATE KEY UPDATE owner_domain=VALUES(owner_domain),family_key=VALUES(family_key),
  producer='server',consumers=VALUES(consumers),is_server_authoritative=1,
  sampling_policy='100%',current_revision=VALUES(current_revision),status='ACTIVE',
  updated_by='migration:g1-admin-staking',reason=VALUES(reason),is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name IN (
   'admin.staking_pool_config_changed','admin.staking_pool_enabled_changed','admin.staking_pool_killed')
   AND NOT EXISTS (
     SELECT 1 FROM (
       SELECT 'admin.staking_pool_config_changed' event_name,'tier_key' property_name UNION ALL
       SELECT 'admin.staking_pool_config_changed','field' UNION ALL
       SELECT 'admin.staking_pool_config_changed','value' UNION ALL
       SELECT 'admin.staking_pool_config_changed','reason' UNION ALL
       SELECT 'admin.staking_pool_config_changed','operator' UNION ALL
       SELECT 'admin.staking_pool_enabled_changed','tier_key' UNION ALL
       SELECT 'admin.staking_pool_enabled_changed','enabled' UNION ALL
       SELECT 'admin.staking_pool_enabled_changed','reason' UNION ALL
       SELECT 'admin.staking_pool_enabled_changed','operator' UNION ALL
       SELECT 'admin.staking_pool_killed','tier_key' UNION ALL
       SELECT 'admin.staking_pool_killed','trigger_basis' UNION ALL
       SELECT 'admin.staking_pool_killed','disposition_plan' UNION ALL
       SELECT 'admin.staking_pool_killed','affected_positions' UNION ALL
       SELECT 'admin.staking_pool_killed','reason' UNION ALL
       SELECT 'admin.staking_pool_killed','operator' UNION ALL
       SELECT 'admin.staking_pool_killed','restoration_domain'
     ) expected
     WHERE expected.event_name=s.event_name AND expected.property_name=p.property_name);

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,x.property_name,x.property_type,0,1,s.current_revision,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'admin.staking_pool_config_changed' event_name,'tier_key' property_name,'string' property_type UNION ALL
    SELECT 'admin.staking_pool_config_changed','field','string' UNION ALL
    SELECT 'admin.staking_pool_config_changed','value','string' UNION ALL
    SELECT 'admin.staking_pool_config_changed','reason','string' UNION ALL
    SELECT 'admin.staking_pool_config_changed','operator','string' UNION ALL
    SELECT 'admin.staking_pool_enabled_changed','tier_key','string' UNION ALL
    SELECT 'admin.staking_pool_enabled_changed','enabled','boolean' UNION ALL
    SELECT 'admin.staking_pool_enabled_changed','reason','string' UNION ALL
    SELECT 'admin.staking_pool_enabled_changed','operator','string' UNION ALL
    SELECT 'admin.staking_pool_killed','tier_key','string' UNION ALL
    SELECT 'admin.staking_pool_killed','trigger_basis','string' UNION ALL
    SELECT 'admin.staking_pool_killed','disposition_plan','string' UNION ALL
    SELECT 'admin.staking_pool_killed','affected_positions','number' UNION ALL
    SELECT 'admin.staking_pool_killed','reason','string' UNION ALL
    SELECT 'admin.staking_pool_killed','operator','string' UNION ALL
    SELECT 'admin.staking_pool_killed','restoration_domain','string'
  ) x ON x.event_name=s.event_name
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_domain_extension
  (domain_name,event_name,producer,consumer,status,created_by,reason,is_deleted)
SELECT 'admin',s.event_name,'G1AdminCommandService','G1/A2/A4','REGISTERED',
       'migration:g1-admin-staking','G1 admin command event contract',0
  FROM nx_event_schema_registry s
 WHERE s.event_name IN (
   'admin.staking_pool_config_changed','admin.staking_pool_enabled_changed','admin.staking_pool_killed')
ON DUPLICATE KEY UPDATE producer=VALUES(producer),consumer=VALUES(consumer),status='REGISTERED',
  reason=VALUES(reason),is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,96)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,96);

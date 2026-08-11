-- F15: the fail-closed leadership-pool configuration boundary must remain
-- observable. Register the canonical server-generated alert before the
-- scheduler can persist its required audit and outbox evidence.
SET NAMES utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,updated_by,reason,is_deleted)
VALUES
  ('leadership_pool.settlement_blocked','leadership_pool','operations','LeadershipPoolConfigAlertService',
   'A4/F4/F15',1,'100%',305,'ACTIVE',
   'migration:f15-leadership-pool-config-blocked-event-schema',
   'migration:f15-leadership-pool-config-blocked-event-schema',
   'Server-authoritative F15 fail-closed leadership-pool configuration alert',0)
ON DUPLICATE KEY UPDATE
  owner_domain=IF(current_revision<=305,VALUES(owner_domain),owner_domain),
  family_key=IF(current_revision<=305,VALUES(family_key),family_key),
  producer=IF(current_revision<=305,VALUES(producer),producer),
  consumers=IF(current_revision<=305,VALUES(consumers),consumers),
  is_server_authoritative=IF(current_revision<=305,VALUES(is_server_authoritative),is_server_authoritative),
  sampling_policy=IF(current_revision<=305,VALUES(sampling_policy),sampling_policy),
  status=IF(current_revision<=305,VALUES(status),status),
  updated_by=IF(current_revision<=305,VALUES(updated_by),updated_by),
  reason=IF(current_revision<=305,VALUES(reason),reason),
  is_deleted=IF(current_revision<=305,VALUES(is_deleted),is_deleted),
  updated_at=IF(current_revision<=305,NOW(),updated_at),
  current_revision=GREATEST(current_revision,305);

-- Do not rewrite a newer contract. For this exact revision, require the
-- complete non-sensitive failure signature emitted by the alert producer.
UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name='leadership_pool.settlement_blocked'
   AND s.current_revision=305
   AND p.property_name NOT IN (
     'source',
     'config_key',
     'reason',
     'value_fingerprint',
     'blocked_at'
   );

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,
   created_at,updated_at,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,305,NOW(),NOW(),0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'source' property_name,'enum' property_type,1 required_field UNION ALL
    SELECT 'config_key','string',1 UNION ALL
    SELECT 'reason','string',1 UNION ALL
    SELECT 'value_fingerprint','string',1 UNION ALL
    SELECT 'blocked_at','timestamp',1
  ) p
 WHERE s.event_name='leadership_pool.settlement_blocked'
   AND s.current_revision=305
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=VALUES(required_field),
  registry_revision=305,is_deleted=0,updated_at=NOW();

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,305)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,305);

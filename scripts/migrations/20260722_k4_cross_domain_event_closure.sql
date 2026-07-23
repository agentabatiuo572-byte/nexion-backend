-- K4 cross-domain closure: register the two durable score facts emitted to A4.
SET NAMES utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('risk.score_updated','risk','risk','K4ScoreEventPublisher','A4/B5/D2/C1',1,
   '100%',271,'ACTIVE','migration:k4-cross-domain','K4 score changed; changed_dimensions elements are exactly {dim,hit,contribution}',0),
  ('risk.score_overridden','risk','risk','K4ScoreEventPublisher','A4/B5/D2/C1',1,
   '100%',272,'ACTIVE','migration:k4-cross-domain','K4 manual effective score override applied',0)
ON DUPLICATE KEY UPDATE
  owner_domain=VALUES(owner_domain),family_key=VALUES(family_key),producer=VALUES(producer),
  consumers=VALUES(consumers),is_server_authoritative=1,sampling_policy='100%',
  current_revision=VALUES(current_revision),status='ACTIVE',updated_by='migration:k4-cross-domain',
  reason=VALUES(reason),is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name IN ('risk.score_updated','risk.score_overridden')
   AND NOT EXISTS (
       SELECT 1
         FROM (
           SELECT 'risk.score_updated' event_name,'score' property_name UNION ALL
           SELECT 'risk.score_updated','band' UNION ALL
           SELECT 'risk.score_updated','changed_dimensions' UNION ALL
           SELECT 'risk.score_updated','model_version' UNION ALL
           SELECT 'risk.score_overridden','override_score' UNION ALL
           SELECT 'risk.score_overridden','reason' UNION ALL
           SELECT 'risk.score_overridden','operator'
         ) expected
        WHERE expected.event_name=s.event_name
          AND expected.property_name=p.property_name
   );

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,s.current_revision,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'risk.score_updated' event_name,'score' property_name,'number' property_type,1 required_field UNION ALL
    SELECT 'risk.score_updated','band','enum',1 UNION ALL
    SELECT 'risk.score_updated','changed_dimensions','json',1 UNION ALL
    SELECT 'risk.score_updated','model_version','string',1 UNION ALL
    SELECT 'risk.score_overridden','override_score','number',1 UNION ALL
    SELECT 'risk.score_overridden','reason','string',1 UNION ALL
    SELECT 'risk.score_overridden','operator','string',1
  ) p ON p.event_name=s.event_name
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=VALUES(required_field),
  registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,272)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,272);

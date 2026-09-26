-- The claim publisher includes instance_key for every quest and
-- required_task_count for Day One. An older four-field A4 contract rejects
-- those fields after wallet credit, rolling the whole claim back with 422.
-- Keep operator retirement, deletion, and newer revisions intact.
SET NAMES utf8mb4;
START TRANSACTION;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('quest.claimed','quest','engagement','AppGrowthEngagementService','A4/H3/D4',1,
   '100%',20260927,'ACTIVE','migration:h3-quest-claim-schema',
   'H3 quest claim instance attribution',0)
ON DUPLICATE KEY UPDATE event_name=VALUES(event_name);

UPDATE nx_event_schema_registry
   SET current_revision=20260927,updated_by='migration:h3-quest-claim-schema',
       reason='H3 quest claim instance attribution'
 WHERE event_name='quest.claimed' AND current_revision<20260927
   AND status='ACTIVE' AND is_deleted=0;

-- Carry forward active operator properties when upgrading this one event.
UPDATE nx_event_schema_property p
  JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.registry_revision=20260927
 WHERE s.event_name='quest.claimed' AND s.current_revision=20260927
   AND s.status='ACTIVE' AND s.is_deleted=0
   AND p.is_deleted=0 AND p.registry_revision<20260927;

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,20260927,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'layer' property_name,'enum' property_type,1 required_field UNION ALL
    SELECT 'reward_nex','number',1 UNION ALL
    SELECT 'multiplier','number',1 UNION ALL
    SELECT 'rhythm_month','number',1 UNION ALL
    SELECT 'instance_key','id',1 UNION ALL
    SELECT 'required_task_count','number',0
  ) p
 WHERE s.event_name='quest.claimed' AND s.current_revision=20260927
   AND s.status='ACTIVE' AND s.is_deleted=0
ON DUPLICATE KEY UPDATE
  property_type=IF(nx_event_schema_property.is_deleted=0
                   AND nx_event_schema_property.registry_revision<=20260927,
                   VALUES(property_type),nx_event_schema_property.property_type),
  pii=IF(nx_event_schema_property.is_deleted=0
         AND nx_event_schema_property.registry_revision<=20260927,
         0,nx_event_schema_property.pii),
  required_field=IF(nx_event_schema_property.is_deleted=0
                    AND nx_event_schema_property.registry_revision<=20260927,
                    VALUES(required_field),nx_event_schema_property.required_field),
  registry_revision=IF(nx_event_schema_property.is_deleted=0,
                       GREATEST(nx_event_schema_property.registry_revision,20260927),
                       nx_event_schema_property.registry_revision);

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,20260927)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,20260927);

COMMIT;

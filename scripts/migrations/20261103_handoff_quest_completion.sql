-- The Day One completion producer includes the frozen instance key.
SET NAMES utf8mb4;
START TRANSACTION;
INSERT IGNORE INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES ('quest.completed','quest','engagement','QuestCompletionFactConsumer','A4/H3',1,
        '100%',20261103,'ACTIVE','migration:h3-completion-instance','Quest completion for a frozen instance',0);
UPDATE nx_event_schema_registry
   SET current_revision=20261103,updated_by='migration:h3-completion-instance'
 WHERE event_name='quest.completed' AND current_revision<20261103 AND status='ACTIVE' AND is_deleted=0;
UPDATE nx_event_schema_property p JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.registry_revision=20261103
 WHERE s.event_name='quest.completed' AND s.current_revision=20261103 AND s.status='ACTIVE' AND s.is_deleted=0
   AND p.is_deleted=0 AND p.registry_revision<20261103;
INSERT IGNORE INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,20261103,0
  FROM nx_event_schema_registry s
  JOIN (SELECT 'quest_id' property_name,'id' property_type,1 required_field UNION ALL
        SELECT 'layer','enum',1 UNION ALL SELECT 'producer','enum',1 UNION ALL
        SELECT 'source_event_id','id',1 UNION ALL SELECT 'instance_key','id',0) p
 WHERE s.event_name='quest.completed' AND s.current_revision=20261103 AND s.status='ACTIVE' AND s.is_deleted=0;
INSERT INTO nx_event_schema_revision(id,current_revision) VALUES(1,20261103)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,VALUES(current_revision));
COMMIT;

-- M5 -> A4 closure: govern conversation category, AutoPush, script and template mutations.
SET NAMES utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('admin.conversation_category_toggled','admin','phase_admin','OpsSessionTemplateService',
   'A2/A4/M1/M3/M5',1,'100%',300,'ACTIVE',
   'migration:m5-conversation-governance','M5 conversation category governance fact',0),
  ('admin.conversation_autopush_toggled','admin','phase_admin','OpsSessionTemplateService',
   'A2/A4/M1/M3/M5',1,'100%',300,'ACTIVE',
   'migration:m5-conversation-governance','M5 advisor AutoPush enablement governance fact',0),
  ('admin.conversation_autopush_changed','admin','phase_admin','OpsSessionTemplateService',
   'A2/A4/M1/M3/M5',1,'100%',300,'ACTIVE',
   'migration:m5-conversation-governance','M5 advisor AutoPush policy governance fact',0),
  ('admin.conversation_script_published','admin','phase_admin','OpsSessionTemplateService',
   'A2/A4/I6/M1/M3/M5',1,'100%',300,'ACTIVE',
   'migration:m5-conversation-governance','M5 advisor script publication governance fact',0),
  ('admin.conversation_template_published','admin','phase_admin','OpsSessionTemplateService',
   'A2/A4/I6/M1/M3/M5',1,'100%',300,'ACTIVE',
   'migration:m5-conversation-governance','M5 reply template publication governance fact',0)
ON DUPLICATE KEY UPDATE
  owner_domain='admin',family_key='phase_admin',producer='OpsSessionTemplateService',
  consumers=VALUES(consumers),is_server_authoritative=1,sampling_policy='100%',
  current_revision=300,status='ACTIVE',updated_by='migration:m5-conversation-governance',
  reason=VALUES(reason),is_deleted=0,updated_at=NOW();

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name IN (
     'admin.conversation_category_toggled',
     'admin.conversation_autopush_toggled',
     'admin.conversation_autopush_changed',
     'admin.conversation_script_published',
     'admin.conversation_template_published'
   )
   AND p.property_name NOT IN ('target_id','from_status','to_status','operator','reason');

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,
   created_at,updated_at,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,300,NOW(),NOW(),0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'target_id' property_name,'id' property_type UNION ALL
    SELECT 'from_status','string' UNION ALL
    SELECT 'to_status','string' UNION ALL
    SELECT 'operator','string' UNION ALL
    SELECT 'reason','string'
  ) p
 WHERE s.event_name IN (
     'admin.conversation_category_toggled',
     'admin.conversation_autopush_toggled',
     'admin.conversation_autopush_changed',
     'admin.conversation_script_published',
     'admin.conversation_template_published'
   )
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=300,is_deleted=0,updated_at=NOW();

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,300)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,300);

-- I6 -> A4 closure: govern published/archived multilingual message facts.
SET NAMES utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('admin.i18n_published','admin','phase_admin','OpsI18nLearningService',
   'A2/A4/I1/I2/I4/I5/App',1,'100%',279,'ACTIVE',
   'migration:i6-i18n-governance',
   'I6 canonical multilingual message version published',0),
  ('admin.i18n_rolledback','admin','phase_admin','OpsI18nLearningService',
   'A2/A4/I1/I2/I4/I5/App',1,'100%',279,'ACTIVE',
   'migration:i6-i18n-governance',
   'I6 canonical multilingual message version archived or rolled back',0)
ON DUPLICATE KEY UPDATE
  owner_domain='admin',family_key='phase_admin',producer='OpsI18nLearningService',
  consumers='A2/A4/I1/I2/I4/I5/App',is_server_authoritative=1,sampling_policy='100%',
  current_revision=279,status='ACTIVE',updated_by='migration:i6-i18n-governance',
  reason=VALUES(reason),is_deleted=0,updated_at=NOW();

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name='admin.i18n_published'
   AND p.property_name NOT IN ('message_key','version','locale_set','operator');

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,
   created_at,updated_at,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,279,NOW(),NOW(),0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'message_key' property_name,'id' property_type UNION ALL
    SELECT 'version','string' UNION ALL
    SELECT 'locale_set','string' UNION ALL
    SELECT 'operator','string'
  ) p
 WHERE s.event_name='admin.i18n_published'
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=279,is_deleted=0,updated_at=NOW();

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name='admin.i18n_rolledback'
   AND p.property_name NOT IN (
     'message_key','from_version','to_status','to_version','published_version','locale_set','operator'
   );

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,
   created_at,updated_at,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,279,NOW(),NOW(),0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'message_key' property_name,'id' property_type,1 required_field UNION ALL
    SELECT 'from_version','string',1 UNION ALL
    SELECT 'to_status','enum',0 UNION ALL
    SELECT 'to_version','string',0 UNION ALL
    SELECT 'published_version','string',0 UNION ALL
    SELECT 'locale_set','string',1 UNION ALL
    SELECT 'operator','string',1
  ) p
 WHERE s.event_name='admin.i18n_rolledback'
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=VALUES(required_field),
  registry_revision=279,is_deleted=0,updated_at=NOW();

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,279)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,279);

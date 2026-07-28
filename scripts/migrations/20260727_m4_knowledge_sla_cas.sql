-- M4 knowledge/SLA closure: versioned SLA CAS and canonical FAQ mutation events.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_support_sla_rule' AND COLUMN_NAME='version') = 0,
  'ALTER TABLE nx_support_sla_rule ADD COLUMN version BIGINT NOT NULL DEFAULT 1 AFTER escalation',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('admin.support_faq_updated','admin','phase_admin','M4 support knowledge service',
   'A2/A4/M1/M2/I6',1,'100%',290,'ACTIVE',
   'migration:m4-knowledge-sla-cas',
   'Server-authoritative FAQ mutation after CAS with mandatory audit and outbox',0)
ON DUPLICATE KEY UPDATE
  owner_domain='admin',family_key='phase_admin',producer='M4 support knowledge service',
  consumers='A2/A4/M1/M2/I6',is_server_authoritative=1,sampling_policy='100%',
  current_revision=GREATEST(current_revision,290),status='ACTIVE',
  updated_by='migration:m4-knowledge-sla-cas',reason=VALUES(reason),
  is_deleted=0,updated_at=NOW();

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name='admin.support_faq_updated'
   AND p.property_name NOT IN
     ('faq_id','action','category','status','version','language','surface','operator','reason');

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,
   created_at,updated_at,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,290,NOW(),NOW(),0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'faq_id' property_name,'id' property_type UNION ALL
    SELECT 'action','enum' UNION ALL
    SELECT 'category','enum' UNION ALL
    SELECT 'status','enum' UNION ALL
    SELECT 'version','number' UNION ALL
    SELECT 'language','enum' UNION ALL
    SELECT 'surface','enum' UNION ALL
    SELECT 'operator','string' UNION ALL
    SELECT 'reason','string'
  ) p
 WHERE s.event_name='admin.support_faq_updated'
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=290,is_deleted=0,updated_at=NOW();

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,290)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,290);

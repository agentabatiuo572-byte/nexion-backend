-- J4 -> A4 closure: authoritative playbook edits and terminal execution outcomes.
SET NAMES utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('admin.emergency_playbook_edited','admin','phase_admin','OpsEmergencyControlService',
   'A2/A4/J1/J2/C2/K1/I3/I5',1,'100%',281,'ACTIVE',
   'migration:j4-emergency-playbook',
   'J4 authoritative playbook definition created or updated',0),
  ('admin.emergency_playbook_executed','admin','phase_admin','OpsEmergencyControlService',
   'A2/A4/J1/J2/C2/K1/I3/I5',1,'100%',282,'ACTIVE',
   'migration:j4-emergency-playbook',
   'J4 terminal completed or partial execution outcome',0)
ON DUPLICATE KEY UPDATE
  owner_domain='admin',family_key='phase_admin',producer='OpsEmergencyControlService',
  consumers='A2/A4/J1/J2/C2/K1/I3/I5',is_server_authoritative=1,sampling_policy='100%',
  current_revision=VALUES(current_revision),status='ACTIVE',
  updated_by='migration:j4-emergency-playbook',reason=VALUES(reason),
  is_deleted=0,updated_at=NOW();

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name='admin.emergency_playbook_edited'
   AND p.property_name NOT IN (
     'playbook_code','operation','operator','reason','idempotency_key'
   );

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,
   created_at,updated_at,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,s.current_revision,NOW(),NOW(),0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'playbook_code' property_name,'id' property_type UNION ALL
    SELECT 'operation','enum' UNION ALL
    SELECT 'operator','string' UNION ALL
    SELECT 'reason','string' UNION ALL
    SELECT 'idempotency_key','id'
  ) p
 WHERE s.event_name='admin.emergency_playbook_edited'
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=VALUES(registry_revision),is_deleted=0,updated_at=NOW();

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name='admin.emergency_playbook_executed'
   AND p.property_name NOT IN (
     'playbook_code','execution_id','outcome','operator','reason',
     'idempotency_key','trigger_basis','step_count'
   );

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,
   created_at,updated_at,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,s.current_revision,NOW(),NOW(),0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'playbook_code' property_name,'id' property_type UNION ALL
    SELECT 'execution_id','id' UNION ALL
    SELECT 'outcome','enum' UNION ALL
    SELECT 'operator','string' UNION ALL
    SELECT 'reason','string' UNION ALL
    SELECT 'idempotency_key','id' UNION ALL
    SELECT 'trigger_basis','enum' UNION ALL
    SELECT 'step_count','number'
  ) p
 WHERE s.event_name='admin.emergency_playbook_executed'
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=VALUES(registry_revision),is_deleted=0,updated_at=NOW();

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,282)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,282);

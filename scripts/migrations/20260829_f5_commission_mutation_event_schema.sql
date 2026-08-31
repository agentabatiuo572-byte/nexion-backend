-- F5 mutation events are part of the same transaction as commission state and
-- wallet ledger writes. Register all exact payloads so the A4 fail-closed gate
-- does not turn valid A2-approved reverse/reissue/suspension operations into a
-- full transaction rollback.
SET NAMES utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,updated_by,reason,is_deleted)
VALUES
  ('admin.commission_reversed','admin','phase_admin','F5CommissionService','A2/A4/D4/F5',1,'100%',313,'ACTIVE',
   'migration:f5-commission-mutation-event-schema','migration:f5-commission-mutation-event-schema',
   'A2-approved commission reversal with wallet debit',0),
  ('admin.commission_reissued','admin','phase_admin','F5CommissionService','A2/A4/D4/F5',1,'100%',313,'ACTIVE',
   'migration:f5-commission-mutation-event-schema','migration:f5-commission-mutation-event-schema',
   'A2-approved commission reissue with wallet credit',0),
  ('admin.commission_user_suspended','admin','phase_admin','F5CommissionService','A2/A4/F5',1,'100%',313,'ACTIVE',
   'migration:f5-commission-mutation-event-schema','migration:f5-commission-mutation-event-schema',
   'A2-approved user commission suspension',0),
  ('admin.commission_user_resumed','admin','phase_admin','F5CommissionService','A2/A4/F5',1,'100%',313,'ACTIVE',
   'migration:f5-commission-mutation-event-schema','migration:f5-commission-mutation-event-schema',
   'A2-approved user commission resume',0)
ON DUPLICATE KEY UPDATE
  owner_domain=IF(current_revision<=313,VALUES(owner_domain),owner_domain),
  family_key=IF(current_revision<=313,VALUES(family_key),family_key),
  producer=IF(current_revision<=313,VALUES(producer),producer),
  consumers=IF(current_revision<=313,VALUES(consumers),consumers),
  is_server_authoritative=IF(current_revision<=313,VALUES(is_server_authoritative),is_server_authoritative),
  sampling_policy=IF(current_revision<=313,VALUES(sampling_policy),sampling_policy),
  status=IF(current_revision<=313,VALUES(status),status),
  updated_by=IF(current_revision<=313,VALUES(updated_by),updated_by),
  reason=IF(current_revision<=313,VALUES(reason),reason),
  is_deleted=IF(current_revision<=313,VALUES(is_deleted),is_deleted),
  updated_at=IF(current_revision<=313,NOW(),updated_at),
  current_revision=GREATEST(current_revision,313);

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name='admin.commission_reversed'
   AND s.current_revision=313
   AND p.property_name NOT IN
       ('commission_id','user_id','kind','amount','currency','refund_ref','operator','reason');

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name='admin.commission_reissued'
   AND s.current_revision=313
   AND p.property_name NOT IN
       ('batch_no','commission_ids','count','amount','operator','reason');

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name IN ('admin.commission_user_suspended','admin.commission_user_resumed')
   AND s.current_revision=313
   AND p.property_name NOT IN
       ('user_id','kinds','suspended','frozen_open_events','operator','reason');

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,
   created_at,updated_at,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,313,NOW(),NOW(),0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'admin.commission_reversed' event_name,'commission_id' property_name,'id' property_type,1 required_field UNION ALL
    SELECT 'admin.commission_reversed','user_id','id',1 UNION ALL
    SELECT 'admin.commission_reversed','kind','enum',1 UNION ALL
    SELECT 'admin.commission_reversed','amount','number',1 UNION ALL
    SELECT 'admin.commission_reversed','currency','enum',1 UNION ALL
    SELECT 'admin.commission_reversed','refund_ref','string',1 UNION ALL
    SELECT 'admin.commission_reversed','operator','string',1 UNION ALL
    SELECT 'admin.commission_reversed','reason','string',1 UNION ALL
    SELECT 'admin.commission_reissued','batch_no','id',1 UNION ALL
    SELECT 'admin.commission_reissued','commission_ids','json',1 UNION ALL
    SELECT 'admin.commission_reissued','count','number',1 UNION ALL
    SELECT 'admin.commission_reissued','amount','number',1 UNION ALL
    SELECT 'admin.commission_reissued','operator','string',1 UNION ALL
    SELECT 'admin.commission_reissued','reason','string',1 UNION ALL
    SELECT 'admin.commission_user_suspended','user_id','id',1 UNION ALL
    SELECT 'admin.commission_user_suspended','kinds','json',1 UNION ALL
    SELECT 'admin.commission_user_suspended','suspended','boolean',1 UNION ALL
    SELECT 'admin.commission_user_suspended','frozen_open_events','number',1 UNION ALL
    SELECT 'admin.commission_user_suspended','operator','string',1 UNION ALL
    SELECT 'admin.commission_user_suspended','reason','string',1 UNION ALL
    SELECT 'admin.commission_user_resumed','user_id','id',1 UNION ALL
    SELECT 'admin.commission_user_resumed','kinds','json',1 UNION ALL
    SELECT 'admin.commission_user_resumed','suspended','boolean',1 UNION ALL
    SELECT 'admin.commission_user_resumed','frozen_open_events','number',1 UNION ALL
    SELECT 'admin.commission_user_resumed','operator','string',1 UNION ALL
    SELECT 'admin.commission_user_resumed','reason','string',1
  ) p ON p.event_name=s.event_name
 WHERE s.current_revision=313
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=VALUES(required_field),
  registry_revision=313,is_deleted=0,updated_at=NOW();

INSERT INTO nx_event_schema_revision (id,current_revision)
VALUES (1,313)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,313);

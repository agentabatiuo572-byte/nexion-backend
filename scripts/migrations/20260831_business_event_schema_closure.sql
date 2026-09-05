-- Register the actual business facts emitted by the six audited publishers.
-- Schema gates stay strict; deployment must not undo operator retirement,
-- deletion, lifecycle restrictions or a newer contract revision.
SET NAMES utf8mb4;
START TRANSACTION;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,updated_by,reason,is_deleted)
VALUES
  ('auth.password_reset_completed','auth','auth','AppUserPasswordResetService','A4',1,'100%',316,'ACTIVE',
   'migration:business-event-closure','migration:business-event-closure','OTP password reset completed',0),
  ('capacity_replacement.completed','device','tradein','AppTradeinService','A4',1,'100%',316,'ACTIVE',
   'migration:business-event-closure','migration:business-event-closure','Capacity replacement completed',0),
  ('task.completed','device','task','AppTaskAssignmentService','A4/DeveloperWebhook',1,'100%',316,'ACTIVE',
   'migration:business-event-closure','migration:business-event-closure','Verified task completed',0),
  ('earnings.credited','finance','earnings','AppTaskAssignmentService','A4/I2/L4/DeveloperWebhook',1,'100%',316,'ACTIVE',
   'migration:business-event-closure','migration:business-event-closure','Verified task earnings credited',0),
  ('genesis.emission_paid','market','genesis','G4AdminCommandService','A4',1,'100%',316,'ACTIVE',
   'migration:business-event-closure','migration:business-event-closure','Genesis holding emission paid',0),
  ('admin.staking_pool_restored','admin','phase_admin','G1AdminCommandService','A4',1,'100%',316,'ACTIVE',
   'migration:business-event-closure','migration:business-event-closure','J1-reviewed staking pool restoration',0)
ON DUPLICATE KEY UPDATE event_name=VALUES(event_name);

UPDATE nx_event_schema_registry
   SET current_revision=316,is_server_authoritative=1,sampling_policy='100%',
       updated_by='migration:business-event-closure',reason='Canonical business publisher contract closure'
 WHERE event_name IN ('auth.password_reset_completed','capacity_replacement.completed','task.completed',
                      'earnings.credited','genesis.emission_paid','admin.staking_pool_restored')
   AND current_revision<316 AND status='ACTIVE' AND is_deleted=0;

-- No passwords, OTPs, session tokens, or raw contact fields belong in these facts.
-- user_id and attribution use the existing shared envelope, not duplicate properties.
INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,p.pii,1,316,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'auth.password_reset_completed' event_name,'revoked_session_count' property_name,'number' property_type,0 pii UNION ALL
    SELECT 'capacity_replacement.completed','tradein_no','id',0 UNION ALL
    SELECT 'capacity_replacement.completed','source_device_id','id',0 UNION ALL
    SELECT 'capacity_replacement.completed','target_device_id','id',0 UNION ALL
    SELECT 'capacity_replacement.completed','order_no','id',0 UNION ALL
    SELECT 'capacity_replacement.completed','wallet_debit_usdt','number',0 UNION ALL
    SELECT 'capacity_replacement.completed','operation','enum',0 UNION ALL
    SELECT 'task.completed','task_id','id',0 UNION ALL
    SELECT 'task.completed','task_no','id',0 UNION ALL
    SELECT 'task.completed','device_id','id',0 UNION ALL
    SELECT 'task.completed','receipt_no','id',0 UNION ALL
    SELECT 'task.completed','amount_usdt','number',0 UNION ALL
    SELECT 'earnings.credited','task_id','id',0 UNION ALL
    SELECT 'earnings.credited','task_no','id',0 UNION ALL
    SELECT 'earnings.credited','device_id','id',0 UNION ALL
    SELECT 'earnings.credited','receipt_no','id',0 UNION ALL
    SELECT 'earnings.credited','amount_usdt','number',0 UNION ALL
    SELECT 'genesis.emission_paid','holding_no','id',0 UNION ALL
    SELECT 'genesis.emission_paid','amount_usdt','number',0 UNION ALL
    SELECT 'genesis.emission_paid','rate_applied','number',0 UNION ALL
    SELECT 'genesis.emission_paid','paid_at','timestamp',0 UNION ALL
    SELECT 'admin.staking_pool_restored','tier_key','enum',0 UNION ALL
    SELECT 'admin.staking_pool_restored','trigger_basis','enum',0 UNION ALL
    SELECT 'admin.staking_pool_restored','review_conclusion','string',1 UNION ALL
    SELECT 'admin.staking_pool_restored','reason','string',1 UNION ALL
    SELECT 'admin.staking_pool_restored','operator','string',1 UNION ALL
    SELECT 'admin.staking_pool_restored','restoration_domain','enum',0
  ) p ON p.event_name=s.event_name
 WHERE s.current_revision=316 AND s.status='ACTIVE' AND s.is_deleted=0
ON DUPLICATE KEY UPDATE
  property_type=IF(nx_event_schema_property.is_deleted=0 AND nx_event_schema_property.registry_revision<=316,
                   VALUES(property_type),nx_event_schema_property.property_type),
  pii=IF(nx_event_schema_property.is_deleted=0 AND nx_event_schema_property.registry_revision<=316,
         VALUES(pii),nx_event_schema_property.pii),
  required_field=IF(nx_event_schema_property.is_deleted=0 AND nx_event_schema_property.registry_revision<=316,
                    1,nx_event_schema_property.required_field),
  registry_revision=IF(nx_event_schema_property.is_deleted=0,
                       GREATEST(nx_event_schema_property.registry_revision,316),nx_event_schema_property.registry_revision);

INSERT INTO nx_admin_event_lifecycle
  (event_name,lifecycle_state,version,changed_by,reason,is_deleted)
SELECT s.event_name,'full',0,'migration:business-event-closure','Register canonical business publisher',0
  FROM nx_event_schema_registry s
 WHERE s.event_name IN ('auth.password_reset_completed','capacity_replacement.completed','task.completed',
                        'earnings.credited','genesis.emission_paid','admin.staking_pool_restored')
   AND s.current_revision=316 AND s.status='ACTIVE' AND s.is_deleted=0
ON DUPLICATE KEY UPDATE event_name=VALUES(event_name);

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,316)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,316);
COMMIT;

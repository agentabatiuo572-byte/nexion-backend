-- GET /api/trial/state advances expired trials transactionally. Its canonical
-- grace event must pass the same A4 schema/property/lifecycle gates as commands.
-- No trial, wallet, order or existing outbox rows are modified by this migration.
SET NAMES utf8mb4;
START TRANSACTION;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,updated_by,reason,is_deleted)
VALUES
  ('trial.grace_entered','trial','conversion','AppTrialLifecycleService','A4/H2',1,
   '100%',314,'ACTIVE','migration:trial-grace-entered-schema',
   'migration:trial-grace-entered-schema','Expired trial enters its grace period',0)
ON DUPLICATE KEY UPDATE event_name=VALUES(event_name);

-- Upgrade an older active definition only. Never downgrade future revisions or
-- resurrect a schema retired/deleted by an operator.
UPDATE nx_event_schema_registry
   SET owner_domain='trial',family_key='conversion',producer='AppTrialLifecycleService',
       consumers='A4/H2',is_server_authoritative=1,sampling_policy='100%',
       current_revision=314,updated_by='migration:trial-grace-entered-schema',
       reason='Expired trial enters its grace period'
 WHERE event_name='trial.grace_entered' AND current_revision<314
   AND status='ACTIVE' AND is_deleted=0;

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,'number',0,1,314,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'grace_days' property_name UNION ALL
    SELECT 'shadow_usdt' UNION ALL
    SELECT 'shadow_nex'
  ) p ON 1=1
 WHERE s.event_name='trial.grace_entered' AND s.current_revision=314
   AND s.status='ACTIVE' AND s.is_deleted=0
ON DUPLICATE KEY UPDATE
  property_type=IF(nx_event_schema_property.is_deleted=0 AND registry_revision<=314,VALUES(property_type),property_type),
  pii=IF(nx_event_schema_property.is_deleted=0 AND registry_revision<=314,0,pii),
  required_field=IF(nx_event_schema_property.is_deleted=0 AND registry_revision<=314,1,required_field),
  registry_revision=IF(nx_event_schema_property.is_deleted=0,GREATEST(registry_revision,314),registry_revision);

-- New canonical events start fully published. A pre-existing disabled/gray/new
-- lifecycle is an operator decision and must survive every startup rerun.
INSERT INTO nx_admin_event_lifecycle
  (event_name,lifecycle_state,version,changed_by,reason,is_deleted)
SELECT s.event_name,'full',0,'migration:trial-grace-entered-schema',
       'Register canonical trial grace transition',0
  FROM nx_event_schema_registry s
 WHERE s.event_name='trial.grace_entered' AND s.current_revision=314
   AND s.status='ACTIVE' AND s.is_deleted=0
ON DUPLICATE KEY UPDATE event_name=VALUES(event_name);

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,314)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,314);

COMMIT;

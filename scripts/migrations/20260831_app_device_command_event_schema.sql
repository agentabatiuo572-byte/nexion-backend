-- App activation/deactivation publishes these canonical facts in the same
-- transaction as the device state. Keep A4 validation enabled and register
-- the exact payload instead of substituting the different admin.* contracts.
SET NAMES utf8mb4;
START TRANSACTION;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,updated_by,reason,is_deleted)
VALUES
  ('device.activated','device','device-ops','AppCanonicalBoundaryService','A4',1,
   '100%',315,'ACTIVE','migration:app-device-command-schema',
   'migration:app-device-command-schema','Canonical App device activation',0),
  ('device.deactivated','device','device-ops','AppCanonicalBoundaryService','A4',1,
   '100%',315,'ACTIVE','migration:app-device-command-schema',
   'migration:app-device-command-schema','Canonical App device deactivation',0)
ON DUPLICATE KEY UPDATE event_name=VALUES(event_name);

-- Upgrade only an older active definition. Operator retirement/deletion and
-- newer revisions must survive a repeated deployment.
UPDATE nx_event_schema_registry
   SET owner_domain='device',family_key='device-ops',producer='AppCanonicalBoundaryService',
       consumers='A4',is_server_authoritative=1,sampling_policy='100%',
       current_revision=315,updated_by='migration:app-device-command-schema',
       reason='Canonical App device command event contract'
 WHERE event_name IN ('device.activated','device.deactivated')
   AND current_revision<315 AND status='ACTIVE' AND is_deleted=0;

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,315,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'device_id' property_name,'id' property_type UNION ALL
    SELECT 'instance_no','id' UNION ALL
    SELECT 'previous_status','enum' UNION ALL
    SELECT 'status','enum' UNION ALL
    SELECT 'row_version','number'
  ) p ON 1=1
 WHERE s.event_name IN ('device.activated','device.deactivated')
   AND s.current_revision=315 AND s.status='ACTIVE' AND s.is_deleted=0
ON DUPLICATE KEY UPDATE
  property_type=IF(nx_event_schema_property.is_deleted=0 AND registry_revision<=315,VALUES(property_type),nx_event_schema_property.property_type),
  pii=IF(nx_event_schema_property.is_deleted=0 AND registry_revision<=315,0,pii),
  required_field=IF(nx_event_schema_property.is_deleted=0 AND registry_revision<=315,1,required_field),
  registry_revision=IF(nx_event_schema_property.is_deleted=0,GREATEST(registry_revision,315),registry_revision);

-- A pre-existing disabled/gray/new lifecycle is not a migration error and is
-- never silently enabled by this repair.
INSERT INTO nx_admin_event_lifecycle
  (event_name,lifecycle_state,version,changed_by,reason,is_deleted)
SELECT s.event_name,'full',0,'migration:app-device-command-schema',
       'Register canonical App device command',0
  FROM nx_event_schema_registry s
 WHERE s.event_name IN ('device.activated','device.deactivated')
   AND s.current_revision=315 AND s.status='ACTIVE' AND s.is_deleted=0
ON DUPLICATE KEY UPDATE event_name=VALUES(event_name);

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,315)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,315);

COMMIT;

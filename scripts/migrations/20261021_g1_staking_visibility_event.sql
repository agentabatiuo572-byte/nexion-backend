-- Register the G1 App visibility command's canonical outbox fact.
-- Replays preserve operator retirement, deletion, and newer contracts.
SET NAMES utf8mb4;
START TRANSACTION;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('admin.staking_visibility_changed','admin','phase_admin','G1AdminCommandService',
   'G1/A2/A4/App',1,'100%',20261021,'ACTIVE','migration:g1-staking-visibility',
   'G1 App staking visibility changed with reason',0)
ON DUPLICATE KEY UPDATE event_name=VALUES(event_name);

UPDATE nx_event_schema_registry
   SET current_revision=20261021,updated_by='migration:g1-staking-visibility',
       reason='G1 App staking visibility changed with reason'
 WHERE event_name='admin.staking_visibility_changed' AND current_revision<20261021
   AND status='ACTIVE' AND is_deleted=0;

UPDATE nx_event_schema_property p
  JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.registry_revision=20261021
 WHERE s.event_name='admin.staking_visibility_changed' AND s.current_revision=20261021
   AND s.status='ACTIVE' AND s.is_deleted=0
   AND p.is_deleted=0 AND p.registry_revision<20261021;

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,20261021,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'visible' property_name,'boolean' property_type UNION ALL
    SELECT 'reason','string' UNION ALL
    SELECT 'operator','string'
  ) p
 WHERE s.event_name='admin.staking_visibility_changed' AND s.current_revision=20261021
   AND s.status='ACTIVE' AND s.is_deleted=0
ON DUPLICATE KEY UPDATE
  property_type=IF(nx_event_schema_property.is_deleted=0
                   AND nx_event_schema_property.registry_revision<=20261021,
                   VALUES(property_type),nx_event_schema_property.property_type),
  pii=IF(nx_event_schema_property.is_deleted=0
         AND nx_event_schema_property.registry_revision<=20261021,
         0,nx_event_schema_property.pii),
  required_field=IF(nx_event_schema_property.is_deleted=0
                    AND nx_event_schema_property.registry_revision<=20261021,
                    1,nx_event_schema_property.required_field),
  registry_revision=IF(nx_event_schema_property.is_deleted=0,
                       GREATEST(nx_event_schema_property.registry_revision,20261021),
                       nx_event_schema_property.registry_revision);

INSERT INTO nx_admin_event_lifecycle
  (event_name,lifecycle_state,version,changed_by,reason,is_deleted)
SELECT s.event_name,'full',0,'migration:g1-staking-visibility',
       'Register G1 App staking visibility fact',0
  FROM nx_event_schema_registry s
 WHERE s.event_name='admin.staking_visibility_changed' AND s.current_revision=20261021
   AND s.status='ACTIVE' AND s.is_deleted=0
ON DUPLICATE KEY UPDATE event_name=VALUES(event_name);

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,20261021)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,20261021);

COMMIT;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- C-001: account-list mutations are durable C2/A4 facts and must not fail at the outbox gate.
SET NAMES utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('admin.account_list_upserted','admin','phase_admin','OpsUserService',
   'A2/A4/B5/C2/c2-high-risk-admin-alert',1,'100%',301,'ACTIVE',
   'migration:c2-account-list-event-schema','C2 account-list add or update governance fact',0),
  ('admin.account_list_removed','admin','phase_admin','OpsUserService',
   'A2/A4/B5/C2/c2-high-risk-admin-alert',1,'100%',301,'ACTIVE',
   'migration:c2-account-list-event-schema','C2 account-list removal governance fact',0)
ON DUPLICATE KEY UPDATE
  owner_domain='admin',family_key='phase_admin',producer='OpsUserService',
  consumers=VALUES(consumers),is_server_authoritative=1,sampling_policy='100%',
  current_revision=301,status='ACTIVE',updated_by='migration:c2-account-list-event-schema',
  reason=VALUES(reason),is_deleted=0,updated_at=NOW();

-- The migration is safe for upgraded databases too: retire any stale property contract
-- before restoring this exact, server-produced payload shape.
UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name IN ('admin.account_list_upserted','admin.account_list_removed')
   AND p.property_name NOT IN ('kind','reason','idempotency_key','expires_at','sessions_revoked');

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,
   created_at,updated_at,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,301,NOW(),NOW(),0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'admin.account_list_upserted' event_name,'kind' property_name,'enum' property_type,1 required_field UNION ALL
    SELECT 'admin.account_list_upserted','reason','string',1 UNION ALL
    SELECT 'admin.account_list_upserted','idempotency_key','id',1 UNION ALL
    SELECT 'admin.account_list_upserted','expires_at','timestamp',0 UNION ALL
    SELECT 'admin.account_list_upserted','sessions_revoked','boolean',1 UNION ALL
    SELECT 'admin.account_list_removed','kind','enum',1 UNION ALL
    SELECT 'admin.account_list_removed','reason','string',1 UNION ALL
    SELECT 'admin.account_list_removed','idempotency_key','id',1
  ) p ON p.event_name=s.event_name
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=VALUES(required_field),
  registry_revision=301,is_deleted=0,updated_at=NOW();

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,301)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,301);

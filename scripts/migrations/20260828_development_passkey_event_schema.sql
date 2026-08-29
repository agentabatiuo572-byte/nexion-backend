-- The canonical local-development Passkey login is not a Sandbox event.
-- Register its server-authoritative audit schema before the dev endpoint can
-- issue a session; production providers remain independently configured.
INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('auth.development_passkey_login','auth','auth','server','security/audit',1,
   '100%',309,'ACTIVE','migration:development-passkey',
   'Canonical local development Passkey login issued',0)
ON DUPLICATE KEY UPDATE owner_domain=VALUES(owner_domain),family_key=VALUES(family_key),
  producer='server',consumers=VALUES(consumers),is_server_authoritative=1,
  sampling_policy='100%',current_revision=VALUES(current_revision),status='ACTIVE',is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1
 WHERE s.event_name='auth.development_passkey_login';

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,s.current_revision,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'user_id' property_name,'id' property_type UNION ALL
    SELECT 'provider','enum' UNION ALL
    SELECT 'source','enum' UNION ALL
    SELECT 'sandbox','boolean' UNION ALL
    SELECT 'subject_hash','string'
  ) p
 WHERE s.event_name='auth.development_passkey_login'
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,309)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,309);

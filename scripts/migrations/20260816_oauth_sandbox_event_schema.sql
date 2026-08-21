-- OAuth sandbox sessions are real server-side state changes even though the
-- external identity proof is mocked. Register both lifecycle events before a
-- local-sandbox request can publish them; production still rejects mock mode.
INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('auth.oauth_sandbox_account_created','auth','auth','server','security/audit',1,
   '100%',307,'ACTIVE','migration:oauth-sandbox','Sandbox OAuth account created by the canonical auth service',0),
  ('auth.oauth_sandbox_login','auth','auth','server','security/audit',1,
   '100%',308,'ACTIVE','migration:oauth-sandbox','Sandbox OAuth login issued by the canonical auth service',0)
ON DUPLICATE KEY UPDATE owner_domain=VALUES(owner_domain),family_key=VALUES(family_key),
  producer='server',consumers=VALUES(consumers),is_server_authoritative=1,
  sampling_policy='100%',current_revision=VALUES(current_revision),status='ACTIVE',is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1
 WHERE s.event_name IN ('auth.oauth_sandbox_account_created','auth.oauth_sandbox_login');

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
 WHERE s.event_name IN ('auth.oauth_sandbox_account_created','auth.oauth_sandbox_login')
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,308)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,308);

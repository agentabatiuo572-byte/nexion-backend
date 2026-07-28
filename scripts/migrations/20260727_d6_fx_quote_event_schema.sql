-- D6 -> A4 closure: register the exact authoritative FX quote change payload
-- before the transactional outbox is allowed to publish it.
SET NAMES utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('admin.fx_quote_updated','admin','money','OpsVietnamPaymentService','A4/D1/D3/D4/B1/B2/App',
   1,'100%',109,'ACTIVE','migration:d6-fx-quote-event',
   'D6 authoritative VND/USDT quote configuration changed',0)
ON DUPLICATE KEY UPDATE
  owner_domain='admin',family_key='money',producer='OpsVietnamPaymentService',
  consumers='A4/D1/D3/D4/B1/B2/App',is_server_authoritative=1,sampling_policy='100%',
  current_revision=109,status='ACTIVE',updated_by='migration:d6-fx-quote-event',
  reason=VALUES(reason),is_deleted=0,updated_at=NOW();

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name='admin.fx_quote_updated'
   AND p.property_name NOT IN (
     'config_code','before_version','version','base_rate_vnd_per_usdt',
     'buy_spread_pct','quote_rate_vnd_per_usdt','lock_window_minutes','operator'
   );

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,
   created_at,updated_at,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,109,NOW(),NOW(),0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'config_code' property_name,'id' property_type UNION ALL
    SELECT 'before_version','number' UNION ALL
    SELECT 'version','number' UNION ALL
    SELECT 'base_rate_vnd_per_usdt','number' UNION ALL
    SELECT 'buy_spread_pct','number' UNION ALL
    SELECT 'quote_rate_vnd_per_usdt','number' UNION ALL
    SELECT 'lock_window_minutes','number' UNION ALL
    SELECT 'operator','string'
  ) p
 WHERE s.event_name='admin.fx_quote_updated'
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=109,is_deleted=0,updated_at=NOW();

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,109)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,109);

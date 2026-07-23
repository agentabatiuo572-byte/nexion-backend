-- E1/A4 closure: register the governed SKU catalogue lifecycle events in the
-- existing A4 registry before E1 publishes their canonical names.

SET NAMES utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name, owner_domain, family_key, producer, consumers, is_server_authoritative,
   sampling_policy, current_revision, status, created_by, reason, is_deleted)
VALUES
  ('admin.product_listed', 'admin', 'phase_admin', 'server', 'E1/A2/L3/B4', 1, '100%', 40,
   'ACTIVE', 'migration:e1', 'E1 authoritative SKU listing event', 0),
  ('admin.product_unlisted', 'admin', 'phase_admin', 'server', 'E1/A2/L3/B4', 1, '100%', 41,
   'ACTIVE', 'migration:e1', 'E1 authoritative SKU unlisting event', 0),
  ('admin.product_price_changed', 'admin', 'phase_admin', 'server', 'E1/A2/L3/B4', 1, '100%', 42,
   'ACTIVE', 'migration:e1', 'E1 authoritative SKU price change event', 0)
ON DUPLICATE KEY UPDATE
  owner_domain=VALUES(owner_domain), family_key=VALUES(family_key), producer=VALUES(producer),
  consumers=VALUES(consumers), is_server_authoritative=VALUES(is_server_authoritative),
  sampling_policy=VALUES(sampling_policy), current_revision=VALUES(current_revision),
  status='ACTIVE', updated_by='migration:e1', reason=VALUES(reason), is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1, p.updated_at=NOW()
 WHERE s.event_name IN (
       'admin.product_listed', 'admin.product_unlisted', 'admin.product_price_changed')
   AND ((s.event_name IN ('admin.product_listed', 'admin.product_unlisted')
         AND p.property_name NOT IN ('sku_key', 'before_status', 'after_status', 'operator', 'reason'))
        OR (s.event_name='admin.product_price_changed'
            AND p.property_name NOT IN (
              'sku_key', 'scope', 'field', 'before', 'after', 'effective_at', 'operator', 'reason')));

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, is_deleted)
SELECT s.id, p.property_name, p.property_type, 0, 1, s.current_revision, 0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'admin.product_listed' event_name, 'sku_key' property_name, 'id' property_type UNION ALL
    SELECT 'admin.product_listed', 'before_status', 'enum' UNION ALL
    SELECT 'admin.product_listed', 'after_status', 'enum' UNION ALL
    SELECT 'admin.product_listed', 'operator', 'id' UNION ALL
    SELECT 'admin.product_listed', 'reason', 'string' UNION ALL
    SELECT 'admin.product_unlisted', 'sku_key', 'id' UNION ALL
    SELECT 'admin.product_unlisted', 'before_status', 'enum' UNION ALL
    SELECT 'admin.product_unlisted', 'after_status', 'enum' UNION ALL
    SELECT 'admin.product_unlisted', 'operator', 'id' UNION ALL
    SELECT 'admin.product_unlisted', 'reason', 'string' UNION ALL
    SELECT 'admin.product_price_changed', 'sku_key', 'id' UNION ALL
    SELECT 'admin.product_price_changed', 'scope', 'enum' UNION ALL
    SELECT 'admin.product_price_changed', 'field', 'enum' UNION ALL
    SELECT 'admin.product_price_changed', 'before', 'json' UNION ALL
    SELECT 'admin.product_price_changed', 'after', 'json' UNION ALL
    SELECT 'admin.product_price_changed', 'effective_at', 'timestamp' UNION ALL
    SELECT 'admin.product_price_changed', 'operator', 'id' UNION ALL
    SELECT 'admin.product_price_changed', 'reason', 'string'
  ) p ON p.event_name=s.event_name
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type), pii=0, required_field=1,
  registry_revision=VALUES(registry_revision), is_deleted=0;

INSERT INTO nx_event_schema_revision (id, current_revision) VALUES (1,42)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,42);

-- Repair events written by the pre-registry E1 implementation. The fallback
-- actor/reason make old facts auditable without weakening the new write gate.
UPDATE nx_event_outbox o
JOIN nx_event_schema_registry s
  ON s.event_name=CASE o.event_type
       WHEN 'PRODUCT_LISTED' THEN 'admin.product_listed'
       WHEN 'PRODUCT_UNLISTED' THEN 'admin.product_unlisted'
       WHEN 'PRODUCT_PRICE_CHANGED' THEN 'admin.product_price_changed'
       ELSE NULL
     END
   SET o.event_name=s.event_name,
       o.family_key=s.family_key,
       o.event_ts=COALESCE(o.event_ts,o.created_at),
       o.phase=COALESCE(NULLIF(o.phase,''),'SYSTEM'),
       o.account_age_months=COALESCE(o.account_age_months,0),
       o.cohort=COALESCE(NULLIF(o.cohort,''),DATE_FORMAT(o.created_at,'%x-W%v')),
       o.is_server_authoritative=1,
       o.schema_revision=s.current_revision,
       o.schema_registered=1,
       o.analytics_event=1,
       o.payload=JSON_SET(
         o.payload,
         '$.event_id',o.event_id,
         '$.event_name',s.event_name,
         '$.ts',CAST(UNIX_TIMESTAMP(COALESCE(o.event_ts,o.created_at))*1000 AS UNSIGNED),
         '$.phase',COALESCE(NULLIF(o.phase,''),'SYSTEM'),
         '$.account_age_months',COALESCE(o.account_age_months,0),
         '$.cohort',COALESCE(NULLIF(o.cohort,''),DATE_FORMAT(o.created_at,'%x-W%v')),
         '$.platform','server',
         '$.app_version','backend',
         '$.locale','und',
         '$.is_server_authoritative',TRUE,
         '$.schema_revision',s.current_revision)
 WHERE o.event_type IN ('PRODUCT_LISTED','PRODUCT_UNLISTED','PRODUCT_PRICE_CHANGED')
   AND o.is_deleted=0;

UPDATE nx_event_outbox o
   SET o.payload=JSON_SET(
         o.payload,
         '$.sku_key',COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.sku_key')),'null'),
                              NULLIF(JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.skuId')),'null'),o.aggregate_id),
         '$.before_status',COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.before_status')),'null'),
                                    CASE o.event_type WHEN 'PRODUCT_LISTED' THEN 'pending' ELSE 'on' END),
         '$.after_status',COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.after_status')),'null'),
                                   NULLIF(JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.status')),'null'),
                                   CASE o.event_type WHEN 'PRODUCT_LISTED' THEN 'on' ELSE 'off' END),
         '$.operator',COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.operator')),'null'),'migration:e1'),
         '$.reason',COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.reason')),'null'),
                             'Legacy E1 product event backfill'))
 WHERE o.event_type IN ('PRODUCT_LISTED','PRODUCT_UNLISTED')
   AND o.is_deleted=0;

UPDATE nx_event_outbox o
   SET o.payload=JSON_SET(
         o.payload,
         '$.sku_key',COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.sku_key')),'null'),
                              NULLIF(JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.skuId')),'null'),o.aggregate_id),
         '$.scope','price',
         '$.field','price',
         '$.before',COALESCE(JSON_EXTRACT(o.payload,'$.before'),JSON_EXTRACT(o.payload,'$.beforePrice')),
         '$.after',COALESCE(JSON_EXTRACT(o.payload,'$.after'),JSON_EXTRACT(o.payload,'$.afterPrice')),
         '$.effective_at',DATE_FORMAT(COALESCE(o.event_ts,o.created_at),'%Y-%m-%dT%H:%i:%s.%f'),
         '$.operator',COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.operator')),'null'),'migration:e1'),
         '$.reason',COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.reason')),'null'),
                             'Legacy E1 product event backfill'))
 WHERE o.event_type='PRODUCT_PRICE_CHANGED'
   AND o.is_deleted=0;

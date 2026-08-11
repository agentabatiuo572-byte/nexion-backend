-- L6 analytics publishes source_environment only after deriving it inside the server.
-- Advance both behavior schemas together so the outbox gate sees one complete revision.
SET NAMES utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,updated_by,reason,is_deleted)
VALUES
  ('app.page_viewed','app','behavior','client','A4/L6',0,'100%',306,'ACTIVE',
   'migration:l6-source-environment','migration:l6-source-environment',
   'L6 page view; source_environment is server-derived',0),
  ('app.element_clicked','app','behavior','client','A4/L6',0,'client-throttle-350ms',306,'ACTIVE',
   'migration:l6-source-environment','migration:l6-source-environment',
   'L6 click; source_environment is server-derived',0)
ON DUPLICATE KEY UPDATE
  owner_domain=IF(current_revision<=306,VALUES(owner_domain),owner_domain),
  family_key=IF(current_revision<=306,VALUES(family_key),family_key),
  producer=IF(current_revision<=306,VALUES(producer),producer),
  consumers=IF(current_revision<=306,VALUES(consumers),consumers),
  is_server_authoritative=IF(current_revision<=306,VALUES(is_server_authoritative),is_server_authoritative),
  sampling_policy=IF(current_revision<=306,VALUES(sampling_policy),sampling_policy),
  status=IF(current_revision<=306,VALUES(status),status),
  updated_by=IF(current_revision<=306,VALUES(updated_by),updated_by),
  reason=IF(current_revision<=306,VALUES(reason),reason),
  is_deleted=IF(current_revision<=306,VALUES(is_deleted),is_deleted),
  updated_at=IF(current_revision<=306,NOW(),updated_at),
  current_revision=GREATEST(current_revision,306);

-- Do not rewrite a newer registry contract. For revision 306, retire stale fields
-- and reactivate the exact payload shape emitted by BehaviorAnalyticsService.
UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name IN ('app.page_viewed','app.element_clicked')
   AND s.current_revision=306
   AND (
     (s.event_name='app.page_viewed' AND p.property_name NOT IN (
       'route','page_level','parent_l1','parent_l2','dwell_ms','source_environment'
     ))
     OR
     (s.event_name='app.element_clicked' AND p.property_name NOT IN (
       'route','page_level','parent_l1','parent_l2','x_norm','y_norm','zone',
       'element_id','source_environment'
     ))
   );

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,
   created_at,updated_at,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,306,NOW(),NOW(),0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'app.page_viewed' event_name,'route' property_name,'string' property_type,1 required_field UNION ALL
    SELECT 'app.page_viewed','page_level','number',1 UNION ALL
    SELECT 'app.page_viewed','parent_l1','string',1 UNION ALL
    SELECT 'app.page_viewed','parent_l2','string',1 UNION ALL
    SELECT 'app.page_viewed','dwell_ms','number',1 UNION ALL
    SELECT 'app.page_viewed','source_environment','enum',1 UNION ALL
    SELECT 'app.element_clicked','route','string',1 UNION ALL
    SELECT 'app.element_clicked','page_level','number',1 UNION ALL
    SELECT 'app.element_clicked','parent_l1','string',1 UNION ALL
    SELECT 'app.element_clicked','parent_l2','string',1 UNION ALL
    SELECT 'app.element_clicked','x_norm','number',1 UNION ALL
    SELECT 'app.element_clicked','y_norm','number',1 UNION ALL
    SELECT 'app.element_clicked','zone','enum',1 UNION ALL
    SELECT 'app.element_clicked','element_id','string',0 UNION ALL
    SELECT 'app.element_clicked','source_environment','enum',1
  ) p ON p.event_name=s.event_name
 WHERE s.current_revision=306
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=VALUES(required_field),
  registry_revision=306,is_deleted=0,updated_at=NOW();

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,306)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,306);

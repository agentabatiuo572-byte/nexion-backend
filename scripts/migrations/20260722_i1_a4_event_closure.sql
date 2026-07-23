-- I1/A4 closure: register server-side stable assignment exposure and paid-order
-- conversion facts before AppCopyExperimentService publishes them transactionally.
SET NAMES utf8mb4;

INSERT INTO nx_event_schema_registry
  (event_name, owner_domain, family_key, producer, consumers, is_server_authoritative,
   sampling_policy, current_revision, status, created_by, reason, is_deleted)
VALUES
  ('content.variant_exposed', 'content', 'conversion', 'AppCopyExperimentService', 'A4/I1/B3/L2', 1,
   '100%', 104, 'ACTIVE', 'migration:i1-a4', 'I1 first server-confirmed copy variant exposure', 0),
  ('content.variant_converted', 'content', 'conversion', 'AppCopyExperimentService', 'A4/I1/B3/L2', 1,
   '100%', 105, 'ACTIVE', 'migration:i1-a4', 'I1 paid or completed order conversion', 0)
ON DUPLICATE KEY UPDATE
  owner_domain=VALUES(owner_domain), family_key=VALUES(family_key), producer=VALUES(producer),
  consumers=VALUES(consumers), is_server_authoritative=VALUES(is_server_authoritative),
  sampling_policy='100%', current_revision=VALUES(current_revision), status='ACTIVE',
  updated_by='migration:i1-a4', reason=VALUES(reason), is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1, p.updated_at=NOW()
 WHERE s.event_name IN ('content.variant_exposed', 'content.variant_converted')
   AND ((s.event_name='content.variant_exposed'
         AND p.property_name NOT IN ('experiment_id','variant','copy_key','copy_version','bucket_no'))
        OR (s.event_name='content.variant_converted'
            AND p.property_name NOT IN ('experiment_id','variant','order_id','paid_or_completed_at')));

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, is_deleted)
SELECT s.id, p.property_name, p.property_type, 0, 1, s.current_revision, 0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'content.variant_exposed' event_name, 'experiment_id' property_name, 'id' property_type UNION ALL
    SELECT 'content.variant_exposed', 'variant', 'enum' UNION ALL
    SELECT 'content.variant_exposed', 'copy_key', 'id' UNION ALL
    SELECT 'content.variant_exposed', 'copy_version', 'id' UNION ALL
    SELECT 'content.variant_exposed', 'bucket_no', 'number' UNION ALL
    SELECT 'content.variant_converted', 'experiment_id', 'id' UNION ALL
    SELECT 'content.variant_converted', 'variant', 'enum' UNION ALL
    SELECT 'content.variant_converted', 'order_id', 'id' UNION ALL
    SELECT 'content.variant_converted', 'paid_or_completed_at', 'timestamp'
  ) p ON p.event_name=s.event_name
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type), pii=0, required_field=1,
  registry_revision=VALUES(registry_revision), is_deleted=0;

INSERT INTO nx_event_domain_extension
  (domain_name, event_name, producer, consumer, status, created_by, reason, is_deleted)
VALUES
  ('content', 'content.variant_exposed', 'AppCopyExperimentService', 'A4/I1/B3/L2',
   'REGISTERED', 'migration:i1-a4', 'I1 server-side stable assignment first exposure', 0),
  ('content', 'content.variant_converted', 'AppCopyExperimentService', 'A4/I1/B3/L2',
   'REGISTERED', 'migration:i1-a4', 'I1 server-confirmed paid or completed order conversion', 0)
ON DUPLICATE KEY UPDATE
  producer=VALUES(producer), consumer=VALUES(consumer), status='REGISTERED',
  reason=VALUES(reason), is_deleted=0;

INSERT INTO nx_event_schema_revision (id, current_revision) VALUES (1,105)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,105);

-- I1/I6 closure: the copy pool owns the published three-language value, while
-- I6 supplies the shared runtime catalog. Backfill the current I1 publication
-- and preserve I6 version history. Re-running the migration creates no duplicate
-- version when the same three-language value is already published.
INSERT INTO nx_i18n_message
  (message_key, locale, message_value, status, created_at, updated_at, is_deleted)
SELECT c.i18n_key,
       locale.locale,
       CASE locale.locale
         WHEN 'zh-CN' THEN v.zh_text
         WHEN 'en-US' THEN v.en_text
         ELSE v.vi_text
       END,
       1, NOW(), NOW(), 0
  FROM nx_content_copy c
  JOIN nx_content_copy_version v
    ON v.copy_key=c.copy_key
   AND v.version=c.current_version
   AND UPPER(v.status)='PUBLISHED'
   AND v.is_deleted=0
  JOIN (
    SELECT 'zh-CN' locale UNION ALL
    SELECT 'en-US' UNION ALL
    SELECT 'vi-VN'
  ) locale
 WHERE UPPER(c.status)='PUBLISHED'
   AND c.is_deleted=0
   AND c.i18n_key IS NOT NULL
   AND TRIM(c.i18n_key)<>''
   AND v.zh_text IS NOT NULL AND TRIM(v.zh_text)<>''
   AND v.en_text IS NOT NULL AND TRIM(v.en_text)<>''
   AND v.vi_text IS NOT NULL AND TRIM(v.vi_text)<>''
ON DUPLICATE KEY UPDATE
  message_value=VALUES(message_value), status=1, updated_at=NOW(), is_deleted=0;

-- Archive a formerly published I6 value only when it differs from I1's current
-- immutable version. This keeps the catalog single-published and auditable.
UPDATE nx_i18n_message_version i6
JOIN nx_content_copy c
  ON c.i18n_key=i6.message_key
 AND UPPER(c.status)='PUBLISHED'
 AND c.is_deleted=0
JOIN nx_content_copy_version v
  ON v.copy_key=c.copy_key
 AND v.version=c.current_version
 AND UPPER(v.status)='PUBLISHED'
 AND v.is_deleted=0
   SET i6.status='ARCHIVED', i6.updated_at=NOW()
 WHERE i6.status='PUBLISHED'
   AND i6.is_deleted=0
   AND NOT (i6.zh_value <=> v.zh_text
            AND i6.en_value <=> v.en_text
            AND i6.vi_value <=> v.vi_text);

INSERT INTO nx_i18n_message_version
  (message_key, version_no, zh_value, en_value, vi_value, status,
   created_at, updated_at, is_deleted)
SELECT c.i18n_key,
       COALESCE(MAX(history.version_no),0)+1,
       v.zh_text, v.en_text, v.vi_text, 'PUBLISHED', NOW(), NOW(), 0
  FROM nx_content_copy c
  JOIN nx_content_copy_version v
    ON v.copy_key=c.copy_key
   AND v.version=c.current_version
   AND UPPER(v.status)='PUBLISHED'
   AND v.is_deleted=0
  LEFT JOIN nx_i18n_message_version history
    ON history.message_key=c.i18n_key
   AND history.is_deleted=0
 WHERE UPPER(c.status)='PUBLISHED'
   AND c.is_deleted=0
   AND c.i18n_key IS NOT NULL
   AND TRIM(c.i18n_key)<>''
   AND v.zh_text IS NOT NULL AND TRIM(v.zh_text)<>''
   AND v.en_text IS NOT NULL AND TRIM(v.en_text)<>''
   AND v.vi_text IS NOT NULL AND TRIM(v.vi_text)<>''
   AND NOT EXISTS (
     SELECT 1
       FROM nx_i18n_message_version published
      WHERE published.message_key=c.i18n_key
        AND published.status='PUBLISHED'
        AND published.is_deleted=0
        AND published.zh_value <=> v.zh_text
        AND published.en_value <=> v.en_text
        AND published.vi_value <=> v.vi_text
   )
 GROUP BY c.i18n_key, v.zh_text, v.en_text, v.vi_text;

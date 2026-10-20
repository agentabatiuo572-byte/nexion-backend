-- Brand-only publication for the three reviewed GLOBAL v6 baseline translations.
-- Retain the v6 text and acknowledgements; v7 requires normal user confirmation.
SET NAMES utf8mb4;
START TRANSACTION;
SET @published_at = UTC_TIMESTAMP();

SELECT id FROM nx_legal_terms_version
WHERE locale IN ('en', 'zh', 'vi') AND jurisdiction='GLOBAL'
  AND version_label='v6' AND status='PUBLISHED' AND is_deleted=0
  AND last_operator='migration:safe-baseline-terms-v6'
FOR UPDATE;

INSERT INTO nx_legal_terms_version
  (locale, jurisdiction, version_label, effective_at, status, title, summary,
   sections_json, revision, last_operator, published_at, created_at, updated_at, is_deleted)
SELECT previous.locale, jurisdiction, 'v7', @published_at, 'DRAFT',
       REPLACE(title, 'NexGrid', 'UVEL'), REPLACE(summary, 'NexGrid', 'UVEL'),
       REPLACE(CAST(sections_json AS CHAR CHARACTER SET utf8mb4), 'NexGrid', 'UVEL'),
       1, 'migration:uvel-brand-terms-v7', NULL, @published_at, @published_at, 0
FROM (SELECT 'en' AS locale UNION ALL SELECT 'zh' UNION ALL SELECT 'vi') required_locale
LEFT JOIN nx_legal_terms_version previous
  ON previous.locale=required_locale.locale AND previous.jurisdiction='GLOBAL'
  AND previous.version_label='v6' AND previous.status='PUBLISHED' AND previous.is_deleted=0
  AND previous.last_operator='migration:safe-baseline-terms-v6';

UPDATE nx_legal_terms_version previous
JOIN nx_legal_terms_version current
  ON current.locale=previous.locale AND current.jurisdiction=previous.jurisdiction
SET previous.status='SUPERSEDED', previous.updated_at=@published_at
WHERE previous.locale IN ('en', 'zh', 'vi') AND previous.jurisdiction='GLOBAL'
  AND previous.version_label='v6' AND previous.status='PUBLISHED' AND previous.is_deleted=0
  AND previous.last_operator='migration:safe-baseline-terms-v6'
  AND current.version_label='v7' AND current.status='DRAFT' AND current.is_deleted=0
  AND current.last_operator='migration:uvel-brand-terms-v7';

UPDATE nx_legal_terms_version
SET status='PUBLISHED', revision=2, published_at=@published_at, updated_at=@published_at
WHERE locale IN ('en', 'zh', 'vi') AND jurisdiction='GLOBAL'
  AND version_label='v7' AND status='DRAFT' AND is_deleted=0
  AND last_operator='migration:uvel-brand-terms-v7';

INSERT INTO nx_audit_log
  (service_name, action, resource_type, resource_id, actor_type, actor_username,
   result, risk_level, detail_json, created_at, is_deleted)
SELECT 'nexion-backend', 'LEGAL_TERMS_PUBLISHED', 'LEGAL_TERMS_VERSION',
       CONCAT(locale, ':', jurisdiction, ':', version_label),
       'SYSTEM', 'migration:uvel-brand-terms-v7', 'SUCCESS', 'HIGH',
       JSON_OBJECT('locale', locale, 'jurisdiction', jurisdiction, 'version', version_label,
                   'revision', revision, 'previousVersion', 'v6',
                   'reason', 'User requested NexGrid product-name correction to UVEL; other wording unchanged',
                   'migration', '20261021_legal_terms_uvel_brand_v7'),
       @published_at, 0
FROM nx_legal_terms_version
WHERE locale IN ('en', 'zh', 'vi') AND jurisdiction='GLOBAL'
  AND version_label='v7' AND status='PUBLISHED' AND is_deleted=0
  AND last_operator='migration:uvel-brand-terms-v7' AND published_at=@published_at;

SELECT current.locale, current.version_label, current.status,
       JSON_LENGTH(current.sections_json) AS section_count,
       current.title=REPLACE(previous.title, 'NexGrid', 'UVEL')
         AND current.summary=REPLACE(previous.summary, 'NexGrid', 'UVEL')
         AND current.sections_json=CAST(REPLACE(CAST(previous.sections_json AS CHAR CHARACTER SET utf8mb4),
                                               'NexGrid', 'UVEL') AS JSON) AS brand_only,
       REGEXP_LIKE(CONCAT(current.title, current.summary, current.sections_json), 'nexgrid', 'i') AS old_brand
FROM nx_legal_terms_version current
JOIN nx_legal_terms_version previous
  ON previous.locale=current.locale AND previous.jurisdiction=current.jurisdiction
WHERE current.locale IN ('en', 'zh', 'vi') AND current.jurisdiction='GLOBAL'
  AND current.version_label='v7' AND current.is_deleted=0
  AND previous.version_label='v6' AND previous.is_deleted=0;
COMMIT;

CREATE TABLE IF NOT EXISTS nx_trust_section_version (
  id BIGINT NOT NULL AUTO_INCREMENT,
  section_key VARCHAR(64) NOT NULL,
  version_label VARCHAR(32) NOT NULL,
  description VARCHAR(255) NOT NULL,
  struct_text VARCHAR(255) NOT NULL,
  fields_json MEDIUMTEXT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  revision BIGINT NOT NULL DEFAULT 0,
  last_operator VARCHAR(128) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_trust_section_version (section_key, version_label, is_deleted),
  KEY idx_trust_section_version_status (section_key, status, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO nx_trust_section_version
  (section_key, version_label, description, struct_text, fields_json, status, revision, last_operator, created_at, updated_at, is_deleted)
SELECT s.section_key, s.version_label, s.description, s.struct_text,
       COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('key', f.field_key, 'label', f.field_key, 'value', f.field_value))
                 FROM nx_trust_section_field f
                 WHERE f.section_key COLLATE utf8mb4_unicode_ci = s.section_key COLLATE utf8mb4_unicode_ci
                   AND f.is_deleted = 0), JSON_ARRAY()),
       'PUBLISHED', 1, COALESCE(s.last_operator, 'migration'), NOW(), NOW(), 0
FROM nx_trust_section s
WHERE s.is_deleted = 0
  AND NOT EXISTS (
    SELECT 1 FROM nx_trust_section_version v
    WHERE v.section_key = s.section_key COLLATE utf8mb4_unicode_ci
      AND v.version_label = s.version_label COLLATE utf8mb4_unicode_ci
      AND v.is_deleted = 0
  );

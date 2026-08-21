-- Server-authoritative Terms of Service CMS and per-account acknowledgement.
-- Structured section fields are serialized only at the storage boundary; callers never submit raw JSON.
CREATE TABLE IF NOT EXISTS nx_legal_terms_version (
  id BIGINT NOT NULL AUTO_INCREMENT,
  locale VARCHAR(16) NOT NULL,
  jurisdiction VARCHAR(32) NOT NULL,
  version_label VARCHAR(64) NOT NULL,
  effective_at DATETIME NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  title VARCHAR(200) NOT NULL,
  summary VARCHAR(2000) NOT NULL,
  sections_json JSON NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  last_operator VARCHAR(128) NULL,
  published_at DATETIME NULL,
  revoked_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_legal_terms_version (locale, jurisdiction, version_label, is_deleted),
  KEY idx_legal_terms_published (locale, jurisdiction, status, effective_at),
  CONSTRAINT chk_legal_terms_status CHECK (status IN ('DRAFT','PUBLISHED','SUPERSEDED','REVOKED'))
);

CREATE TABLE IF NOT EXISTS nx_legal_terms_ack (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  source_environment VARCHAR(16) NOT NULL,
  run_id VARCHAR(128) NOT NULL DEFAULT '',
  locale VARCHAR(16) NOT NULL,
  jurisdiction VARCHAR(32) NOT NULL,
  version_label VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  acknowledged_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_legal_terms_ack_scope (user_id, source_environment, run_id, locale, jurisdiction, is_deleted),
  UNIQUE KEY uk_legal_terms_ack_idem (user_id, source_environment, run_id, idempotency_key, is_deleted),
  KEY idx_legal_terms_ack_version (jurisdiction, locale, version_label),
  CONSTRAINT chk_legal_terms_ack_environment CHECK (source_environment IN ('PRODUCTION','SANDBOX'))
);

INSERT IGNORE INTO nx_admin_permission
  (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted)
VALUES
 ('content_legal_terms_read', 'Legal Terms read', 'API', '/content/legal-terms', 'READ', 0, 1, 0),
 ('content_legal_terms_write', 'Legal Terms draft write', 'API', '/content/legal-terms', 'WRITE', 0, 1, 0),
 ('content_legal_terms_publish', 'Legal Terms publish/revoke', 'API', '/content/legal-terms', 'HIGH', 0, 1, 0)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name), resource_path=VALUES(resource_path),
  perm_type=VALUES(perm_type), amplifies=VALUES(amplifies), status=1, is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
 WHERE r.role_code IN ('SUPER_ADMIN','CONTENT')
   AND p.permission_code IN ('content_legal_terms_read','content_legal_terms_write','content_legal_terms_publish')
   AND r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0;

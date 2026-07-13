CREATE TABLE IF NOT EXISTS nx_disclosure_jurisdiction_catalog (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  jurisdiction_code VARCHAR(32) NOT NULL,
  jurisdiction_name VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DISABLED',
  revision BIGINT NOT NULL DEFAULT 1,
  last_operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_disclosure_jurisdiction_catalog_code (jurisdiction_code),
  KEY idx_disclosure_jurisdiction_catalog_status (status, is_deleted),
  CONSTRAINT chk_disclosure_jurisdiction_catalog_status CHECK (status IN ('ACTIVE','DISABLED','ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO nx_disclosure_jurisdiction_catalog
  (jurisdiction_code, jurisdiction_name, status, revision, last_operator, created_at, updated_at, is_deleted)
SELECT UPPER(j.jurisdiction_code), j.jurisdiction_name,
       CASE WHEN UPPER(j.status) = 'ARCHIVED' THEN 'ARCHIVED' ELSE 'ACTIVE' END,
       1, COALESCE(j.last_operator, 'migration:i5-catalog'), j.created_at, j.updated_at, 0
FROM nx_disclosure_jurisdiction j
WHERE j.is_deleted = 0;

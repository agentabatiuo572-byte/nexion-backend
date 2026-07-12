SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE nx_disclosure_draft ADD COLUMN vi_body TEXT NULL AFTER zh_body',
  'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_disclosure_draft' AND COLUMN_NAME = 'vi_body');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE nx_disclosure_draft SET vi_body = zh_body WHERE vi_body IS NULL OR vi_body = '';

ALTER TABLE nx_disclosure_draft MODIFY COLUMN vi_body TEXT NOT NULL;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE nx_disclosure_chapter ADD COLUMN vi_title VARCHAR(255) NULL AFTER zh_title',
  'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_disclosure_chapter' AND COLUMN_NAME = 'vi_title');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE nx_disclosure_chapter ADD COLUMN vi_body TEXT NULL AFTER zh_body',
  'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_disclosure_chapter' AND COLUMN_NAME = 'vi_body');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE nx_disclosure_chapter
SET vi_title = zh_title, vi_body = zh_body
WHERE vi_title IS NULL OR vi_title = '' OR vi_body IS NULL OR vi_body = '';

ALTER TABLE nx_disclosure_chapter
  MODIFY COLUMN vi_title VARCHAR(255) NOT NULL,
  MODIFY COLUMN vi_body TEXT NOT NULL;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE nx_disclosure_jurisdiction ADD COLUMN country_codes VARCHAR(255) NULL AFTER jurisdiction_name',
  'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_disclosure_jurisdiction' AND COLUMN_NAME = 'country_codes');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE nx_disclosure_jurisdiction
SET country_codes = CASE UPPER(jurisdiction_code)
  WHEN 'SBV' THEN 'VN'
  WHEN 'SFC' THEN 'HK'
  WHEN 'MAS' THEN 'SG'
  WHEN 'FCA' THEN 'GB'
  ELSE UPPER(jurisdiction_code)
END
WHERE country_codes IS NULL OR country_codes = '';

ALTER TABLE nx_disclosure_jurisdiction
  MODIFY COLUMN country_codes VARCHAR(255) NOT NULL DEFAULT '';

CREATE TABLE IF NOT EXISTS nx_disclosure_ack_status (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  jurisdiction_code VARCHAR(32) NOT NULL,
  required_version VARCHAR(32) NOT NULL,
  acknowledged_version VARCHAR(32) NULL,
  ack_status VARCHAR(16) NOT NULL DEFAULT 'STALE',
  acknowledged_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_disclosure_ack_user_jurisdiction (user_id, jurisdiction_code),
  KEY idx_disclosure_ack_gate (jurisdiction_code, required_version, ack_status, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

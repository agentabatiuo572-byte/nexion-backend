-- I1 copy pool: align the physical schema with the Vietnamese-copy and copy-position model.
-- Every statement is idempotent so existing environments can safely re-run this migration.

SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_content_copy' AND COLUMN_NAME = 'draft_vi') = 0,
  'ALTER TABLE nx_content_copy ADD COLUMN draft_vi TEXT NULL AFTER draft_en',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_content_copy' AND COLUMN_NAME = 'revision') = 0,
  'ALTER TABLE nx_content_copy ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 AFTER draft_note',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_content_copy' AND COLUMN_NAME = 'copy_position') = 0,
  'ALTER TABLE nx_content_copy ADD COLUMN copy_position VARCHAR(96) NULL AFTER draft_vi',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_content_copy' AND COLUMN_NAME = 'draft_copy_position') = 0,
  'ALTER TABLE nx_content_copy ADD COLUMN draft_copy_position VARCHAR(96) NULL AFTER copy_position',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_content_copy' AND COLUMN_NAME = 'draft_audience_json') = 0,
  'ALTER TABLE nx_content_copy ADD COLUMN draft_audience_json JSON NULL AFTER draft_audience',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_content_copy_version' AND COLUMN_NAME = 'vi_text') = 0,
  'ALTER TABLE nx_content_copy_version ADD COLUMN vi_text TEXT NULL AFTER en_text',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_content_copy_version' AND COLUMN_NAME = 'copy_position') = 0,
  'ALTER TABLE nx_content_copy_version ADD COLUMN copy_position VARCHAR(96) NULL AFTER vi_text',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_content_copy_version' AND COLUMN_NAME = 'audience_json') = 0,
  'ALTER TABLE nx_content_copy_version ADD COLUMN audience_json JSON NULL AFTER audience',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_content_experiment' AND COLUMN_NAME = 'audience_snapshot_json') = 0,
  'ALTER TABLE nx_content_experiment ADD COLUMN audience_snapshot_json JSON NULL AFTER audience',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_content_experiment_variant' AND COLUMN_NAME = 'copy_version') = 0,
  'ALTER TABLE nx_content_experiment_variant ADD COLUMN copy_version VARCHAR(32) NULL AFTER variant_name',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Backfill only when every live variant resolves to the same immutable audience JSON.
-- Missing versions, NULL audiences, or differing variant audiences remain NULL so runtime is fail-closed.
UPDATE nx_content_experiment experiment
JOIN (
  SELECT candidate.experiment_id,
         MIN(CAST(version.audience_json AS CHAR)) AS audience_snapshot_json
    FROM nx_content_experiment candidate
    JOIN nx_content_experiment_variant variant
      ON variant.experiment_id = candidate.experiment_id
     AND variant.is_deleted = 0
    LEFT JOIN nx_content_copy_version version
      ON version.copy_key = candidate.copy_key
     AND version.version = variant.copy_version
     AND version.is_deleted = 0
   WHERE candidate.is_deleted = 0
     AND candidate.state IN ('SCHEDULED', 'RUNNING')
   GROUP BY candidate.experiment_id
  HAVING COUNT(*) > 0
     AND COUNT(version.id) = COUNT(*)
     AND COUNT(version.audience_json) = COUNT(*)
     AND COUNT(DISTINCT CAST(version.audience_json AS CHAR)) = 1
) safe_snapshot
  ON safe_snapshot.experiment_id = experiment.experiment_id
   SET experiment.audience_snapshot_json = safe_snapshot.audience_snapshot_json
 WHERE experiment.audience_snapshot_json IS NULL
   AND experiment.is_deleted = 0
   AND experiment.state IN ('SCHEDULED', 'RUNNING');

CREATE TABLE IF NOT EXISTS nx_content_experiment_assignment (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  experiment_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  variant_name VARCHAR(128) NOT NULL,
  copy_version VARCHAR(32) NOT NULL,
  bucket_no INT NOT NULL,
  exposed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_content_experiment_assignment (experiment_id, user_id),
  KEY idx_content_experiment_assignment_variant (experiment_id, variant_name),
  KEY idx_content_experiment_assignment_exposed (experiment_id, exposed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_content_experiment_conversion (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  experiment_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  conversion_key VARCHAR(96) NOT NULL,
  variant_name VARCHAR(128) NOT NULL,
  converted_at DATETIME NOT NULL,
  UNIQUE KEY uk_content_experiment_conversion (experiment_id, user_id),
  KEY idx_content_experiment_conversion_variant (experiment_id, variant_name, converted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- A conversion is counted at most once per experiment/user. Preserve the oldest
-- legacy business trace before replacing the former conversion_key-scoped index.
DELETE newer
  FROM nx_content_experiment_conversion newer
  JOIN nx_content_experiment_conversion older
    ON older.experiment_id = newer.experiment_id
   AND older.user_id = newer.user_id
   AND older.id < newer.id;

SET @drop_old_conversion_unique = IF(
  EXISTS (
    SELECT 1
      FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'nx_content_experiment_conversion'
       AND INDEX_NAME = 'uk_content_experiment_conversion'
       AND COLUMN_NAME = 'conversion_key'
  ),
  'ALTER TABLE nx_content_experiment_conversion DROP INDEX uk_content_experiment_conversion',
  'SELECT 1'
);
PREPARE stmt FROM @drop_old_conversion_unique;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_conversion_unique = IF(
  NOT EXISTS (
    SELECT 1
      FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'nx_content_experiment_conversion'
       AND INDEX_NAME = 'uk_content_experiment_conversion'
  ),
  'ALTER TABLE nx_content_experiment_conversion ADD UNIQUE KEY uk_content_experiment_conversion (experiment_id, user_id)',
  'SELECT 1'
);
PREPARE stmt FROM @add_conversion_unique;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_content_copy_version_option (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  version_key VARCHAR(32) NOT NULL,
  name VARCHAR(128) NOT NULL,
  description VARCHAR(255) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  sort_order INT NOT NULL DEFAULT 0,
  revision BIGINT NOT NULL DEFAULT 1,
  last_operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_content_copy_version_option_key (version_key),
  KEY idx_content_copy_version_option_list (is_deleted, status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Default catalog entries match the five versions already used by the I1 prototype.
INSERT IGNORE INTO nx_content_copy_version_option
  (version_key, name, description, status, sort_order, revision, last_operator, created_at, updated_at, is_deleted)
VALUES
  ('v1', CONVERT(0xE78988E69CAC207631 USING utf8mb4), CONVERT(0xE5889DE5A78BE69687E6A188E78988E69CAC USING utf8mb4), 'ACTIVE', 10, 1, 'migration', NOW(), NOW(), 0),
  ('v2', CONVERT(0xE78988E69CAC207632 USING utf8mb4), CONVERT(0xE7ACACE4BA8CE78988E69687E6A188 USING utf8mb4), 'ACTIVE', 20, 1, 'migration', NOW(), NOW(), 0),
  ('v3', CONVERT(0xE78988E69CAC207633 USING utf8mb4), CONVERT(0xE7ACACE4B889E78988E69687E6A188 USING utf8mb4), 'ACTIVE', 30, 1, 'migration', NOW(), NOW(), 0),
  ('v4', CONVERT(0xE78988E69CAC207634 USING utf8mb4), CONVERT(0xE7ACACE59B9BE78988E69687E6A188 USING utf8mb4), 'ACTIVE', 40, 1, 'migration', NOW(), NOW(), 0),
  ('v5', CONVERT(0xE78988E69CAC207635 USING utf8mb4), CONVERT(0xE7ACACE4BA94E78988E69687E6A188 USING utf8mb4), 'ACTIVE', 50, 1, 'migration', NOW(), NOW(), 0);

-- Repair seed rows written by a client with the wrong connection encoding. User-edited rows are preserved.
UPDATE nx_content_copy_version_option
SET name = CASE version_key
      WHEN 'v1' THEN CONVERT(0xE78988E69CAC207631 USING utf8mb4)
      WHEN 'v2' THEN CONVERT(0xE78988E69CAC207632 USING utf8mb4)
      WHEN 'v3' THEN CONVERT(0xE78988E69CAC207633 USING utf8mb4)
      WHEN 'v4' THEN CONVERT(0xE78988E69CAC207634 USING utf8mb4)
      WHEN 'v5' THEN CONVERT(0xE78988E69CAC207635 USING utf8mb4)
    END,
    description = CASE version_key
      WHEN 'v1' THEN CONVERT(0xE5889DE5A78BE69687E6A188E78988E69CAC USING utf8mb4)
      WHEN 'v2' THEN CONVERT(0xE7ACACE4BA8CE78988E69687E6A188 USING utf8mb4)
      WHEN 'v3' THEN CONVERT(0xE7ACACE4B889E78988E69687E6A188 USING utf8mb4)
      WHEN 'v4' THEN CONVERT(0xE7ACACE59B9BE78988E69687E6A188 USING utf8mb4)
      WHEN 'v5' THEN CONVERT(0xE7ACACE4BA94E78988E69687E6A188 USING utf8mb4)
    END
WHERE version_key IN ('v1', 'v2', 'v3', 'v4', 'v5')
  AND is_deleted = 0
  AND revision = 1
  AND last_operator IN ('migration', 'schema');

-- Preserve all existing historical version keys as selectable catalog data.
INSERT IGNORE INTO nx_content_copy_version_option
  (version_key, name, description, status, sort_order, revision, last_operator, created_at, updated_at, is_deleted)
SELECT version,
       CONCAT(CONVERT(0xE78988E69CAC20 USING utf8mb4), version),
       CONVERT(0xE794B1E58E86E58FB2E69687E6A188E78988E69CACE8BF81E7A7BB USING utf8mb4),
       'ACTIVE', 1000, 1, 'migration', NOW(), NOW(), 0
FROM nx_content_copy_version
WHERE version IS NOT NULL AND version <> ''
GROUP BY version;

CREATE TABLE IF NOT EXISTS nx_content_copy_position (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  position_key VARCHAR(96) NOT NULL,
  name VARCHAR(255) NOT NULL,
  surface VARCHAR(32) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  last_operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_content_copy_position_key (position_key),
  KEY idx_content_copy_position_surface (surface, status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- I1 内置一组可直接使用的基础位置；INSERT IGNORE 保证重跑时不覆盖运营配置。
INSERT IGNORE INTO nx_content_copy_position
  (position_key, name, surface, sort_order, status, last_operator, created_at, updated_at, is_deleted)
VALUES
  ('home.hero', '首页顶部主视觉', 'home', 10, 'ACTIVE', 'migration', NOW(), NOW(), 0),
  ('home.conversion-banner', '首页转化横幅', 'home', 20, 'ACTIVE', 'migration', NOW(), NOW(), 0),
  ('store.hero', '商城顶部横幅', 'store', 30, 'ACTIVE', 'migration', NOW(), NOW(), 0),
  ('earn.hero', '赚取顶部横幅', 'earn', 40, 'ACTIVE', 'migration', NOW(), NOW(), 0),
  ('me.notice', '我的页面提示', 'me', 50, 'ACTIVE', 'migration', NOW(), NOW(), 0);

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_content_copy' AND INDEX_NAME = 'idx_content_copy_position') = 0,
  'ALTER TABLE nx_content_copy ADD INDEX idx_content_copy_position (copy_position)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_content_copy' AND INDEX_NAME = 'idx_content_copy_draft_position') = 0,
  'ALTER TABLE nx_content_copy ADD INDEX idx_content_copy_draft_position (draft_copy_position)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_content_copy_version' AND INDEX_NAME = 'idx_content_copy_version_position') = 0,
  'ALTER TABLE nx_content_copy_version ADD INDEX idx_content_copy_version_position (copy_position)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Canonicalize the four fixed UI surfaces while retaining unknown historical values.
UPDATE nx_content_copy
SET surface = CASE
  WHEN LOWER(surface) = 'home' THEN 'home'
  WHEN LOWER(surface) IN ('store', '商城') THEN 'store'
  WHEN LOWER(surface) = 'earn' THEN 'earn'
  WHEN LOWER(surface) = 'me' THEN 'me'
  ELSE surface
END,
draft_surface = CASE
  WHEN LOWER(draft_surface) = 'home' THEN 'home'
  WHEN LOWER(draft_surface) IN ('store', '商城') THEN 'store'
  WHEN LOWER(draft_surface) = 'earn' THEN 'earn'
  WHEN LOWER(draft_surface) = 'me' THEN 'me'
  ELSE draft_surface
END
WHERE surface IN ('Home', 'home', 'Store', 'store', '商城', 'Earn', 'earn', 'Me', 'me')
   OR draft_surface IN ('Home', 'home', 'Store', 'store', '商城', 'Earn', 'earn', 'Me', 'me');

UPDATE nx_content_copy_version
SET surface = CASE
  WHEN LOWER(surface) = 'home' THEN 'home'
  WHEN LOWER(surface) IN ('store', '商城') THEN 'store'
  WHEN LOWER(surface) = 'earn' THEN 'earn'
  WHEN LOWER(surface) = 'me' THEN 'me'
  ELSE surface
END
WHERE surface IN ('Home', 'home', 'Store', 'store', '商城', 'Earn', 'earn', 'Me', 'me');

UPDATE nx_content_copy_position
SET surface = CASE
  WHEN LOWER(surface) = 'home' THEN 'home'
  WHEN LOWER(surface) IN ('store', '商城') THEN 'store'
  WHEN LOWER(surface) = 'earn' THEN 'earn'
  WHEN LOWER(surface) = 'me' THEN 'me'
  ELSE surface
END
WHERE surface IN ('Home', 'home', 'Store', 'store', '商城', 'Earn', 'earn', 'Me', 'me');

-- Backfill structured audience snapshots for legacy rows without discarding the legacy label.
UPDATE nx_content_copy
SET draft_audience_json = CASE CAST(draft_audience AS BINARY)
  WHEN CAST('全量' AS BINARY) THEN JSON_OBJECT('mode', 'structured', 'locales', JSON_ARRAY(), 'tiers', JSON_ARRAY())
  WHEN CAST('P3 · 全语言' AS BINARY) THEN JSON_OBJECT('mode', 'structured', 'locales', JSON_ARRAY(), 'tiers', JSON_ARRAY('P3'))
  WHEN CAST('zh · 注册>30天' AS BINARY) THEN JSON_OBJECT('mode', 'structured', 'locales', JSON_ARRAY('zh'), 'tiers', JSON_ARRAY(), 'registrationDaysMin', 31)
  WHEN CAST('注册 ≤14 天' AS BINARY) THEN JSON_OBJECT('mode', 'structured', 'locales', JSON_ARRAY(), 'tiers', JSON_ARRAY(), 'registrationDaysMax', 14)
  WHEN CAST('P2-P3' AS BINARY) THEN JSON_OBJECT('mode', 'structured', 'locales', JSON_ARRAY(), 'tiers', JSON_ARRAY('P2', 'P3'))
  ELSE NULL
END
WHERE draft_audience_json IS NULL AND draft_audience IS NOT NULL;

UPDATE nx_content_copy_version
SET audience_json = CASE CAST(audience AS BINARY)
  WHEN CAST('全量' AS BINARY) THEN JSON_OBJECT('mode', 'structured', 'locales', JSON_ARRAY(), 'tiers', JSON_ARRAY())
  WHEN CAST('P3 · 全语言' AS BINARY) THEN JSON_OBJECT('mode', 'structured', 'locales', JSON_ARRAY(), 'tiers', JSON_ARRAY('P3'))
  WHEN CAST('zh · 注册>30天' AS BINARY) THEN JSON_OBJECT('mode', 'structured', 'locales', JSON_ARRAY('zh'), 'tiers', JSON_ARRAY(), 'registrationDaysMin', 31)
  WHEN CAST('注册 ≤14 天' AS BINARY) THEN JSON_OBJECT('mode', 'structured', 'locales', JSON_ARRAY(), 'tiers', JSON_ARRAY(), 'registrationDaysMax', 14)
  WHEN CAST('P2-P3' AS BINARY) THEN JSON_OBJECT('mode', 'structured', 'locales', JSON_ARRAY(), 'tiers', JSON_ARRAY('P2', 'P3'))
  ELSE NULL
END
WHERE audience_json IS NULL AND audience IS NOT NULL;

-- Experiment variants must reference copy versions structurally. Legacy variants that cannot
-- be backfilled remain NULL and conservatively block draft deletion for their copy.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_content_experiment_variant' AND COLUMN_NAME = 'copy_version') = 0,
  'ALTER TABLE nx_content_experiment_variant ADD COLUMN copy_version VARCHAR(32) NULL AFTER variant_name',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE nx_content_experiment_variant
SET copy_version = LOWER(REGEXP_SUBSTR(variant_name, 'v[0-9]+'))
WHERE copy_version IS NULL AND variant_name REGEXP '(^|[^A-Za-z0-9])v[0-9]+($|[^A-Za-z0-9])';

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_content_experiment_variant' AND INDEX_NAME = 'idx_content_experiment_variant_version') = 0,
  'ALTER TABLE nx_content_experiment_variant ADD INDEX idx_content_experiment_variant_version (experiment_id, copy_version)',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

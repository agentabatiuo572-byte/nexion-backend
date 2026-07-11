-- I1 copy pool: align the physical schema with the Vietnamese-copy and copy-position model.
-- Every statement is idempotent so existing environments can safely re-run this migration.

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
SET draft_audience_json = CASE draft_audience
  WHEN '全量' THEN JSON_OBJECT('mode', 'structured', 'locales', JSON_ARRAY(), 'tiers', JSON_ARRAY())
  WHEN 'P3 · 全语言' THEN JSON_OBJECT('mode', 'structured', 'locales', JSON_ARRAY(), 'tiers', JSON_ARRAY('P3'))
  WHEN 'zh · 注册>30天' THEN JSON_OBJECT('mode', 'structured', 'locales', JSON_ARRAY('zh'), 'tiers', JSON_ARRAY(), 'registrationDaysMin', 31)
  WHEN '注册 ≤14 天' THEN JSON_OBJECT('mode', 'structured', 'locales', JSON_ARRAY(), 'tiers', JSON_ARRAY(), 'registrationDaysMax', 14)
  WHEN 'P2-P3' THEN JSON_OBJECT('mode', 'structured', 'locales', JSON_ARRAY(), 'tiers', JSON_ARRAY('P2', 'P3'))
  ELSE NULL
END
WHERE draft_audience_json IS NULL AND draft_audience IS NOT NULL;

UPDATE nx_content_copy_version
SET audience_json = CASE audience
  WHEN '全量' THEN JSON_OBJECT('mode', 'structured', 'locales', JSON_ARRAY(), 'tiers', JSON_ARRAY())
  WHEN 'P3 · 全语言' THEN JSON_OBJECT('mode', 'structured', 'locales', JSON_ARRAY(), 'tiers', JSON_ARRAY('P3'))
  WHEN 'zh · 注册>30天' THEN JSON_OBJECT('mode', 'structured', 'locales', JSON_ARRAY('zh'), 'tiers', JSON_ARRAY(), 'registrationDaysMin', 31)
  WHEN '注册 ≤14 天' THEN JSON_OBJECT('mode', 'structured', 'locales', JSON_ARRAY(), 'tiers', JSON_ARRAY(), 'registrationDaysMax', 14)
  WHEN 'P2-P3' THEN JSON_OBJECT('mode', 'structured', 'locales', JSON_ARRAY(), 'tiers', JSON_ARRAY('P2', 'P3'))
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

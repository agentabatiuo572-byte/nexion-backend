CREATE TABLE IF NOT EXISTS nx_learning_progress (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  course_id VARCHAR(96) NOT NULL,
  course_version VARCHAR(32) NOT NULL,
  progress_pct INT NOT NULL DEFAULT 0,
  attempts INT NOT NULL DEFAULT 0,
  last_score INT NOT NULL DEFAULT 0,
  started_at DATETIME NULL,
  completed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_learning_progress_user_course_version (user_id, course_id, course_version),
  KEY idx_learning_progress_user (user_id, updated_at),
  CONSTRAINT chk_learning_progress_pct CHECK (progress_pct BETWEEN 0 AND 100),
  CONSTRAINT chk_learning_progress_score CHECK (last_score BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_learning_course_version (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  course_id VARCHAR(96) NOT NULL,
  version_label VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  payload_json JSON NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_learning_course_version (course_id, version_label),
  KEY idx_learning_course_version_status (course_id, status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_learning_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  course_id VARCHAR(96) NOT NULL,
  course_version VARCHAR(32) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  event_payload JSON NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_learning_event_once (user_id, course_id, course_version, event_type),
  KEY idx_learning_event_type_time (event_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_learning_reward_ledger (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  reward_no VARCHAR(160) NOT NULL,
  user_id BIGINT NOT NULL,
  course_id VARCHAR(96) NOT NULL,
  course_version VARCHAR(32) NOT NULL,
  amount_nex DECIMAL(18,6) NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_learning_reward_user_course_version (user_id, course_id, course_version),
  UNIQUE KEY uk_learning_reward_no (reward_no),
  KEY idx_learning_reward_user (user_id, created_at),
  CONSTRAINT chk_learning_reward_positive CHECK (amount_nex > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Existing environments may already have nx_help_article without the I7 runtime columns.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_help_article' AND COLUMN_NAME = 'quiz_json') = 0,
  'ALTER TABLE nx_help_article ADD COLUMN quiz_json MEDIUMTEXT NULL AFTER tint', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_help_article' AND COLUMN_NAME = 'quiz_pass_score') = 0,
  'ALTER TABLE nx_help_article ADD COLUMN quiz_pass_score INT NULL AFTER quiz_json', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_help_article' AND COLUMN_NAME = 'quiz_retry_limit') = 0,
  'ALTER TABLE nx_help_article ADD COLUMN quiz_retry_limit INT NULL AFTER quiz_pass_score', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_help_article' AND COLUMN_NAME = 'completion_condition') = 0,
  'ALTER TABLE nx_help_article ADD COLUMN completion_condition VARCHAR(255) NULL AFTER quiz_retry_limit', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_help_article' AND COLUMN_NAME = 'reward_event') = 0,
  'ALTER TABLE nx_help_article ADD COLUMN reward_event VARCHAR(32) NULL AFTER completion_condition', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_help_article' AND COLUMN_NAME = 'version_no') = 0,
  'ALTER TABLE nx_help_article ADD COLUMN version_no INT NOT NULL DEFAULT 1 AFTER reward_event', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_help_article' AND COLUMN_NAME = 'revision') = 0,
  'ALTER TABLE nx_help_article ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 AFTER version_no', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO nx_learning_course_version (
  course_id, version_label, status, payload_json, revision, created_at, updated_at, is_deleted
)
SELECT
  SUBSTRING_INDEX(a.article_code, '.', -1),
  CONCAT('v', COALESCE(a.version_no, 1)),
  CASE WHEN a.status = 1 THEN 'PUBLISHED' ELSE 'DRAFT' END,
  JSON_OBJECT(
    'titleZh', a.title, 'titleEn', a.title, 'titleVi', a.title,
    'bodyZh', a.content, 'bodyEn', a.content, 'bodyVi', a.content,
    'category', CASE a.category WHEN 'earn' THEN 'Earn' WHEN 'team' THEN 'Team' WHEN 'wealth' THEN 'Wealth' WHEN 'security' THEN 'Security' ELSE 'Basics' END,
    'format', CASE a.format WHEN 'video' THEN 'Video' WHEN 'interactive' THEN 'Hands-on' ELSE 'Article' END,
    'difficulty', CASE a.level WHEN 'advanced' THEN 'Advanced' WHEN 'intermediate' THEN 'Intermediate' ELSE 'Beginner' END,
    'rewardNex', a.reward_nex, 'duration', CONCAT(COALESCE(a.duration_min, 5), ' min'),
    'publishState', CASE WHEN a.status = 1 THEN 'published' ELSE 'draft' END,
    'operator', 'migration', 'reason', '迁移既有课程版本快照',
    'quizQuestions', COALESCE(JSON_EXTRACT(a.quiz_json, '$'), JSON_ARRAY()),
    'passScore', a.quiz_pass_score, 'retryLimit', a.quiz_retry_limit,
    'completionCondition', a.completion_condition, 'rewardEvent', a.reward_event,
    'expectedRevision', a.revision, 'version', CONCAT('v', COALESCE(a.version_no, 1))
  ),
  COALESCE(a.revision, 0), a.created_at, a.updated_at, 0
FROM nx_help_article a
WHERE a.is_deleted = 0 AND a.article_code LIKE 'learn.%';

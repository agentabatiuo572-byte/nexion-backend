USE nexion;

CREATE TABLE IF NOT EXISTS nx_nova_social_distribution (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  dist_key VARCHAR(64) NOT NULL,
  dist_name VARCHAR(128) NOT NULL,
  pct INT NOT NULL DEFAULT 0,
  color VARCHAR(64) NOT NULL DEFAULT '',
  operator VARCHAR(128) NULL,
  reason VARCHAR(512) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_nova_social_dist_key (dist_key),
  KEY idx_nova_social_dist_order (is_deleted, dist_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_nova_social_distribution
  (dist_key, dist_name, pct, color, operator, reason, created_at, updated_at, is_deleted)
VALUES
  ('withdrawal', '提现到账', 30, 'var(--admin-cat-3)', 'system', 'I2 默认真实事件权重', NOW(), NOW(), 0),
  ('vrank', 'V 等级晋升', 25, 'var(--admin-cat-5)', 'system', 'I2 默认真实事件权重', NOW(), NOW(), 0),
  ('genesis', 'Genesis 成交', 25, 'var(--admin-cat-7)', 'system', 'I2 默认真实事件权重', NOW(), NOW(), 0),
  ('aiClient', 'AI 客户消费', 0, 'var(--admin-cat-2)', 'system', '真实数据源未接入，默认不参与抽样', NOW(), NOW(), 0),
  ('newUsers', '每小时新增用户', 20, 'var(--admin-cat-4)', 'system', 'I2 默认真实事件权重', NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE dist_key = VALUES(dist_key);

CREATE TABLE IF NOT EXISTS nx_nova_social_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_type VARCHAR(32) NOT NULL,
  source_event_id VARCHAR(160) NOT NULL,
  source_system VARCHAR(64) NOT NULL,
  source_table VARCHAR(64) NOT NULL,
  actor_display VARCHAR(64) NOT NULL DEFAULT '',
  city_display VARCHAR(64) NOT NULL DEFAULT '',
  amount_display VARCHAR(64) NOT NULL DEFAULT '',
  source_note VARCHAR(512) NOT NULL DEFAULT '',
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  occurred_at DATETIME NOT NULL,
  expires_at DATETIME NOT NULL,
  verified_at DATETIME NOT NULL,
  last_dispatched_at DATETIME NULL,
  dispatch_count BIGINT NOT NULL DEFAULT 0,
  operator VARCHAR(128) NULL,
  reason VARCHAR(512) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_nova_social_source_event (event_type, source_system, source_event_id),
  KEY idx_nova_social_event_runtime (is_deleted, status, expires_at, event_type),
  KEY idx_nova_social_event_type_runtime (is_deleted, status, event_type, expires_at, occurred_at, id),
  KEY idx_nova_social_event_occurred (occurred_at),
  CONSTRAINT chk_nova_social_event_status CHECK (status IN ('ACTIVE', 'DISABLED', 'EXPIRED')),
  CONSTRAINT chk_nova_social_event_type CHECK (event_type IN ('withdrawal', 'vrank', 'genesis', 'aiClient', 'newUsers')),
  CONSTRAINT chk_nova_social_event_expiry CHECK (expires_at > occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_nova_social_event' AND INDEX_NAME = 'idx_nova_social_event_type_runtime') = 0,
  'ALTER TABLE nx_nova_social_event ADD INDEX idx_nova_social_event_type_runtime (is_deleted, status, event_type, expires_at, occurred_at, id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_nova_social_runtime_slot (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  slot_key VARCHAR(128) NOT NULL,
  lease_owner VARCHAR(128) NOT NULL,
  lease_until DATETIME NOT NULL,
  completed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_nova_social_runtime_slot (slot_key),
  KEY idx_nova_social_runtime_lease (completed_at, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_withdrawal_order' AND INDEX_NAME = 'idx_withdrawal_nova_terminal') = 0,
  'ALTER TABLE nx_withdrawal_order ADD INDEX idx_withdrawal_nova_terminal (status, completed_at, id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_genesis_order' AND INDEX_NAME = 'idx_genesis_nova_terminal') = 0,
  'ALTER TABLE nx_genesis_order ADD INDEX idx_genesis_nova_terminal (status, completed_at, paid_at, id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_level_log' AND INDEX_NAME = 'idx_user_level_nova_event') = 0,
  'ALTER TABLE nx_user_level_log ADD INDEX idx_user_level_nova_event (level_type, created_at, id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user' AND INDEX_NAME = 'idx_user_nova_created') = 0,
  'ALTER TABLE nx_user ADD INDEX idx_user_nova_created (is_deleted, created_at, id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

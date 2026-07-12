CREATE DATABASE IF NOT EXISTS nexion DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE nexion;

SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  country_code VARCHAR(8) NOT NULL,
  phone VARCHAR(32) NOT NULL,
  password_hash VARCHAR(128) NOT NULL,
  nickname VARCHAR(64) NOT NULL,
  avatar_url VARCHAR(512) NULL,
  referral_code VARCHAR(32) NOT NULL,
  sponsor_user_id BIGINT NULL,
  sponsor_code VARCHAR(32) NULL,
  kyc_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  user_level VARCHAR(16) NOT NULL DEFAULT 'L1',
  v_rank VARCHAR(16) NOT NULL DEFAULT 'V0',
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  language VARCHAR(16) NOT NULL DEFAULT 'en-US',
  region VARCHAR(32) NULL,
  bio VARCHAR(512) NULL,
  timezone VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_phone (country_code, phone),
  UNIQUE KEY uk_user_referral_code (referral_code),
  KEY idx_user_sponsor (sponsor_user_id),
  KEY idx_user_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user' AND COLUMN_NAME = 'sponsor_user_id') = 0,
  'ALTER TABLE nx_user ADD COLUMN sponsor_user_id BIGINT NULL AFTER referral_code',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user' AND COLUMN_NAME = 'id') NOT LIKE '%auto_increment%',
  'ALTER TABLE nx_user MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user' AND COLUMN_NAME = 'deleted') > 0
    AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user' AND COLUMN_NAME = 'is_deleted') = 0,
  'ALTER TABLE nx_user CHANGE COLUMN deleted is_deleted TINYINT NOT NULL DEFAULT 0',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user' AND COLUMN_NAME = 'is_deleted') = 0,
  'ALTER TABLE nx_user ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user' AND COLUMN_NAME = 'language') = 0,
  'ALTER TABLE nx_user ADD COLUMN language VARCHAR(16) NOT NULL DEFAULT ''en-US'' AFTER status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user' AND COLUMN_NAME = 'region') = 0,
  'ALTER TABLE nx_user ADD COLUMN region VARCHAR(32) NULL AFTER language',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_product_review (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  product_id BIGINT NOT NULL,
  user_id BIGINT NULL,
  order_id BIGINT NULL,
  active_order_id BIGINT GENERATED ALWAYS AS (CASE WHEN is_deleted = 0 THEN order_id ELSE NULL END) STORED,
  rating DECIMAL(3,2) NOT NULL,
  title VARCHAR(128) NULL,
  content VARCHAR(1000) NULL,
  media_object_keys JSON NULL,
  avatar_object_key VARCHAR(512) NULL,
  avatar_color VARCHAR(32) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'VISIBLE',
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_product_review_product (product_id, status, sort_order),
  KEY idx_product_review_user (user_id, created_at),
  UNIQUE KEY uk_product_review_active_order_user (active_order_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product_review' AND COLUMN_NAME = 'avatar_object_key') = 0,
  'ALTER TABLE nx_product_review ADD COLUMN avatar_object_key VARCHAR(512) NULL AFTER media_object_keys',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product_review' AND COLUMN_NAME = 'avatar_color') = 0,
  'ALTER TABLE nx_product_review ADD COLUMN avatar_color VARCHAR(32) NULL AFTER avatar_object_key',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product_review' AND COLUMN_NAME = 'active_order_id') = 0,
  'ALTER TABLE nx_product_review ADD COLUMN active_order_id BIGINT GENERATED ALWAYS AS (CASE WHEN is_deleted = 0 THEN order_id ELSE NULL END) STORED AFTER order_id',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product_review' AND INDEX_NAME = 'uk_product_review_active_order_user') = 0,
  'ALTER TABLE nx_product_review ADD UNIQUE KEY uk_product_review_active_order_user (active_order_id, user_id)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_product_waitlist (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  product_id BIGINT NOT NULL,
  active_product_id BIGINT GENERATED ALWAYS AS (CASE WHEN is_deleted = 0 AND status = 'ACTIVE' THEN product_id ELSE NULL END) STORED,
  product_no VARCHAR(64) NULL,
  product_name VARCHAR(128) NULL,
  user_id BIGINT NOT NULL,
  unlock_phase VARCHAR(32) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  notified_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_product_waitlist_product (product_id, status, created_at),
  KEY idx_product_waitlist_user (user_id, created_at),
  UNIQUE KEY uk_product_waitlist_active_product_user (active_product_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product_waitlist' AND COLUMN_NAME = 'active_product_id') = 0,
  'ALTER TABLE nx_product_waitlist ADD COLUMN active_product_id BIGINT GENERATED ALWAYS AS (CASE WHEN is_deleted = 0 AND status = ''ACTIVE'' THEN product_id ELSE NULL END) STORED AFTER product_id',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product_waitlist' AND INDEX_NAME = 'uk_product_waitlist_active_product_user') = 0,
  'ALTER TABLE nx_product_waitlist ADD UNIQUE KEY uk_product_waitlist_active_product_user (active_product_id, user_id)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_product_faq (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  product_id BIGINT NOT NULL,
  question VARCHAR(255) NOT NULL,
  answer VARCHAR(1200) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'VISIBLE',
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_product_faq_product (product_id, status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_product_spec (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  product_id BIGINT NOT NULL,
  spec_group VARCHAR(64) NOT NULL DEFAULT 'GENERAL',
  spec_key VARCHAR(96) NOT NULL,
  spec_value VARCHAR(512) NOT NULL,
  unit VARCHAR(32) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'VISIBLE',
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_product_spec_product (product_id, status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_price_index (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  metric_code VARCHAR(96) NOT NULL,
  metric_label VARCHAR(128) NOT NULL,
  unit_label VARCHAR(64) NOT NULL,
  price_usdt DECIMAL(18,8) NOT NULL DEFAULT 0,
  delta_percent DECIMAL(9,4) NOT NULL DEFAULT 0,
  volume_24h_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  sparkline JSON NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  sampled_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_price_index_metric (metric_code, status, sampled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_treasury_reserve_ledger (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  reserve_no VARCHAR(64) NOT NULL,
  voucher_no VARCHAR(96) NOT NULL,
  direction VARCHAR(16) NOT NULL,
  amount_usd DECIMAL(24,6) NOT NULL DEFAULT 0,
  reason VARCHAR(512) NULL,
  operator VARCHAR(64) NULL,
  idempotency_key VARCHAR(128) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'CONFIRMED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_treasury_reserve_no (reserve_no),
  UNIQUE KEY uk_treasury_reserve_voucher (voucher_no),
  KEY idx_treasury_reserve_status (status, direction, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_price_index' AND COLUMN_NAME = 'volume_24h_usdt') = 0,
  'ALTER TABLE nx_price_index ADD COLUMN volume_24h_usdt DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER delta_percent',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_page_snapshot (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  page_code VARCHAR(128) NOT NULL,
  locale VARCHAR(16) NOT NULL DEFAULT 'en',
  snapshot_key VARCHAR(128) NOT NULL,
  snapshot_value JSON NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  published_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_page_snapshot_key (page_code, locale, snapshot_key),
  KEY idx_page_snapshot_status (page_code, status, published_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user' AND COLUMN_NAME = 'bio') = 0,
  'ALTER TABLE nx_user ADD COLUMN bio VARCHAR(512) NULL AFTER region',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user' AND COLUMN_NAME = 'timezone') = 0,
  'ALTER TABLE nx_user ADD COLUMN timezone VARCHAR(64) NULL AFTER bio',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user' AND INDEX_NAME = 'idx_user_sponsor') = 0,
  'ALTER TABLE nx_user ADD INDEX idx_user_sponsor (sponsor_user_id)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user' AND INDEX_NAME = 'idx_user_status') = 0,
  'ALTER TABLE nx_user ADD INDEX idx_user_status (status)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_user_profile (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  display_name VARCHAR(96) NULL,
  email VARCHAR(128) NULL,
  wallet_address VARCHAR(128) NULL,
  bio VARCHAR(512) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_profile_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_user_preference (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  sound_enabled TINYINT NOT NULL DEFAULT 1,
  haptics_enabled TINYINT NOT NULL DEFAULT 1,
  notify_commission TINYINT NOT NULL DEFAULT 1,
  notify_team TINYINT NOT NULL DEFAULT 1,
  notify_staking TINYINT NOT NULL DEFAULT 1,
  notify_market TINYINT NOT NULL DEFAULT 1,
  notify_genesis TINYINT NOT NULL DEFAULT 1,
  notify_system TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_preference_user (user_id),
  KEY idx_user_preference_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_user_security (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  two_factor_enabled TINYINT NOT NULL DEFAULT 0,
  login_fail_count INT NOT NULL DEFAULT 0,
  last_login_at DATETIME NULL,
  last_login_ip VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_security_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_user_session (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  refresh_token_id VARCHAR(96) NOT NULL,
  device_name VARCHAR(128) NULL,
  client_ip VARCHAR(64) NULL,
  expires_at DATETIME NOT NULL,
  revoked_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_session_token (refresh_token_id),
  KEY idx_user_session_user (user_id, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_user_impersonation_session (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_no VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  ttl_minutes INT NOT NULL,
  operator VARCHAR(64) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  expires_at DATETIME NOT NULL,
  terminated_by VARCHAR(64) NULL,
  terminate_reason VARCHAR(255) NULL,
  terminated_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_impersonation_session_no (session_no),
  KEY idx_user_impersonation_user (user_id, status, expires_at),
  KEY idx_user_impersonation_operator (operator, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_impersonation_session' AND COLUMN_NAME = 'terminated_by') = 0,
  'ALTER TABLE nx_user_impersonation_session ADD COLUMN terminated_by VARCHAR(64) NULL AFTER expires_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_impersonation_session' AND COLUMN_NAME = 'terminate_reason') = 0,
  'ALTER TABLE nx_user_impersonation_session ADD COLUMN terminate_reason VARCHAR(255) NULL AFTER terminated_by',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_impersonation_session' AND COLUMN_NAME = 'terminated_at') = 0,
  'ALTER TABLE nx_user_impersonation_session ADD COLUMN terminated_at DATETIME NULL AFTER terminate_reason',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_sponsorship (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  sponsor_user_id BIGINT NOT NULL,
  sponsor_code VARCHAR(32) NOT NULL,
  bound_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_sponsorship_user (user_id),
  KEY idx_sponsorship_sponsor (sponsor_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_admin (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(128) NOT NULL,
  nickname VARCHAR(64) NULL,
  email VARCHAR(128) NULL,
  phone VARCHAR(32) NULL,
  super_admin TINYINT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_admin_role (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_code VARCHAR(64) NOT NULL,
  role_name VARCHAR(64) NOT NULL,
  remark VARCHAR(255) NULL,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_admin_permission (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  permission_code VARCHAR(96) NOT NULL,
  permission_name VARCHAR(96) NOT NULL,
  resource_type VARCHAR(32) NOT NULL,
  perm_type VARCHAR(16) NULL,
  amplifies TINYINT NOT NULL DEFAULT 0,
  resource_path VARCHAR(255) NULL,
  menu_id BIGINT NULL,
  remark VARCHAR(255) NULL,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_permission_code (permission_code),
  KEY idx_admin_permission_menu (menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_admin_role (role_code, role_name, remark, status, is_deleted) VALUES
('SUPER_ADMIN', '超级管理员', '平台全域管理员', 1, 0),
('CONFIG_ADMIN', '配置运营', '平台配置与系统参数管理员', 1, 0),
('FINANCE', '财务', '资金、账务与提现审核', 1, 0),
('RISK', '风控', '风控、KYC 与紧急处置', 1, 0),
('CONTENT', '内容运营', '内容、公告与披露管理', 1, 0),
('GROWTH', '增长运营', '增长、设备与网络运营', 1, 0),
('SUPPORT', '客服', '客服中心全局后台角色', 1, 0),
('AUDITOR', '只读审计', '审计与合规只读观察', 1, 0)
ON DUPLICATE KEY UPDATE role_name=VALUES(role_name), remark=VALUES(remark),
 status=1, is_deleted=0, updated_at=NOW();

CREATE TABLE IF NOT EXISTS nx_admin_menu (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  menu_code VARCHAR(96) NOT NULL,
  menu_name VARCHAR(96) NOT NULL,
  menu_name_zh VARCHAR(96) NULL,
  menu_name_en VARCHAR(96) NULL,
  parent_id BIGINT NULL,
  route_path VARCHAR(255) NOT NULL,
  icon VARCHAR(64) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  remark VARCHAR(255) NULL,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_menu_code (menu_code),
  KEY idx_admin_menu_parent (parent_id),
  KEY idx_admin_menu_sort (sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_admin_role_relation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  admin_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_role_relation (admin_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_admin_account_state (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  admin_id BIGINT NOT NULL,
  tfa_required TINYINT NOT NULL DEFAULT 1,
  last_login_at DATETIME NULL,
  tfa_reset_at DATETIME NULL,
  sessions_revoked_at DATETIME NULL,
  credential_delivery_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_account_state_admin (admin_id),
  KEY idx_admin_account_state_delivery (credential_delivery_status),
  CONSTRAINT fk_admin_account_state_admin
    FOREIGN KEY (admin_id) REFERENCES nx_admin(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_admin_rbac_action (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  action_id VARCHAR(96) NOT NULL,
  action_name VARCHAR(160) NOT NULL,
  domain_group VARCHAR(64) NOT NULL,
  sort_order INT NOT NULL DEFAULT 9999,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_rbac_action_code (action_id),
  KEY idx_admin_rbac_action_status_sort (status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_admin_rbac_grant (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  action_id VARCHAR(96) NOT NULL,
  role_key VARCHAR(64) NOT NULL,
  grant_value VARCHAR(8) NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_rbac_grant_action_role (action_id, role_key),
  KEY idx_admin_rbac_grant_role (role_key, status),
  CONSTRAINT fk_admin_rbac_grant_action
    FOREIGN KEY (action_id) REFERENCES nx_admin_rbac_action(action_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_admin_security_baseline (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  baseline_key VARCHAR(64) NOT NULL,
  label VARCHAR(128) NOT NULL,
  description VARCHAR(512) NOT NULL DEFAULT '',
  baseline_value VARCHAR(128) NOT NULL,
  locked TINYINT NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 9999,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_security_baseline_key (baseline_key),
  KEY idx_admin_security_baseline_status_sort (status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_admin_role_permission (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_id BIGINT NOT NULL,
  permission_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_role_permission (role_id, permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_admin_role_menu (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_id BIGINT NOT NULL,
  menu_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_role_menu (role_id, menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_admin_device_generation_gate (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  sku_id VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  release_month INT NOT NULL,
  phase VARCHAR(32) NOT NULL DEFAULT '',
  phase_id BIGINT NULL,
  tradein_discount DECIMAL(18,4) NOT NULL DEFAULT 0,
  eligibility TINYINT NOT NULL DEFAULT 0,
  phase_offset INT NOT NULL DEFAULT 0,
  force_unlock TINYINT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'active',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_device_generation_gate_sku (sku_id),
  KEY idx_admin_device_generation_gate_status (status, is_deleted),
  KEY idx_admin_device_generation_gate_phase (phase, release_month),
  KEY idx_admin_device_generation_gate_phase_id (phase_id, release_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_device_generation_gate' AND COLUMN_NAME = 'phase') < 32,
  'ALTER TABLE nx_admin_device_generation_gate MODIFY COLUMN phase VARCHAR(32) NOT NULL DEFAULT ''''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_device_generation_gate' AND COLUMN_NAME = 'phase_id') = 0,
  'ALTER TABLE nx_admin_device_generation_gate ADD COLUMN phase_id BIGINT NULL AFTER phase',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_device_generation_gate' AND INDEX_NAME = 'idx_admin_device_generation_gate_phase_id') = 0,
  'ALTER TABLE nx_admin_device_generation_gate ADD INDEX idx_admin_device_generation_gate_phase_id (phase_id, release_month)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_admin_phase_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  scope VARCHAR(32) NOT NULL DEFAULT 'E1',
  label VARCHAR(128) NOT NULL,
  meta VARCHAR(128) DEFAULT NULL,
  sku_label VARCHAR(255) DEFAULT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'active',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_phase_scope_label (scope, label),
  KEY idx_admin_phase_scope_sort (scope, status, is_deleted, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_phase_config' AND COLUMN_NAME = 'phase_id') > 0,
  'ALTER TABLE nx_admin_phase_config MODIFY COLUMN phase_id VARCHAR(32) NULL DEFAULT NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_phase_config' AND INDEX_NAME = 'uk_admin_phase_scope_label') = 0,
  'ALTER TABLE nx_admin_phase_config ADD UNIQUE KEY uk_admin_phase_scope_label (scope, label)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_user_wallet (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  usdt_available DECIMAL(18,6) NOT NULL DEFAULT 0,
  nex_available DECIMAL(18,6) NOT NULL DEFAULT 0,
  pending_withdraw DECIMAL(18,6) NOT NULL DEFAULT 0,
  lifetime_earned DECIMAL(18,6) NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_wallet_user (user_id),
  CONSTRAINT chk_user_wallet_non_negative CHECK (
    usdt_available >= 0
    AND nex_available >= 0
    AND pending_withdraw >= 0
    AND lifetime_earned >= 0
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_wallet' AND COLUMN_NAME = 'version') = 0,
  'ALTER TABLE nx_user_wallet ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER lifetime_earned',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_wallet' AND COLUMN_NAME = 'is_deleted') = 0,
  'ALTER TABLE nx_user_wallet ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_user_wallet_non_negative') = 0,
  'ALTER TABLE nx_user_wallet ADD CONSTRAINT chk_user_wallet_non_negative CHECK (usdt_available >= 0 AND nex_available >= 0 AND pending_withdraw >= 0 AND lifetime_earned >= 0)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_wallet_ledger (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  biz_no VARCHAR(96) NOT NULL,
  biz_type VARCHAR(64) NOT NULL,
  asset VARCHAR(16) NOT NULL,
  direction VARCHAR(16) NOT NULL,
  amount DECIMAL(18,6) NOT NULL,
  balance_after DECIMAL(18,6) NOT NULL,
  status VARCHAR(32) NOT NULL,
  remark VARCHAR(255) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_wallet_ledger_biz (biz_no, asset, direction),
  KEY idx_wallet_ledger_user_time (user_id, created_at),
  CONSTRAINT chk_wallet_ledger_positive_amount CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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

CREATE TABLE IF NOT EXISTS nx_wallet_asset_adjustment (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  adjustment_no VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  asset VARCHAR(16) NOT NULL,
  direction VARCHAR(16) NOT NULL,
  amount DECIMAL(18,6) NOT NULL,
  reason_code VARCHAR(64) NOT NULL,
  reason VARCHAR(255) NOT NULL,
  maker VARCHAR(64) NOT NULL,
  checker VARCHAR(64) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  ledger_id BIGINT NULL,
  review_reason VARCHAR(255) NULL,
  reviewed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_wallet_asset_adjustment_no (adjustment_no),
  KEY idx_wallet_asset_adjustment_user_time (user_id, created_at),
  KEY idx_wallet_asset_adjustment_status_time (status, created_at),
  CONSTRAINT chk_wallet_asset_adjustment_amount CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_wallet_ledger' AND INDEX_NAME = 'uk_wallet_ledger_biz') = 0,
  'ALTER TABLE nx_wallet_ledger ADD UNIQUE KEY uk_wallet_ledger_biz (biz_no, asset, direction)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_wallet_ledger_positive_amount') = 0,
  'ALTER TABLE nx_wallet_ledger ADD CONSTRAINT chk_wallet_ledger_positive_amount CHECK (amount > 0)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_wallet_bank_card (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  card_token VARCHAR(96) NOT NULL,
  cardholder_name VARCHAR(80) NOT NULL,
  brand VARCHAR(32) NOT NULL,
  last4 VARCHAR(4) NOT NULL,
  country_code VARCHAR(8) NULL,
  status VARCHAR(32) NOT NULL,
  is_default TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_wallet_card_token (card_token),
  KEY idx_wallet_card_user (user_id, status, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_nex_lock_order (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  lock_no VARCHAR(96) NOT NULL,
  amount_nex DECIMAL(18,6) NOT NULL,
  apy_bps DECIMAL(18,6) NOT NULL,
  term_months INT NOT NULL,
  locked_at DATETIME NOT NULL,
  unlock_at DATETIME NOT NULL,
  estimated_reward_nex DECIMAL(18,6) NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_nex_lock_no (lock_no),
  KEY idx_nex_lock_user_status (user_id, status, unlock_at),
  CONSTRAINT chk_nex_lock_positive_amount CHECK (amount_nex > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_staking_product (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  product_code VARCHAR(64) NOT NULL,
  name VARCHAR(120) NOT NULL,
  asset VARCHAR(16) NOT NULL DEFAULT 'USDT',
  term_days INT NOT NULL,
  apy_bps DECIMAL(18,6) NOT NULL,
  early_penalty_bps DECIMAL(18,6) NOT NULL DEFAULT 0,
  min_amount DECIMAL(18,6) NOT NULL,
  reward_multiplier DECIMAL(18,6) NOT NULL DEFAULT 0,
  ticket_per_order INT NOT NULL DEFAULT 0,
  preset_amounts VARCHAR(255) NOT NULL DEFAULT '',
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_staking_product_code (product_code),
  KEY idx_staking_product_status (asset, status, sort_order),
  CONSTRAINT chk_staking_product_positive_term CHECK (term_days > 0),
  CONSTRAINT chk_staking_product_positive_apy CHECK (apy_bps > 0),
  CONSTRAINT chk_staking_product_positive_min CHECK (min_amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_staking_product' AND COLUMN_NAME = 'reward_multiplier') = 0,
  'ALTER TABLE nx_staking_product ADD COLUMN reward_multiplier DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER min_amount',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_staking_product' AND COLUMN_NAME = 'ticket_per_order') = 0,
  'ALTER TABLE nx_staking_product ADD COLUMN ticket_per_order INT NOT NULL DEFAULT 0 AFTER reward_multiplier',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_staking_product' AND COLUMN_NAME = 'preset_amounts') = 0,
  'ALTER TABLE nx_staking_product ADD COLUMN preset_amounts VARCHAR(255) NOT NULL DEFAULT '''' AFTER ticket_per_order',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_staking_position (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  position_no VARCHAR(96) NOT NULL,
  product_id BIGINT NOT NULL,
  product_code VARCHAR(64) NOT NULL,
  product_name VARCHAR(120) NOT NULL,
  amount_usdt DECIMAL(18,6) NOT NULL,
  apy_bps DECIMAL(18,6) NOT NULL,
  early_penalty_bps DECIMAL(18,6) NOT NULL DEFAULT 0,
  term_days INT NOT NULL,
  locked_at DATETIME NOT NULL,
  unlock_at DATETIME NOT NULL,
  estimated_interest_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL,
  claimed_at DATETIME NULL,
  early_withdrawn_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_staking_position_no (position_no),
  KEY idx_staking_position_user_status (user_id, status, unlock_at),
  KEY idx_staking_position_product (product_id, status),
  CONSTRAINT chk_staking_position_positive_amount CHECK (amount_usdt > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_deposit_order (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  deposit_no VARCHAR(96) NOT NULL,
  chain_name VARCHAR(32) NOT NULL,
  chain_tx_hash VARCHAR(128) NOT NULL,
  asset VARCHAR(16) NOT NULL,
  amount DECIMAL(18,6) NOT NULL,
  confirmations INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL,
  ledger_id BIGINT NULL,
  confirmed_at DATETIME NULL,
  credited_at DATETIME NULL,
  failed_at DATETIME NULL,
  failure_reason VARCHAR(255) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_deposit_no (deposit_no),
  UNIQUE KEY uk_deposit_chain_tx_asset (chain_tx_hash, asset),
  KEY idx_deposit_user_time (user_id, created_at),
  KEY idx_deposit_status_time (status, created_at),
  CONSTRAINT chk_deposit_positive_amount CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_deposit_reconciliation_writeoff (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  channel_code VARCHAR(64) NOT NULL,
  reconcile_date DATE NOT NULL,
  operator VARCHAR(64) NOT NULL,
  reason VARCHAR(255) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'RECONCILED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_deposit_reconcile_writeoff (channel_code, reconcile_date),
  UNIQUE KEY uk_deposit_reconcile_idem (idempotency_key),
  KEY idx_deposit_reconcile_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_deposit_order' AND INDEX_NAME = 'uk_deposit_chain_tx_asset') = 0,
  'ALTER TABLE nx_deposit_order ADD UNIQUE KEY uk_deposit_chain_tx_asset (chain_tx_hash, asset)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_deposit_order' AND INDEX_NAME = 'idx_deposit_status_time') = 0,
  'ALTER TABLE nx_deposit_order ADD INDEX idx_deposit_status_time (status, created_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_deposit_positive_amount') = 0,
  'ALTER TABLE nx_deposit_order ADD CONSTRAINT chk_deposit_positive_amount CHECK (amount > 0)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_withdrawal_order (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  withdrawal_no VARCHAR(96) NOT NULL,
  asset VARCHAR(16) NOT NULL,
  chain VARCHAR(32) NOT NULL DEFAULT 'USDT-TRC20',
  amount DECIMAL(18,6) NOT NULL,
  fee DECIMAL(18,6) NOT NULL DEFAULT 0,
  target_address VARCHAR(128) NOT NULL,
  risk_decision_id BIGINT NULL,
  chain_tx_hash VARCHAR(128) NULL,
  status VARCHAR(32) NOT NULL,
  chain_submitted_at DATETIME NULL,
  completed_at DATETIME NULL,
  failed_at DATETIME NULL,
  failure_reason VARCHAR(255) NULL,
  chain_broadcast_attempts INT NOT NULL DEFAULT 0,
  next_broadcast_at DATETIME NULL,
  last_broadcast_error VARCHAR(512) NULL,
  broadcast_dead_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_withdrawal_no (withdrawal_no),
  UNIQUE KEY uk_withdrawal_chain_tx (chain_tx_hash),
  KEY idx_withdrawal_user_time (user_id, created_at),
  KEY idx_withdrawal_status (status, created_at),
  KEY idx_withdrawal_broadcast_due (status, next_broadcast_at),
  CONSTRAINT chk_withdrawal_positive_amount CHECK (amount > 0 AND fee >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_withdrawal_order' AND COLUMN_NAME = 'chain_tx_hash') = 0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN chain_tx_hash VARCHAR(128) NULL AFTER risk_decision_id',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_withdrawal_order' AND COLUMN_NAME = 'chain') = 0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN chain VARCHAR(32) NOT NULL DEFAULT ''USDT-TRC20'' AFTER asset',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_withdrawal_order' AND COLUMN_NAME = 'chain_submitted_at') = 0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN chain_submitted_at DATETIME NULL AFTER status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_withdrawal_order' AND COLUMN_NAME = 'completed_at') = 0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN completed_at DATETIME NULL AFTER chain_submitted_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_withdrawal_order' AND COLUMN_NAME = 'failed_at') = 0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN failed_at DATETIME NULL AFTER completed_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_withdrawal_order' AND COLUMN_NAME = 'failure_reason') = 0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN failure_reason VARCHAR(255) NULL AFTER failed_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_withdrawal_order' AND COLUMN_NAME = 'chain_broadcast_attempts') = 0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN chain_broadcast_attempts INT NOT NULL DEFAULT 0 AFTER failure_reason',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_withdrawal_order' AND COLUMN_NAME = 'next_broadcast_at') = 0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN next_broadcast_at DATETIME NULL AFTER chain_broadcast_attempts',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_withdrawal_order' AND COLUMN_NAME = 'last_broadcast_error') = 0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN last_broadcast_error VARCHAR(512) NULL AFTER next_broadcast_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_withdrawal_order' AND COLUMN_NAME = 'broadcast_dead_at') = 0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN broadcast_dead_at DATETIME NULL AFTER last_broadcast_error',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_withdrawal_order' AND INDEX_NAME = 'uk_withdrawal_chain_tx') = 0,
  'ALTER TABLE nx_withdrawal_order ADD UNIQUE KEY uk_withdrawal_chain_tx (chain_tx_hash)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_withdrawal_order' AND INDEX_NAME = 'idx_withdrawal_status') = 0,
  'ALTER TABLE nx_withdrawal_order ADD INDEX idx_withdrawal_status (status, created_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_withdrawal_order' AND INDEX_NAME = 'idx_withdrawal_broadcast_due') = 0,
  'ALTER TABLE nx_withdrawal_order ADD INDEX idx_withdrawal_broadcast_due (status, next_broadcast_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_withdrawal_positive_amount') = 0,
  'ALTER TABLE nx_withdrawal_order ADD CONSTRAINT chk_withdrawal_positive_amount CHECK (amount > 0 AND fee >= 0)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_exchange_order (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  exchange_no VARCHAR(96) NOT NULL,
  from_asset VARCHAR(16) NOT NULL,
  to_asset VARCHAR(16) NOT NULL,
  from_amount DECIMAL(18,6) NOT NULL,
  to_amount DECIMAL(18,6) NOT NULL,
  rate DECIMAL(18,8) NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_exchange_no (exchange_no),
  KEY idx_exchange_user_time (user_id, created_at),
  CONSTRAINT chk_exchange_positive_amount CHECK (from_amount > 0 AND to_amount > 0 AND rate > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_exchange_positive_amount') = 0,
  'ALTER TABLE nx_exchange_order ADD CONSTRAINT chk_exchange_positive_amount CHECK (from_amount > 0 AND to_amount > 0 AND rate > 0)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_product (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  product_no VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  product_type VARCHAR(32) NOT NULL,
  tier VARCHAR(32) NULL,
  status VARCHAR(32) NOT NULL,
  price_usdt DECIMAL(18,2) NOT NULL DEFAULT 0,
  hashrate DECIMAL(18,6) NOT NULL DEFAULT 0,
  estimated_daily_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  daily_nex DECIMAL(18,6) NOT NULL DEFAULT 0,
  stock INT NOT NULL DEFAULT 0,
  cover_url VARCHAR(512) NULL,
  detail_image_urls TEXT NULL,
  badge VARCHAR(64) NULL,
  tagline VARCHAR(255) NULL,
  store_status VARCHAR(32) NULL,
  store_visible TINYINT NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 0,
  generation INT NOT NULL DEFAULT 1,
  gpu_model VARCHAR(128) NULL,
  vram_total_gb INT NULL,
  ai_performance_json VARCHAR(2048) NULL,
  detail_metrics_json TEXT NULL,
  hardware_specs_json TEXT NULL,
  review_summary_json TEXT NULL,
  reviews_json TEXT NULL,
  trust_json TEXT NULL,
  faq_json TEXT NULL,
  phone_compare_json TEXT NULL,
  share_yield_min DECIMAL(10,4) NULL,
  share_yield_max DECIMAL(10,4) NULL,
  superseded_by_product_no VARCHAR(64) NULL,
  unlock_phase VARCHAR(32) NULL,
  sold_count INT NOT NULL DEFAULT 0,
  rating_value DECIMAL(3,2) NOT NULL DEFAULT 0,
  review_count INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_product_no (product_no),
  KEY idx_product_sale (status),
  KEY idx_product_store (store_visible, store_status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'detail_image_urls') = 0,
  'ALTER TABLE nx_product ADD COLUMN detail_image_urls TEXT NULL AFTER cover_url',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'badge') = 0,
  'ALTER TABLE nx_product ADD COLUMN badge VARCHAR(64) NULL AFTER detail_image_urls',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'tagline') = 0,
  'ALTER TABLE nx_product ADD COLUMN tagline VARCHAR(255) NULL AFTER badge',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'store_status') = 0,
  'ALTER TABLE nx_product ADD COLUMN store_status VARCHAR(32) NULL AFTER tagline',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'store_visible') = 0,
  'ALTER TABLE nx_product ADD COLUMN store_visible TINYINT NOT NULL DEFAULT 1 AFTER store_status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'store_featured') = 0,
  'ALTER TABLE nx_product ADD COLUMN store_featured TINYINT NOT NULL DEFAULT 0 AFTER store_visible',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'sort_order') = 0,
  'ALTER TABLE nx_product ADD COLUMN sort_order INT NOT NULL DEFAULT 0 AFTER store_featured',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'generation') = 0,
  'ALTER TABLE nx_product ADD COLUMN generation INT NOT NULL DEFAULT 1 AFTER sort_order',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'gpu_model') = 0,
  'ALTER TABLE nx_product ADD COLUMN gpu_model VARCHAR(128) NULL AFTER generation',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'vram_total_gb') = 0,
  'ALTER TABLE nx_product ADD COLUMN vram_total_gb INT NULL AFTER gpu_model',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'ai_performance_json') = 0,
  'ALTER TABLE nx_product ADD COLUMN ai_performance_json VARCHAR(2048) NULL AFTER vram_total_gb',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'detail_metrics_json') = 0,
  'ALTER TABLE nx_product ADD COLUMN detail_metrics_json TEXT NULL AFTER ai_performance_json',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'hardware_specs_json') = 0,
  'ALTER TABLE nx_product ADD COLUMN hardware_specs_json TEXT NULL AFTER detail_metrics_json',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'review_summary_json') = 0,
  'ALTER TABLE nx_product ADD COLUMN review_summary_json TEXT NULL AFTER hardware_specs_json',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'reviews_json') = 0,
  'ALTER TABLE nx_product ADD COLUMN reviews_json TEXT NULL AFTER review_summary_json',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'trust_json') = 0,
  'ALTER TABLE nx_product ADD COLUMN trust_json TEXT NULL AFTER reviews_json',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'faq_json') = 0,
  'ALTER TABLE nx_product ADD COLUMN faq_json TEXT NULL AFTER trust_json',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'phone_compare_json') = 0,
  'ALTER TABLE nx_product ADD COLUMN phone_compare_json TEXT NULL AFTER faq_json',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'share_yield_min') = 0,
  'ALTER TABLE nx_product ADD COLUMN share_yield_min DECIMAL(10,4) NULL AFTER phone_compare_json',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'share_yield_max') = 0,
  'ALTER TABLE nx_product ADD COLUMN share_yield_max DECIMAL(10,4) NULL AFTER share_yield_min',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'superseded_by_product_no') = 0,
  'ALTER TABLE nx_product ADD COLUMN superseded_by_product_no VARCHAR(64) NULL AFTER share_yield_max',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'unlock_phase') = 0,
  'ALTER TABLE nx_product ADD COLUMN unlock_phase VARCHAR(32) NULL AFTER superseded_by_product_no',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'unlock_phase') < 32,
  'ALTER TABLE nx_product MODIFY COLUMN unlock_phase VARCHAR(32) NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
UPDATE nx_product
SET unlock_phase = CONCAT('P', GREATEST(COALESCE(generation, 1), 1))
WHERE unlock_phase IS NULL OR unlock_phase = '';
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'sold_count') = 0,
  'ALTER TABLE nx_product ADD COLUMN sold_count INT NOT NULL DEFAULT 0 AFTER unlock_phase',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'rating_value') = 0,
  'ALTER TABLE nx_product ADD COLUMN rating_value DECIMAL(3,2) NOT NULL DEFAULT 0 AFTER sold_count',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'review_count') = 0,
  'ALTER TABLE nx_product ADD COLUMN review_count INT NOT NULL DEFAULT 0 AFTER rating_value',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_order (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  order_no VARCHAR(96) NOT NULL,
  product_id BIGINT NOT NULL,
  quantity INT NOT NULL DEFAULT 1,
  order_type VARCHAR(32) NOT NULL DEFAULT 'SINGLE',
  item_count INT NOT NULL DEFAULT 1,
  subtotal_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  discount_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  amount_usdt DECIMAL(18,6) NOT NULL,
  payment_no VARCHAR(96) NULL,
  payment_status VARCHAR(32) NOT NULL,
  order_status VARCHAR(32) NOT NULL,
  activation_status VARCHAR(32) NOT NULL DEFAULT 'WAITING_PAYMENT',
  paid_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_order_no (order_no),
  KEY idx_order_user_time (user_id, created_at),
  KEY idx_order_status (order_status, payment_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_order_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_no VARCHAR(96) NOT NULL,
  product_id BIGINT NOT NULL,
  product_no VARCHAR(64) NULL,
  product_name VARCHAR(128) NOT NULL,
  quantity INT NOT NULL DEFAULT 1,
  unit_price_usdt DECIMAL(18,6) NOT NULL,
  line_amount_usdt DECIMAL(18,6) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_order_item_order (order_no, sort_order),
  KEY idx_order_item_product (product_id, created_at),
  CONSTRAINT chk_order_item_positive CHECK (quantity > 0 AND unit_price_usdt > 0 AND line_amount_usdt > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_payment_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  payment_no VARCHAR(96) NOT NULL,
  order_no VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  provider VARCHAR(32) NOT NULL,
  provider_payment_id VARCHAR(128) NOT NULL,
  amount_usdt DECIMAL(18,6) NOT NULL,
  currency VARCHAR(16) NOT NULL DEFAULT 'USDT',
  payment_status VARCHAR(32) NOT NULL,
  checkout_url VARCHAR(1024) NULL,
  expires_at DATETIME NULL,
  callback_event_id VARCHAR(128) NULL,
  signature_status VARCHAR(32) NULL,
  raw_callback TEXT NULL,
  paid_at DATETIME NULL,
  failed_at DATETIME NULL,
  failure_reason VARCHAR(255) NULL,
  reconcile_attempts INT NOT NULL DEFAULT 0,
  last_reconcile_at DATETIME NULL,
  next_reconcile_at DATETIME NULL,
  last_reconcile_error VARCHAR(512) NULL,
  expired_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_payment_no (payment_no),
  UNIQUE KEY uk_payment_provider_id (provider, provider_payment_id),
  KEY idx_payment_order (order_no),
  KEY idx_payment_user_status (user_id, payment_status, created_at),
  KEY idx_payment_status_time (payment_status, created_at),
  KEY idx_payment_reconcile_due (payment_status, next_reconcile_at, created_at),
  CONSTRAINT chk_payment_record_positive_amount CHECK (amount_usdt > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_payment_callback_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  provider VARCHAR(32) NOT NULL,
  provider_event_id VARCHAR(128) NOT NULL,
  payment_no VARCHAR(96) NOT NULL,
  order_no VARCHAR(96) NOT NULL,
  event_status VARCHAR(32) NOT NULL,
  processing_status VARCHAR(32) NOT NULL,
  signature_status VARCHAR(32) NULL,
  raw_payload TEXT NULL,
  failure_reason VARCHAR(255) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_payment_callback_event (provider, provider_event_id),
  KEY idx_payment_callback_payment (payment_no, created_at),
  KEY idx_payment_callback_status (processing_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_tradein_application (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tradein_no VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  source_device_id BIGINT NOT NULL,
  source_instance_no VARCHAR(96) NULL,
  source_product_id BIGINT NOT NULL,
  source_product_name VARCHAR(128) NOT NULL,
  source_product_tier VARCHAR(32) NULL,
  target_product_id BIGINT NOT NULL,
  target_product_name VARCHAR(128) NOT NULL,
  target_product_tier VARCHAR(32) NULL,
  months_owned INT NOT NULL DEFAULT 0,
  current_efficiency DECIMAL(10,6) NOT NULL DEFAULT 1,
  source_price_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  target_price_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  salvage_value_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  tradein_discount_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  net_upgrade_cost_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL,
  review_note VARCHAR(512) NULL,
  reviewer VARCHAR(96) NULL,
  submitted_at DATETIME NOT NULL,
  reviewed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_tradein_no (tradein_no),
  KEY idx_tradein_user_time (user_id, created_at),
  KEY idx_tradein_status (status, created_at),
  KEY idx_tradein_source_device (source_device_id),
  CONSTRAINT chk_tradein_amounts CHECK (
    months_owned >= 0
    AND current_efficiency >= 0
    AND source_price_usdt >= 0
    AND target_price_usdt >= 0
    AND salvage_value_usdt >= 0
    AND tradein_discount_usdt >= 0
    AND net_upgrade_cost_usdt >= 0
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_tradein_rule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  source_product_no VARCHAR(64) NULL,
  source_tier VARCHAR(32) NULL,
  target_tier VARCHAR(32) NOT NULL,
  discount_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  salvage_rate DECIMAL(10,6) NOT NULL DEFAULT 0.30,
  min_holding_months INT NOT NULL DEFAULT 1,
  status TINYINT NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_tradein_rule_source_product (source_product_no, status),
  KEY idx_tradein_rule_source_tier (source_tier, status),
  CONSTRAINT chk_tradein_rule_amounts CHECK (
    discount_usdt >= 0
    AND salvage_rate >= 0
    AND salvage_rate <= 1
    AND min_holding_months >= 0
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_genesis_series (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  series_code VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  total_supply INT NOT NULL,
  sold_supply INT NOT NULL DEFAULT 0,
  price_usdt DECIMAL(18,6) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  sale_start_at DATETIME NULL,
  sale_end_at DATETIME NULL,
  royalty_bps INT NOT NULL DEFAULT 0,
  daily_dividend_rate_pct DECIMAL(10,6) NOT NULL DEFAULT 0,
  dividend_base_formula VARCHAR(255) NOT NULL DEFAULT '',
  cover_url VARCHAR(512) NULL,
  metadata_json VARCHAR(2048) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_genesis_series_code (series_code),
  KEY idx_genesis_series_status (status, created_at),
  CONSTRAINT chk_genesis_series_supply CHECK (total_supply > 0 AND sold_supply >= 0 AND sold_supply <= total_supply),
  CONSTRAINT chk_genesis_series_price CHECK (price_usdt > 0 AND royalty_bps >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_genesis_series' AND COLUMN_NAME = 'daily_dividend_rate_pct') = 0,
  'ALTER TABLE nx_genesis_series ADD COLUMN daily_dividend_rate_pct DECIMAL(10,6) NOT NULL DEFAULT 0 AFTER royalty_bps',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_genesis_series' AND COLUMN_NAME = 'dividend_base_formula') = 0,
  'ALTER TABLE nx_genesis_series ADD COLUMN dividend_base_formula VARCHAR(255) NOT NULL DEFAULT '''' AFTER daily_dividend_rate_pct',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_genesis_order (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_no VARCHAR(96) NOT NULL,
  client_request_no VARCHAR(96) NULL,
  user_id BIGINT NOT NULL,
  series_code VARCHAR(64) NOT NULL,
  quantity INT NOT NULL,
  unit_price_usdt DECIMAL(18,6) NOT NULL,
  amount_usdt DECIMAL(18,6) NOT NULL,
  payment_asset VARCHAR(16) NOT NULL DEFAULT 'USDT',
  status VARCHAR(32) NOT NULL,
  risk_decision_id BIGINT NULL,
  wallet_ledger_id BIGINT NULL,
  failure_reason VARCHAR(255) NULL,
  paid_at DATETIME NULL,
  completed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_genesis_order_no (order_no),
  UNIQUE KEY uk_genesis_order_client_request (user_id, client_request_no),
  KEY idx_genesis_order_user_time (user_id, created_at),
  KEY idx_genesis_order_status_time (status, created_at),
  CONSTRAINT chk_genesis_order_positive_amount CHECK (quantity > 0 AND unit_price_usdt > 0 AND amount_usdt > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_genesis_holding (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  holding_no VARCHAR(128) NOT NULL,
  user_id BIGINT NOT NULL,
  order_no VARCHAR(96) NOT NULL,
  series_code VARCHAR(64) NOT NULL,
  acquired_price_usdt DECIMAL(18,6) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  acquired_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_genesis_holding_no (holding_no),
  KEY idx_genesis_holding_user_time (user_id, created_at),
  KEY idx_genesis_holding_order (order_no),
  KEY idx_genesis_holding_series (series_code, status),
  CONSTRAINT chk_genesis_holding_positive_price CHECK (acquired_price_usdt > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_order' AND COLUMN_NAME = 'id') NOT LIKE '%auto_increment%',
  'ALTER TABLE nx_order MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_order' AND COLUMN_NAME = 'status') > 0,
  'ALTER TABLE nx_order MODIFY COLUMN status VARCHAR(32) NULL DEFAULT NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_order' AND COLUMN_NAME = 'pay_token') > 0,
  'ALTER TABLE nx_order MODIFY COLUMN pay_token VARCHAR(16) NULL DEFAULT NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_order' AND COLUMN_NAME = 'network') > 0,
  'ALTER TABLE nx_order MODIFY COLUMN network VARCHAR(32) NULL DEFAULT NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_order' AND COLUMN_NAME = 'product_id') = 0,
  'ALTER TABLE nx_order ADD COLUMN product_id BIGINT NULL AFTER order_no',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_order' AND COLUMN_NAME = 'quantity') = 0,
  'ALTER TABLE nx_order ADD COLUMN quantity INT NOT NULL DEFAULT 1 AFTER product_id',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_order' AND COLUMN_NAME = 'order_type') = 0,
  'ALTER TABLE nx_order ADD COLUMN order_type VARCHAR(32) NOT NULL DEFAULT ''SINGLE'' AFTER quantity',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_order' AND COLUMN_NAME = 'item_count') = 0,
  'ALTER TABLE nx_order ADD COLUMN item_count INT NOT NULL DEFAULT 1 AFTER order_type',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_order' AND COLUMN_NAME = 'subtotal_usdt') = 0,
  'ALTER TABLE nx_order ADD COLUMN subtotal_usdt DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER item_count',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_order' AND COLUMN_NAME = 'discount_usdt') = 0,
  'ALTER TABLE nx_order ADD COLUMN discount_usdt DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER subtotal_usdt',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_order' AND COLUMN_NAME = 'payment_no') = 0,
  'ALTER TABLE nx_order ADD COLUMN payment_no VARCHAR(96) NULL AFTER amount_usdt',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_order' AND COLUMN_NAME = 'payment_status') = 0,
  'ALTER TABLE nx_order ADD COLUMN payment_status VARCHAR(32) NOT NULL DEFAULT ''PENDING'' AFTER payment_no',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_order' AND COLUMN_NAME = 'order_status') = 0,
  'ALTER TABLE nx_order ADD COLUMN order_status VARCHAR(32) NOT NULL DEFAULT ''CREATED'' AFTER payment_status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_order' AND COLUMN_NAME = 'activation_status') = 0,
  'ALTER TABLE nx_order ADD COLUMN activation_status VARCHAR(32) NOT NULL DEFAULT ''WAITING_PAYMENT'' AFTER order_status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_order' AND COLUMN_NAME = 'paid_at') = 0,
  'ALTER TABLE nx_order ADD COLUMN paid_at DATETIME NULL AFTER activation_status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_order' AND COLUMN_NAME = 'deleted') > 0
    AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_order' AND COLUMN_NAME = 'is_deleted') = 0,
  'ALTER TABLE nx_order CHANGE COLUMN deleted is_deleted TINYINT NOT NULL DEFAULT 0',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_order' AND COLUMN_NAME = 'is_deleted') = 0,
  'ALTER TABLE nx_order ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_order' AND INDEX_NAME = 'idx_order_status') = 0,
  'ALTER TABLE nx_order ADD INDEX idx_order_status (order_status, payment_status)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_payment_record' AND INDEX_NAME = 'uk_payment_no') = 0,
  'ALTER TABLE nx_payment_record ADD UNIQUE KEY uk_payment_no (payment_no)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_payment_record' AND INDEX_NAME = 'uk_payment_provider_id') = 0,
  'ALTER TABLE nx_payment_record ADD UNIQUE KEY uk_payment_provider_id (provider, provider_payment_id)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_payment_record' AND INDEX_NAME = 'idx_payment_user_status') = 0,
  'ALTER TABLE nx_payment_record ADD INDEX idx_payment_user_status (user_id, payment_status, created_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_payment_record' AND COLUMN_NAME = 'reconcile_attempts') = 0,
  'ALTER TABLE nx_payment_record ADD COLUMN reconcile_attempts INT NOT NULL DEFAULT 0 AFTER failure_reason',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_payment_record' AND COLUMN_NAME = 'last_reconcile_at') = 0,
  'ALTER TABLE nx_payment_record ADD COLUMN last_reconcile_at DATETIME NULL AFTER reconcile_attempts',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_payment_record' AND COLUMN_NAME = 'next_reconcile_at') = 0,
  'ALTER TABLE nx_payment_record ADD COLUMN next_reconcile_at DATETIME NULL AFTER last_reconcile_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_payment_record' AND COLUMN_NAME = 'last_reconcile_error') = 0,
  'ALTER TABLE nx_payment_record ADD COLUMN last_reconcile_error VARCHAR(512) NULL AFTER next_reconcile_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_payment_record' AND COLUMN_NAME = 'expired_at') = 0,
  'ALTER TABLE nx_payment_record ADD COLUMN expired_at DATETIME NULL AFTER last_reconcile_error',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_payment_record' AND INDEX_NAME = 'idx_payment_reconcile_due') = 0,
  'ALTER TABLE nx_payment_record ADD INDEX idx_payment_reconcile_due (payment_status, next_reconcile_at, created_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_payment_record_positive_amount') = 0,
  'ALTER TABLE nx_payment_record ADD CONSTRAINT chk_payment_record_positive_amount CHECK (amount_usdt > 0)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_payment_callback_event' AND INDEX_NAME = 'uk_payment_callback_event') = 0,
  'ALTER TABLE nx_payment_callback_event ADD UNIQUE KEY uk_payment_callback_event (provider, provider_event_id)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_trade_in_order (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  trade_in_no VARCHAR(96) NOT NULL,
  source_device_id BIGINT NOT NULL,
  target_product_id BIGINT NOT NULL,
  valuation_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_trade_in_no (trade_in_no),
  KEY idx_trade_in_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_event_outbox (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id VARCHAR(64) NOT NULL,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id VARCHAR(128) NOT NULL,
  event_type VARCHAR(96) NOT NULL,
  payload JSON NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME NULL,
  published_at DATETIME NULL,
  last_error VARCHAR(512) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_event_outbox_event_id (event_id),
  KEY idx_event_outbox_status_next (status, next_retry_at, id),
  KEY idx_event_outbox_aggregate (aggregate_type, aggregate_id),
  KEY idx_event_outbox_type_time (event_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_event_consumer_delivery (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id VARCHAR(64) NOT NULL,
  consumer_group VARCHAR(128) NOT NULL,
  topic VARCHAR(128) NOT NULL,
  msg_id VARCHAR(128) NULL,
  event_type VARCHAR(96) NOT NULL DEFAULT 'UNKNOWN',
  aggregate_type VARCHAR(64) NULL,
  aggregate_id VARCHAR(128) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PROCESSING',
  attempt_count INT NOT NULL DEFAULT 0,
  rocketmq_reconsume_times INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME NULL,
  processed_at DATETIME NULL,
  dead_at DATETIME NULL,
  created_commissions INT NOT NULL DEFAULT 0,
  last_error VARCHAR(512) NULL,
  first_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_event_consumer_event_group (event_id, consumer_group),
  KEY idx_event_consumer_status_time (consumer_group, status, updated_at),
  KEY idx_event_consumer_topic_status (topic, status, updated_at),
  KEY idx_event_consumer_aggregate (aggregate_type, aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_admin_idempotency_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  scope VARCHAR(96) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PROCESSING',
  response_json JSON NULL,
  error_message VARCHAR(512) NULL,
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_idem_scope_key (scope, idempotency_key),
  KEY idx_admin_idem_expires (expires_at),
  KEY idx_admin_idem_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_audit_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  trace_id VARCHAR(128) NULL,
  service_name VARCHAR(96) NOT NULL,
  action VARCHAR(96) NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  resource_id VARCHAR(128) NULL,
  biz_no VARCHAR(128) NULL,
  user_id BIGINT NULL,
  actor_id BIGINT NULL,
  actor_type VARCHAR(32) NULL,
  actor_username VARCHAR(128) NULL,
  client_ip VARCHAR(64) NULL,
  method VARCHAR(16) NULL,
  path VARCHAR(255) NULL,
  result VARCHAR(32) NOT NULL DEFAULT 'SUCCESS',
  risk_level VARCHAR(32) NOT NULL DEFAULT 'INFO',
  detail_json JSON NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_audit_trace (trace_id, created_at),
  KEY idx_audit_action_time (action, created_at),
  KEY idx_audit_resource (resource_type, resource_id, created_at),
  KEY idx_audit_biz_no (biz_no, created_at),
  KEY idx_audit_user_time (user_id, created_at),
  KEY idx_audit_actor_time (actor_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_audit_operation_ticket (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  operation_id VARCHAR(64) NOT NULL,
  action VARCHAR(160) NOT NULL,
  object_text VARCHAR(255) NOT NULL,
  before_value VARCHAR(128) NOT NULL,
  after_value VARCHAR(128) NOT NULL,
  operator_name VARCHAR(128) NOT NULL,
  operator_role VARCHAR(32) NOT NULL,
  operation_type VARCHAR(32) NOT NULL,
  amplifies TINYINT NOT NULL DEFAULT 0,
  sos TINYINT NOT NULL DEFAULT 0,
  time_label VARCHAR(32) NOT NULL,
  mine TINYINT NOT NULL DEFAULT 0,
  role_gate VARCHAR(255) NOT NULL,
  reason VARCHAR(512) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'pending',
  decision_reason VARCHAR(512) NULL,
  decided_at DATETIME NULL,
  command_json TEXT NULL COMMENT '结构化回放指令 {domain,op,params}',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_audit_operation_ticket_no (operation_id),
  KEY idx_audit_operation_ticket_status (status, created_at),
  KEY idx_audit_operation_ticket_type (operation_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_audit_operation_history (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  operation_id VARCHAR(64) NOT NULL,
  action VARCHAR(160) NOT NULL,
  status VARCHAR(32) NOT NULL,
  chain_text VARCHAR(255) NOT NULL,
  time_label VARCHAR(32) NOT NULL,
  note VARCHAR(512) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_audit_operation_history_no (operation_id),
  KEY idx_audit_operation_history_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_audit_confirm_category (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  category_name VARCHAR(80) NOT NULL,
  examples VARCHAR(512) NOT NULL,
  role_gate VARCHAR(255) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_audit_confirm_category_name (category_name),
  KEY idx_audit_confirm_category_order (sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_maker_checker_task (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  action_type VARCHAR(64) NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  resource_id VARCHAR(128) NULL,
  title VARCHAR(160) NOT NULL,
  detail VARCHAR(512) NULL,
  payload_json JSON NULL,
  maker VARCHAR(128) NOT NULL,
  checker VARCHAR(128) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  reason VARCHAR(512) NULL,
  reviewed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_mc_status_time (status, created_at),
  KEY idx_mc_resource (resource_type, resource_id, created_at),
  KEY idx_mc_maker_time (maker, created_at),
  KEY idx_mc_checker_time (checker, reviewed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_user_device (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  source_order_no VARCHAR(96) NULL,
  product_id BIGINT NULL,
  product_code VARCHAR(64) NULL,
  product_tier VARCHAR(32) NULL,
  instance_no VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  device_type VARCHAR(32) NOT NULL,
  generation INT NOT NULL DEFAULT 1,
  gpu_model VARCHAR(128) NULL,
  vram_total_gb INT NULL,
  base_power_w DECIMAL(18,6) NOT NULL DEFAULT 0,
  dc_location VARCHAR(128) NULL,
  price_usdt_snapshot DECIMAL(18,6) NOT NULL DEFAULT 0,
  ownership_status VARCHAR(32) NOT NULL DEFAULT 'OWNED',
  source_channel VARCHAR(32) NOT NULL DEFAULT 'ORDER',
  status VARCHAR(32) NOT NULL,
  hashrate DECIMAL(18,6) NOT NULL DEFAULT 0,
  daily_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  daily_nex DECIMAL(18,6) NOT NULL DEFAULT 0,
  last_seen_at DATETIME NULL,
  purchased_at DATETIME NULL,
  activated_at DATETIME NULL,
  deactivated_at DATETIME NULL,
  pending_deactivate TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_device_instance_no (instance_no),
  KEY idx_user_device_user (user_id),
  KEY idx_user_device_product_code (product_code),
  KEY idx_user_device_ownership (ownership_status, created_at),
  KEY idx_user_device_order (source_order_no),
  KEY idx_user_device_status (status, last_seen_at),
  KEY idx_user_device_active (user_id, activated_at, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_trial_claim (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  claim_no VARCHAR(96) NOT NULL,
  client_request_no VARCHAR(96) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'CLAIMED',
  user_device_id BIGINT NULL,
  device_name VARCHAR(128) NOT NULL,
  duration_days INT NOT NULL DEFAULT 3,
  daily_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  daily_nex DECIMAL(18,6) NOT NULL DEFAULT 0,
  seats_left_today INT NOT NULL DEFAULT 0,
  offset_cap_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  price_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  claimed_at DATETIME NOT NULL,
  expires_at DATETIME NOT NULL,
  quota_snapshot VARCHAR(512) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_trial_claim_user_once (user_id),
  UNIQUE KEY uk_trial_claim_no (claim_no),
  KEY idx_trial_claim_user (user_id, status, created_at),
  KEY idx_trial_claim_device (user_device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_growth_trial_policy (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  policy_key VARCHAR(64) NOT NULL,
  policy_name VARCHAR(128) NOT NULL,
  description VARCHAR(512) NULL,
  current_value VARCHAR(128) NOT NULL DEFAULT '',
  value_type VARCHAR(32) NOT NULL DEFAULT 'STRING',
  hot TINYINT NOT NULL DEFAULT 0,
  section VARCHAR(32) NOT NULL DEFAULT 'live',
  server_only TINYINT NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 100,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_growth_trial_policy_key (policy_key),
  KEY idx_growth_trial_policy_order (is_deleted, sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_growth_trial_gate (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  gate_key VARCHAR(64) NOT NULL,
  gate_name VARCHAR(128) NOT NULL,
  note VARCHAR(512) NULL,
  sort_order INT NOT NULL DEFAULT 100,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_growth_trial_gate_key (gate_key),
  KEY idx_growth_trial_gate_order (status, sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_growth_checkin_rule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_key VARCHAR(64) NOT NULL,
  rule_name VARCHAR(128) NOT NULL,
  description VARCHAR(512) NULL,
  current_value VARCHAR(128) NOT NULL DEFAULT '',
  value_type VARCHAR(32) NOT NULL DEFAULT 'STRING',
  hot TINYINT NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 100,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_growth_checkin_rule_key (rule_key),
  KEY idx_growth_checkin_rule_order (is_deleted, sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_growth_wheel_tier (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tier_name VARCHAR(128) NOT NULL,
  reward_name VARCHAR(128) NOT NULL,
  probability_pct DECIMAL(8,4) NOT NULL DEFAULT 0,
  real_outflow TINYINT NOT NULL DEFAULT 0,
  reward_kind VARCHAR(64) NOT NULL DEFAULT '',
  sort_order INT NOT NULL DEFAULT 100,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_growth_wheel_tier_name (tier_name),
  KEY idx_growth_wheel_tier_order (status, sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_growth_wheel_guard (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  guard_key VARCHAR(64) NOT NULL,
  guard_label VARCHAR(128) NOT NULL,
  guard_value VARCHAR(255) NOT NULL DEFAULT '',
  note VARCHAR(512) NULL,
  sort_order INT NOT NULL DEFAULT 100,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_growth_wheel_guard_key (guard_key),
  KEY idx_growth_wheel_guard_order (status, sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_growth_promo_banner (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  banner_code VARCHAR(64) NOT NULL,
  base_reward VARCHAR(64) NULL,
  multiplier VARCHAR(32) NULL,
  countdown_days INT NULL,
  countdown_hours INT NULL,
  target_device VARCHAR(128) NULL,
  target_daily VARCHAR(64) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'active',
  sort_order INT NOT NULL DEFAULT 100,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_growth_promo_banner_code (banner_code),
  KEY idx_growth_promo_banner_status (status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND COLUMN_NAME = 'source_order_no') = 0,
  'ALTER TABLE nx_user_device ADD COLUMN source_order_no VARCHAR(96) NULL AFTER user_id',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND COLUMN_NAME = 'product_id') = 0,
  'ALTER TABLE nx_user_device ADD COLUMN product_id BIGINT NULL AFTER source_order_no',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND COLUMN_NAME = 'product_code') = 0,
  'ALTER TABLE nx_user_device ADD COLUMN product_code VARCHAR(64) NULL AFTER product_id',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND COLUMN_NAME = 'product_tier') = 0,
  'ALTER TABLE nx_user_device ADD COLUMN product_tier VARCHAR(32) NULL AFTER product_id',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND COLUMN_NAME = 'device_type') = 0,
  'ALTER TABLE nx_user_device ADD COLUMN device_type VARCHAR(32) NOT NULL DEFAULT ''MOBILE'' AFTER name',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND COLUMN_NAME = 'generation') = 0,
  'ALTER TABLE nx_user_device ADD COLUMN generation INT NOT NULL DEFAULT 1 AFTER device_type',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND COLUMN_NAME = 'gpu_model') = 0,
  'ALTER TABLE nx_user_device ADD COLUMN gpu_model VARCHAR(128) NULL AFTER generation',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND COLUMN_NAME = 'vram_total_gb') = 0,
  'ALTER TABLE nx_user_device ADD COLUMN vram_total_gb INT NULL AFTER gpu_model',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND COLUMN_NAME = 'base_power_w') = 0,
  'ALTER TABLE nx_user_device ADD COLUMN base_power_w DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER vram_total_gb',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND COLUMN_NAME = 'dc_location') = 0,
  'ALTER TABLE nx_user_device ADD COLUMN dc_location VARCHAR(128) NULL AFTER base_power_w',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND COLUMN_NAME = 'price_usdt_snapshot') = 0,
  'ALTER TABLE nx_user_device ADD COLUMN price_usdt_snapshot DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER dc_location',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND COLUMN_NAME = 'ownership_status') = 0,
  'ALTER TABLE nx_user_device ADD COLUMN ownership_status VARCHAR(32) NOT NULL DEFAULT ''OWNED'' AFTER price_usdt_snapshot',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND COLUMN_NAME = 'source_channel') = 0,
  'ALTER TABLE nx_user_device ADD COLUMN source_channel VARCHAR(32) NOT NULL DEFAULT ''ORDER'' AFTER ownership_status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND COLUMN_NAME = 'hashrate') = 0,
  'ALTER TABLE nx_user_device ADD COLUMN hashrate DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND COLUMN_NAME = 'purchased_at') = 0,
  'ALTER TABLE nx_user_device ADD COLUMN purchased_at DATETIME NULL AFTER last_seen_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND COLUMN_NAME = 'pending_deactivate') = 0,
  'ALTER TABLE nx_user_device ADD COLUMN pending_deactivate TINYINT NOT NULL DEFAULT 0 AFTER activated_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND COLUMN_NAME = 'deactivated_at') = 0,
  'ALTER TABLE nx_user_device ADD COLUMN deactivated_at DATETIME NULL AFTER activated_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND COLUMN_NAME = 'device_id') > 0,
  'ALTER TABLE nx_user_device MODIFY COLUMN device_id BIGINT NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND INDEX_NAME = 'idx_user_device_product_code') = 0,
  'ALTER TABLE nx_user_device ADD INDEX idx_user_device_product_code (product_code)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND INDEX_NAME = 'idx_user_device_ownership') = 0,
  'ALTER TABLE nx_user_device ADD INDEX idx_user_device_ownership (ownership_status, created_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_user_device_runtime (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_device_id BIGINT NOT NULL,
  online_status VARCHAR(32) NOT NULL,
  region VARCHAR(64) NULL,
  country VARCHAR(64) NULL,
  city VARCHAR(64) NULL,
  latitude DECIMAL(10,6) NULL,
  longitude DECIMAL(10,6) NULL,
  gpu_usage DECIMAL(10,6) NULL,
  gpu_temp_c DECIMAL(10,6) NULL,
  gpu_power_w DECIMAL(18,6) NULL,
  vram_used_gb DECIMAL(10,3) NULL,
  battery_level INT NULL,
  is_charging TINYINT NULL,
  network_reachable TINYINT NULL,
  thermal_state VARCHAR(32) NULL,
  paused_reason VARCHAR(64) NULL,
  active_task_no VARCHAR(96) NULL,
  client_name VARCHAR(96) NULL,
  heartbeat_at DATETIME NOT NULL,
  agent_version VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_device_runtime_device (user_device_id),
  KEY idx_device_runtime_status_time (online_status, heartbeat_at),
  KEY idx_device_runtime_geo (country, city),
  KEY idx_device_runtime_heartbeat (heartbeat_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_compute_datacenter (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  dc_location VARCHAR(128) NOT NULL,
  region_label VARCHAR(128) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'active',
  sort_order INT NOT NULL DEFAULT 100,
  updated_by VARCHAR(96) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_compute_datacenter_location (dc_location),
  KEY idx_compute_datacenter_status_sort (is_deleted, status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_compute_dc_ops_state (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  dc_location VARCHAR(128) NOT NULL,
  dispatch_paused TINYINT NOT NULL DEFAULT 0,
  paused_reason VARCHAR(160) NULL,
  paused_at DATETIME NULL,
  resumed_at DATETIME NULL,
  updated_by VARCHAR(96) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_compute_dc_ops_state_dc (dc_location),
  KEY idx_compute_dc_ops_paused (dispatch_paused, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_device_lifecycle_rule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  scope_type VARCHAR(32) NOT NULL,
  scope_value VARCHAR(64) NULL,
  start_month INT NOT NULL DEFAULT 0,
  end_month INT NULL,
  monthly_decay_rate DECIMAL(10,6) NOT NULL DEFAULT 0,
  floor_efficiency DECIMAL(10,6) NOT NULL DEFAULT 0.22,
  exempt TINYINT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_device_lifecycle_scope (scope_type, scope_value, status),
  KEY idx_device_lifecycle_status (status, sort_order),
  CONSTRAINT chk_device_lifecycle_month CHECK (start_month >= 0 AND (end_month IS NULL OR end_month >= start_month)),
  CONSTRAINT chk_device_lifecycle_rates CHECK (
    monthly_decay_rate >= 0 AND monthly_decay_rate <= 1
    AND floor_efficiency >= 0 AND floor_efficiency <= 1
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_compute_e3_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  config_key VARCHAR(96) NOT NULL,
  config_value VARCHAR(255) NOT NULL,
  value_type VARCHAR(16) NOT NULL DEFAULT 'NUMBER',
  updated_by VARCHAR(96) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_compute_e3_config_key (config_key),
  KEY idx_compute_e3_config_sort (sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND INDEX_NAME = 'idx_user_device_status') = 0,
  'ALTER TABLE nx_user_device ADD INDEX idx_user_device_status (status, last_seen_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND INDEX_NAME = 'idx_user_device_active') = 0,
  'ALTER TABLE nx_user_device ADD INDEX idx_user_device_active (user_id, activated_at, status)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND INDEX_NAME = 'idx_user_device_order') = 0,
  'ALTER TABLE nx_user_device ADD INDEX idx_user_device_order (source_order_no)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_compute_task (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_no VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  user_device_id BIGINT NOT NULL,
  task_type VARCHAR(64) NOT NULL,
  client_name VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  started_at DATETIME NULL,
  worker_ack_at DATETIME NULL,
  lease_expires_at DATETIME NULL,
  attempt_count INT NOT NULL DEFAULT 1,
  max_attempts INT NOT NULL DEFAULT 3,
  next_retry_at DATETIME NULL,
  last_error VARCHAR(512) NULL,
  completed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_compute_task_no (task_no),
  KEY idx_compute_task_device_time (user_device_id, created_at),
  KEY idx_compute_task_user_status (user_id, status, created_at),
  KEY idx_compute_task_lease (status, lease_expires_at, created_at),
  KEY idx_compute_task_retry (status, next_retry_at, created_at),
  KEY idx_compute_task_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_task' AND COLUMN_NAME = 'worker_ack_at') = 0,
  'ALTER TABLE nx_compute_task ADD COLUMN worker_ack_at DATETIME NULL AFTER started_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_task' AND COLUMN_NAME = 'lease_expires_at') = 0,
  'ALTER TABLE nx_compute_task ADD COLUMN lease_expires_at DATETIME NULL AFTER worker_ack_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_task' AND COLUMN_NAME = 'attempt_count') = 0,
  'ALTER TABLE nx_compute_task ADD COLUMN attempt_count INT NOT NULL DEFAULT 1 AFTER lease_expires_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_task' AND COLUMN_NAME = 'max_attempts') = 0,
  'ALTER TABLE nx_compute_task ADD COLUMN max_attempts INT NOT NULL DEFAULT 3 AFTER attempt_count',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_task' AND COLUMN_NAME = 'next_retry_at') = 0,
  'ALTER TABLE nx_compute_task ADD COLUMN next_retry_at DATETIME NULL AFTER max_attempts',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_task' AND COLUMN_NAME = 'last_error') = 0,
  'ALTER TABLE nx_compute_task ADD COLUMN last_error VARCHAR(512) NULL AFTER next_retry_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_task' AND INDEX_NAME = 'idx_compute_task_user_status') = 0,
  'ALTER TABLE nx_compute_task ADD INDEX idx_compute_task_user_status (user_id, status, created_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_task' AND INDEX_NAME = 'idx_compute_task_lease') = 0,
  'ALTER TABLE nx_compute_task ADD INDEX idx_compute_task_lease (status, lease_expires_at, created_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_task' AND INDEX_NAME = 'idx_compute_task_retry') = 0,
  'ALTER TABLE nx_compute_task ADD INDEX idx_compute_task_retry (status, next_retry_at, created_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_compute_receipt (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  user_device_id BIGINT NULL,
  task_no VARCHAR(96) NULL,
  receipt_no VARCHAR(96) NOT NULL,
  task_type VARCHAR(64) NOT NULL,
  client_name VARCHAR(128) NOT NULL,
  reward_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  reward_nex DECIMAL(18,6) NOT NULL DEFAULT 0,
  earning_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  proof_hash VARCHAR(128) NOT NULL,
  completed_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_receipt_no (receipt_no),
  UNIQUE KEY uk_receipt_task_no (task_no),
  KEY idx_receipt_user_time (user_id, completed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_receipt' AND COLUMN_NAME = 'id') NOT LIKE '%auto_increment%',
  'ALTER TABLE nx_compute_receipt MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_receipt' AND COLUMN_NAME = 'task_no') = 0,
  'ALTER TABLE nx_compute_receipt ADD COLUMN task_no VARCHAR(96) NULL AFTER user_device_id',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_receipt' AND COLUMN_NAME = 'earning_status') = 0,
  'ALTER TABLE nx_compute_receipt ADD COLUMN earning_status VARCHAR(32) NOT NULL DEFAULT ''PENDING'' AFTER reward_nex',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_compute_receipt' AND INDEX_NAME = 'uk_receipt_task_no') = 0,
  'ALTER TABLE nx_compute_receipt ADD UNIQUE KEY uk_receipt_task_no (task_no)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_earning_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_no VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  user_device_id BIGINT NULL,
  receipt_no VARCHAR(96) NULL,
  asset VARCHAR(16) NOT NULL,
  amount DECIMAL(18,6) NOT NULL,
  status VARCHAR(32) NOT NULL,
  wallet_posted_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_earning_event_no (event_no),
  UNIQUE KEY uk_earning_receipt_asset (receipt_no, asset),
  KEY idx_earning_user_time (user_id, created_at),
  KEY idx_earning_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_earning_event' AND INDEX_NAME = 'uk_earning_receipt_asset') = 0,
  'ALTER TABLE nx_earning_event ADD UNIQUE KEY uk_earning_receipt_asset (receipt_no, asset)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_earning_summary (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  summary_date DATE NOT NULL,
  usdt_amount DECIMAL(18,6) NOT NULL DEFAULT 0,
  nex_amount DECIMAL(18,6) NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_earning_summary_user_date (user_id, summary_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_earning_milestone_rule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  milestone_id VARCHAR(64) NOT NULL,
  label VARCHAR(128) NOT NULL,
  threshold_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  reward_nex DECIMAL(18,6) NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 100,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_earning_milestone_rule_id (milestone_id),
  KEY idx_earning_milestone_rule_status (status, threshold_usdt, sort_order),
  CONSTRAINT chk_earning_milestone_rule_amount CHECK (threshold_usdt >= 0 AND reward_nex >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_earning_milestone (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  milestone_id VARCHAR(64) NOT NULL,
  threshold_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  reward_nex DECIMAL(18,6) NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL,
  event_no VARCHAR(96) NULL,
  achieved_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_earning_milestone_user (user_id, milestone_id),
  KEY idx_earning_milestone_status (status, achieved_at),
  CONSTRAINT chk_earning_milestone_amount CHECK (threshold_usdt >= 0 AND reward_nex >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_earning_goal (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  target_usdt DECIMAL(18,6) NOT NULL,
  deadline_at DATETIME NOT NULL,
  achieved TINYINT NOT NULL DEFAULT 0,
  achieved_at DATETIME NULL,
  deleted_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_earning_goal_user (user_id, is_deleted, achieved, deadline_at),
  KEY idx_earning_goal_deadline (deadline_at, achieved),
  CONSTRAINT chk_earning_goal_target CHECK (target_usdt >= 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_earning_milestone' AND INDEX_NAME = 'uk_earning_milestone_user') = 0,
  'ALTER TABLE nx_earning_milestone ADD UNIQUE KEY uk_earning_milestone_user (user_id, milestone_id)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_team_member (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  member_user_id BIGINT NOT NULL,
  member_no VARCHAR(64) NOT NULL,
  nickname VARCHAR(64) NOT NULL,
  v_rank VARCHAR(16) NOT NULL DEFAULT 'V0',
  level INT NOT NULL,
  volume DECIMAL(18,2) NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_team_user_member (user_id, member_user_id),
  KEY idx_team_user_level (user_id, level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_team_member' AND INDEX_NAME = 'idx_team_user_rank') = 0,
  'ALTER TABLE nx_team_member ADD INDEX idx_team_user_rank (user_id, v_rank)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_team_ambassador_application (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  applicant_name VARCHAR(64) NOT NULL,
  region VARCHAR(64) NOT NULL,
  city VARCHAR(64) NULL,
  event_date DATE NULL,
  contact_method VARCHAR(128) NULL,
  application_reason VARCHAR(255) NULL,
  event_plan TEXT NULL,
  expected_attendees INT NOT NULL DEFAULT 0,
  current_rank VARCHAR(16) NOT NULL DEFAULT 'V0',
  requested_budget_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  kol_budget_pct DECIMAL(8,4) NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  reviewer VARCHAR(64) NULL,
  review_reason VARCHAR(255) NULL,
  reviewed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_ambassador_status_time (status, created_at),
  KEY idx_ambassador_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_team_ambassador_application' AND COLUMN_NAME = 'city') = 0,
  'ALTER TABLE nx_team_ambassador_application ADD COLUMN city VARCHAR(64) NULL AFTER region',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_team_ambassador_application' AND COLUMN_NAME = 'event_date') = 0,
  'ALTER TABLE nx_team_ambassador_application ADD COLUMN event_date DATE NULL AFTER city',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_team_ambassador_application' AND COLUMN_NAME = 'contact_method') = 0,
  'ALTER TABLE nx_team_ambassador_application ADD COLUMN contact_method VARCHAR(128) NULL AFTER event_date',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_team_ambassador_application' AND COLUMN_NAME = 'application_reason') = 0,
  'ALTER TABLE nx_team_ambassador_application ADD COLUMN application_reason VARCHAR(255) NULL AFTER contact_method',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_team_ambassador_application' AND COLUMN_NAME = 'event_plan') = 0,
  'ALTER TABLE nx_team_ambassador_application ADD COLUMN event_plan TEXT NULL AFTER application_reason',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_team_ambassador_application' AND COLUMN_NAME = 'expected_attendees') = 0,
  'ALTER TABLE nx_team_ambassador_application ADD COLUMN expected_attendees INT NOT NULL DEFAULT 0 AFTER event_plan',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_team_hardware_quota_tier (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  quota_code VARCHAR(32) NOT NULL,
  product_no VARCHAR(64) NOT NULL,
  display_name VARCHAR(128) NULL,
  note VARCHAR(255) NULL,
  direct_refs INT NOT NULL DEFAULT 0,
  month_volume_usd DECIMAL(18,6) NOT NULL DEFAULT 0,
  monthly_quota INT NOT NULL DEFAULT 0,
  unlock_mode VARCHAR(16) NOT NULL DEFAULT 'ALL',
  status TINYINT NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_team_hardware_quota_code (quota_code),
  KEY idx_team_hardware_quota_product (product_no, status),
  KEY idx_team_hardware_quota_sort (status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_team_hardware_quota_usage (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  quota_tier_id BIGINT NOT NULL,
  quota_code VARCHAR(32) NOT NULL,
  product_no VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  order_no VARCHAR(64) NULL,
  usage_type VARCHAR(32) NOT NULL DEFAULT 'RESERVED',
  quantity INT NOT NULL DEFAULT 1,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  remark VARCHAR(255) NULL,
  occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_team_hardware_quota_usage_tier (quota_tier_id, status, occurred_at),
  KEY idx_team_hardware_quota_usage_user (user_id, occurred_at),
  KEY idx_team_hardware_quota_usage_order (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_team_leaderboard_action (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  period VARCHAR(32) NOT NULL DEFAULT 'week',
  user_id BIGINT NOT NULL,
  member_user_id BIGINT NOT NULL,
  member_no VARCHAR(64) NULL,
  nickname VARCHAR(64) NULL,
  action_type VARCHAR(32) NOT NULL,
  reason VARCHAR(255) NULL,
  operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_leaderboard_action_member (period, user_id, member_user_id, action_type),
  KEY idx_leaderboard_action_period (period, action_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_user_level_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  level_code VARCHAR(16) NOT NULL,
  level_name VARCHAR(64) NOT NULL,
  entry_condition VARCHAR(255) NOT NULL,
  core_goal VARCHAR(255) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_level_code (level_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_v_rank_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  rank_code VARCHAR(16) NOT NULL,
  title_en VARCHAR(64) NOT NULL,
  title_cn VARCHAR(64) NOT NULL,
  self_buy_usd DECIMAL(18,2) NOT NULL DEFAULT 0,
  direct_refs INT NOT NULL DEFAULT 0,
  team_volume_usd DECIMAL(18,2) NOT NULL DEFAULT 0,
  required_downline_rank VARCHAR(16) NULL,
  required_downline_count INT NOT NULL DEFAULT 0,
  downline_requirement VARCHAR(255) NULL,
  unilevel_depth VARCHAR(32) NULL,
  peer_bonus_rate DECIMAL(8,4) NOT NULL DEFAULT 0,
  leadership_votes INT NOT NULL DEFAULT 0,
  physical_reward VARCHAR(128) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_v_rank_code (rank_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_v_rank_reward_rule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  reward_id VARCHAR(64) NOT NULL,
  rank_code VARCHAR(16) NOT NULL,
  reward_type VARCHAR(32) NOT NULL,
  amount DECIMAL(18,6) NULL,
  voucher_id VARCHAR(64) NULL,
  sku_id VARCHAR(64) NULL,
  custom_label VARCHAR(255) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_v_rank_reward_rule_id (reward_id),
  KEY idx_v_rank_reward_rule_rank (rank_code, status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_v_rank_reward_fulfillment (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  rank_code VARCHAR(16) NOT NULL,
  reward_name VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  logistics_provider VARCHAR(64) NULL,
  tracking_no VARCHAR(96) NULL,
  encrypted_address_ref VARCHAR(128) NULL,
  reason VARCHAR(255) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  fulfilled_at DATETIME NULL,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_v_rank_fulfillment_status (status, created_at),
  KEY idx_v_rank_fulfillment_user (user_id, rank_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_user_level_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  level_type VARCHAR(16) NOT NULL,
  from_code VARCHAR(16) NULL,
  to_code VARCHAR(16) NOT NULL,
  reason VARCHAR(255) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_user_level_log_user (user_id, level_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_level_log' AND INDEX_NAME = 'idx_user_level_log_type_time') = 0,
  'ALTER TABLE nx_user_level_log ADD INDEX idx_user_level_log_type_time (level_type, created_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_commission_rule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  commission_type VARCHAR(32) NOT NULL,
  layer_no INT NULL,
  rank_code VARCHAR(16) NULL,
  usdt_rate DECIMAL(10,6) NOT NULL DEFAULT 0,
  nex_per_usd DECIMAL(18,6) NOT NULL DEFAULT 0,
  fixed_nex DECIMAL(18,6) NOT NULL DEFAULT 0,
  daily_cap_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  cooldown_days INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_commission_rule_layer (commission_type, layer_no),
  KEY idx_commission_rule_rank (commission_type, rank_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_team_f2_admin_config (
  config_key VARCHAR(64) PRIMARY KEY,
  config_value VARCHAR(255) NOT NULL,
  value_type VARCHAR(16) NOT NULL DEFAULT 'STRING',
  description VARCHAR(255) NULL,
  updated_reason VARCHAR(255) NULL,
  updated_by VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_team_f4_admin_config (
  config_key VARCHAR(64) PRIMARY KEY,
  config_value VARCHAR(255) NOT NULL,
  value_type VARCHAR(16) NOT NULL DEFAULT 'STRING',
  description VARCHAR(255) NULL,
  updated_reason VARCHAR(255) NULL,
  updated_by VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_commission_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  commission_type VARCHAR(32) NOT NULL,
  source_user_id BIGINT NULL,
  source_user_name VARCHAR(64) NULL,
  layer_no INT NULL,
  order_no VARCHAR(64) NULL,
  order_amount_usd DECIMAL(18,6) NULL,
  amount_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  amount_nex DECIMAL(18,6) NOT NULL DEFAULT 0,
  currency VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  unlock_at DATETIME NULL,
  remark VARCHAR(255) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_commission_user_time (user_id, created_at),
  KEY idx_commission_status (status, unlock_at),
  KEY idx_commission_order (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_commission_event' AND INDEX_NAME = 'uk_commission_order_user_layer') = 0,
  'ALTER TABLE nx_commission_event ADD UNIQUE KEY uk_commission_order_user_layer (commission_type, order_no, user_id, layer_no)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_binary_commission_settlement (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  settlement_date DATE NOT NULL,
  left_user_id BIGINT NOT NULL,
  right_user_id BIGINT NOT NULL,
  left_volume DECIMAL(18,6) NOT NULL DEFAULT 0,
  right_volume DECIMAL(18,6) NOT NULL DEFAULT 0,
  matched_volume DECIMAL(18,6) NOT NULL DEFAULT 0,
  amount_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  daily_cap_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  commission_event_id BIGINT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_binary_settlement_user_date (user_id, settlement_date),
  KEY idx_binary_settlement_date (settlement_date, status),
  KEY idx_binary_settlement_event (commission_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_mission (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  mission_code VARCHAR(64) NOT NULL,
  mission_name VARCHAR(128) NOT NULL,
  mission_type VARCHAR(32) NOT NULL,
  reward_points INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_mission_code (mission_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_ops_compute_task_catalog (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_code VARCHAR(64) NOT NULL,
  task_name VARCHAR(128) NOT NULL,
  task_kind VARCHAR(16) NOT NULL,
  unit_price_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  billing_unit VARCHAR(16) NOT NULL DEFAULT '/job',
  requirement_label VARCHAR(96) NOT NULL,
  saturation_pct DECIMAL(6,2) NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_ops_compute_task_code (task_code),
  KEY idx_ops_compute_task_status (status, sort_order),
  CONSTRAINT chk_ops_compute_task_price CHECK (unit_price_usdt >= 0),
  CONSTRAINT chk_ops_compute_task_saturation CHECK (saturation_pct >= 0 AND saturation_pct <= 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_user_mission (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  mission_id BIGINT NOT NULL,
  mission_status VARCHAR(32) NOT NULL,
  completed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_mission (user_id, mission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_daily_check_in (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  mission_id BIGINT NOT NULL,
  check_in_date DATE NOT NULL,
  base_points INT NOT NULL DEFAULT 0,
  reward_multiplier DECIMAL(4,2) NOT NULL DEFAULT 1.00,
  bonus_points INT NOT NULL DEFAULT 0,
  streak_bonus_points INT NOT NULL DEFAULT 0,
  reward_points INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_daily_check_in_user_date (user_id, check_in_date),
  KEY idx_daily_check_in_date (check_in_date, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_daily_check_in' AND COLUMN_NAME = 'base_points') = 0,
  'ALTER TABLE nx_daily_check_in ADD COLUMN base_points INT NOT NULL DEFAULT 0 AFTER check_in_date',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_daily_check_in' AND COLUMN_NAME = 'reward_multiplier') = 0,
  'ALTER TABLE nx_daily_check_in ADD COLUMN reward_multiplier DECIMAL(4,2) NOT NULL DEFAULT 1.00 AFTER base_points',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_daily_check_in' AND COLUMN_NAME = 'bonus_points') = 0,
  'ALTER TABLE nx_daily_check_in ADD COLUMN bonus_points INT NOT NULL DEFAULT 0 AFTER reward_multiplier',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_daily_check_in' AND COLUMN_NAME = 'streak_bonus_points') = 0,
  'ALTER TABLE nx_daily_check_in ADD COLUMN streak_bonus_points INT NOT NULL DEFAULT 0 AFTER bonus_points',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_user_streak (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  current_streak INT NOT NULL DEFAULT 0,
  longest_streak INT NOT NULL DEFAULT 0,
  streak_savers INT NOT NULL DEFAULT 1,
  last_check_in_date DATE NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_streak_user (user_id),
  KEY idx_user_streak_last_check_in (last_check_in_date),
  KEY idx_user_streak_rank (is_deleted, last_check_in_date, current_streak, longest_streak, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_streak' AND INDEX_NAME = 'idx_user_streak_rank') = 0,
  'ALTER TABLE nx_user_streak ADD KEY idx_user_streak_rank (is_deleted, last_check_in_date, current_streak, longest_streak, id)',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_achievement (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  achievement_code VARCHAR(64) NOT NULL,
  achievement_name VARCHAR(128) NOT NULL,
  description VARCHAR(512) NULL,
  category VARCHAR(32) NOT NULL,
  icon_key VARCHAR(64) NULL,
  accent_color VARCHAR(32) NULL,
  trigger_type VARCHAR(32) NOT NULL,
  trigger_value INT NOT NULL DEFAULT 0,
  reward_points INT NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_achievement_code (achievement_code),
  KEY idx_achievement_display (category, sort_order),
  KEY idx_achievement_trigger (trigger_type, trigger_value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_achievement' AND COLUMN_NAME = 'description') = 0,
  'ALTER TABLE nx_achievement ADD COLUMN description VARCHAR(512) NULL AFTER achievement_name',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_achievement' AND COLUMN_NAME = 'icon_key') = 0,
  'ALTER TABLE nx_achievement ADD COLUMN icon_key VARCHAR(64) NULL AFTER category',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_achievement' AND COLUMN_NAME = 'accent_color') = 0,
  'ALTER TABLE nx_achievement ADD COLUMN accent_color VARCHAR(32) NULL AFTER icon_key',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_achievement' AND COLUMN_NAME = 'sort_order') = 0,
  'ALTER TABLE nx_achievement ADD COLUMN sort_order INT NOT NULL DEFAULT 0 AFTER reward_points',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_achievement' AND INDEX_NAME = 'idx_achievement_display') = 0,
  'ALTER TABLE nx_achievement ADD KEY idx_achievement_display (category, sort_order)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_user_achievement (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  achievement_id BIGINT NOT NULL,
  achievement_code VARCHAR(64) NOT NULL,
  achievement_status VARCHAR(32) NOT NULL,
  unlocked_at DATETIME NULL,
  claimed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_achievement (user_id, achievement_code),
  KEY idx_user_achievement_status (achievement_status, unlocked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_streak_power_up (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  power_up_code VARCHAR(64) NOT NULL,
  power_up_name VARCHAR(128) NOT NULL,
  i18n_key VARCHAR(96) NOT NULL,
  target_path VARCHAR(255) NOT NULL,
  badge_achievement_code VARCHAR(64) NOT NULL,
  unlock_streak_days INT NOT NULL,
  effect_type VARCHAR(32) NOT NULL,
  effect_value VARCHAR(128) NULL,
  duration_days INT NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_streak_power_up_code (power_up_code),
  KEY idx_streak_power_up_threshold (unlock_streak_days, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_user_streak_power_up (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  power_up_id BIGINT NOT NULL,
  power_up_code VARCHAR(64) NOT NULL,
  power_up_status VARCHAR(32) NOT NULL,
  unlocked_at DATETIME NULL,
  activated_at DATETIME NULL,
  expires_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_streak_power_up (user_id, power_up_code),
  KEY idx_user_streak_power_up_status (power_up_status, activated_at),
  KEY idx_user_streak_power_up_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_streak_milestone (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  milestone_day INT NOT NULL,
  milestone_name VARCHAR(128) NOT NULL,
  reward_type VARCHAR(32) NOT NULL,
  reward_amount DECIMAL(18,6) NOT NULL DEFAULT 0,
  reward_name VARCHAR(128) NOT NULL,
  badge_achievement_code VARCHAR(64) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_streak_milestone_day (milestone_day),
  KEY idx_streak_milestone_status (status, sort_order, milestone_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_user_streak_milestone (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  milestone_id BIGINT NOT NULL,
  milestone_day INT NOT NULL,
  reward_type VARCHAR(32) NOT NULL,
  reward_amount DECIMAL(18,6) NOT NULL DEFAULT 0,
  claim_status VARCHAR(32) NOT NULL,
  claimed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_streak_milestone (user_id, milestone_day),
  KEY idx_user_streak_milestone_status (claim_status, claimed_at),
  KEY idx_user_streak_milestone_day (milestone_day, claimed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_monthly_challenge (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  challenge_code VARCHAR(64) NOT NULL,
  challenge_name VARCHAR(128) NOT NULL,
  description VARCHAR(512) NULL,
  theme VARCHAR(64) NULL,
  months_from INT NOT NULL DEFAULT 0,
  months_to INT NOT NULL DEFAULT 999,
  target_type VARCHAR(64) NOT NULL,
  target_value INT NOT NULL DEFAULT 1,
  reward_type VARCHAR(32) NOT NULL,
  reward_amount DECIMAL(18,6) NOT NULL DEFAULT 0,
  reward_name VARCHAR(128) NOT NULL,
  badge_achievement_code VARCHAR(64) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_monthly_challenge_code (challenge_code),
  KEY idx_monthly_challenge_status (status, sort_order, id),
  KEY idx_monthly_challenge_months (months_from, months_to, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_user_monthly_challenge (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  challenge_id BIGINT NOT NULL,
  challenge_code VARCHAR(64) NOT NULL,
  progress_value INT NOT NULL DEFAULT 0,
  claim_status VARCHAR(32) NOT NULL,
  reward_type VARCHAR(32) NOT NULL,
  reward_amount DECIMAL(18,6) NOT NULL DEFAULT 0,
  claimed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_monthly_challenge (user_id, challenge_code),
  KEY idx_user_monthly_status (claim_status, updated_at),
  KEY idx_user_monthly_challenge_id (challenge_id, claim_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_event_quest (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  quest_code VARCHAR(64) NOT NULL,
  quest_name VARCHAR(128) NOT NULL,
  description VARCHAR(512) NULL,
  geo_scope VARCHAR(64) NOT NULL DEFAULT '',
  starts_at DATETIME NULL,
  ends_at DATETIME NULL,
  target_type VARCHAR(64) NOT NULL,
  target_value INT NOT NULL DEFAULT 1,
  reward_type VARCHAR(32) NOT NULL,
  reward_amount DECIMAL(18,6) NOT NULL DEFAULT 0,
  reward_name VARCHAR(128) NOT NULL,
  badge_achievement_code VARCHAR(64) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_event_quest_code (quest_code),
  KEY idx_event_quest_status (status, sort_order, id),
  KEY idx_event_quest_window (starts_at, ends_at, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_user_event_quest (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  quest_id BIGINT NOT NULL,
  quest_code VARCHAR(64) NOT NULL,
  progress_value INT NOT NULL DEFAULT 0,
  claim_status VARCHAR(32) NOT NULL,
  reward_type VARCHAR(32) NOT NULL,
  reward_amount DECIMAL(18,6) NOT NULL DEFAULT 0,
  claimed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_event_quest (user_id, quest_code),
  KEY idx_user_event_status (claim_status, updated_at),
  KEY idx_user_event_quest_id (quest_id, claim_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_points_ledger (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  biz_no VARCHAR(96) NOT NULL,
  biz_type VARCHAR(64) NOT NULL,
  points INT NOT NULL,
  balance_after INT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_points_ledger_biz (biz_no),
  KEY idx_points_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_notification (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  biz_no VARCHAR(128) NULL,
  user_id BIGINT NOT NULL,
  type VARCHAR(32) NOT NULL,
  priority VARCHAR(16) NOT NULL DEFAULT 'normal',
  title VARCHAR(128) NOT NULL,
  body VARCHAR(512) NOT NULL,
  cta_label VARCHAR(64) NULL,
  cta_href VARCHAR(255) NULL,
  read_flag TINYINT NOT NULL DEFAULT 0,
  read_at DATETIME NULL,
  push_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  push_attempts INT NOT NULL DEFAULT 0,
  next_push_at DATETIME NULL,
  last_push_error VARCHAR(512) NULL,
  pushed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_notification_biz_user (biz_no, user_id),
  KEY idx_notification_user_time (user_id, created_at),
  KEY idx_notification_push (push_status, created_at),
  KEY idx_notification_push_due (push_status, next_push_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification' AND COLUMN_NAME = 'id' AND EXTRA LIKE '%auto_increment%') = 0,
  'ALTER TABLE nx_notification MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification' AND COLUMN_NAME = 'biz_no') = 0,
  'ALTER TABLE nx_notification ADD COLUMN biz_no VARCHAR(128) NULL AFTER id',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification' AND COLUMN_NAME = 'priority') = 0,
  'ALTER TABLE nx_notification ADD COLUMN priority VARCHAR(16) NOT NULL DEFAULT ''normal'' AFTER type',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification' AND COLUMN_NAME = 'cta_label') = 0,
  'ALTER TABLE nx_notification ADD COLUMN cta_label VARCHAR(64) NULL AFTER body',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification' AND COLUMN_NAME = 'cta_href') = 0,
  'ALTER TABLE nx_notification ADD COLUMN cta_href VARCHAR(255) NULL AFTER cta_label',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification' AND COLUMN_NAME = 'read_at') = 0,
  'ALTER TABLE nx_notification ADD COLUMN read_at DATETIME NULL AFTER read_flag',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification' AND COLUMN_NAME = 'push_status') = 0,
  'ALTER TABLE nx_notification ADD COLUMN push_status VARCHAR(32) NOT NULL DEFAULT ''PENDING'' AFTER read_flag',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification' AND COLUMN_NAME = 'push_attempts') = 0,
  'ALTER TABLE nx_notification ADD COLUMN push_attempts INT NOT NULL DEFAULT 0 AFTER push_status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification' AND COLUMN_NAME = 'next_push_at') = 0,
  'ALTER TABLE nx_notification ADD COLUMN next_push_at DATETIME NULL AFTER push_attempts',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification' AND COLUMN_NAME = 'last_push_error') = 0,
  'ALTER TABLE nx_notification ADD COLUMN last_push_error VARCHAR(512) NULL AFTER next_push_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification' AND COLUMN_NAME = 'pushed_at') = 0,
  'ALTER TABLE nx_notification ADD COLUMN pushed_at DATETIME NULL AFTER last_push_error',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification' AND INDEX_NAME = 'uk_notification_biz') > 0,
  'ALTER TABLE nx_notification DROP INDEX uk_notification_biz',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification' AND INDEX_NAME = 'uk_notification_biz_user') = 0,
  'ALTER TABLE nx_notification ADD UNIQUE KEY uk_notification_biz_user (biz_no, user_id)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification' AND INDEX_NAME = 'idx_notification_push') = 0,
  'ALTER TABLE nx_notification ADD INDEX idx_notification_push (push_status, created_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification' AND INDEX_NAME = 'idx_notification_push_due') = 0,
  'ALTER TABLE nx_notification ADD INDEX idx_notification_push_due (push_status, next_push_at, created_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_kyc_profile (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  kyc_no VARCHAR(96) NOT NULL,
  status VARCHAR(32) NOT NULL,
  country VARCHAR(64) NULL,
  applicant_name VARCHAR(128) NULL,
  document_type VARCHAR(64) NULL,
  document_last4 VARCHAR(16) NULL,
  document_object_key VARCHAR(255) NULL,
  submitted_at DATETIME NULL,
  reviewed_by VARCHAR(64) NULL,
  reviewed_at DATETIME NULL,
  reject_reason VARCHAR(255) NULL,
  expires_at DATETIME NULL,
  risk_notes VARCHAR(512) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_kyc_user (user_id),
  UNIQUE KEY uk_kyc_no (kyc_no),
  KEY idx_kyc_status (status, created_at),
  KEY idx_kyc_expiry (status, expires_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_kyc_profile' AND COLUMN_NAME = 'applicant_name') = 0,
  'ALTER TABLE nx_kyc_profile ADD COLUMN applicant_name VARCHAR(128) NULL AFTER country',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_kyc_profile' AND COLUMN_NAME = 'document_type') = 0,
  'ALTER TABLE nx_kyc_profile ADD COLUMN document_type VARCHAR(64) NULL AFTER applicant_name',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_kyc_profile' AND COLUMN_NAME = 'document_last4') = 0,
  'ALTER TABLE nx_kyc_profile ADD COLUMN document_last4 VARCHAR(16) NULL AFTER document_type',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_kyc_profile' AND COLUMN_NAME = 'submitted_at') = 0,
  'ALTER TABLE nx_kyc_profile ADD COLUMN submitted_at DATETIME NULL AFTER document_object_key',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_kyc_profile' AND COLUMN_NAME = 'reviewed_by') = 0,
  'ALTER TABLE nx_kyc_profile ADD COLUMN reviewed_by VARCHAR(64) NULL AFTER submitted_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_kyc_profile' AND COLUMN_NAME = 'reject_reason') = 0,
  'ALTER TABLE nx_kyc_profile ADD COLUMN reject_reason VARCHAR(255) NULL AFTER reviewed_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_kyc_profile' AND COLUMN_NAME = 'expires_at') = 0,
  'ALTER TABLE nx_kyc_profile ADD COLUMN expires_at DATETIME NULL AFTER reject_reason',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_kyc_profile' AND COLUMN_NAME = 'risk_notes') = 0,
  'ALTER TABLE nx_kyc_profile ADD COLUMN risk_notes VARCHAR(512) NULL AFTER expires_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_kyc_profile' AND INDEX_NAME = 'idx_kyc_expiry') = 0,
  'ALTER TABLE nx_kyc_profile ADD INDEX idx_kyc_expiry (status, expires_at, id)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_risk_decision (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  decision_no VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  biz_type VARCHAR(64) NOT NULL,
  biz_no VARCHAR(96) NOT NULL,
  region VARCHAR(32) NULL,
  user_level VARCHAR(16) NULL,
  client_ip VARCHAR(64) NULL,
  device_fingerprint VARCHAR(128) NULL,
  decision VARCHAR(32) NOT NULL,
  reason VARCHAR(255) NULL,
  risk_score INT NOT NULL DEFAULT 0,
  rule_codes VARCHAR(512) NULL,
  rule_snapshot TEXT NULL,
  reviewed_by VARCHAR(64) NULL,
  reviewed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_risk_decision_no (decision_no),
  KEY idx_risk_biz (biz_type, biz_no),
  KEY idx_risk_user_time (user_id, created_at),
  KEY idx_risk_decision_review (decision, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_risk_decision' AND COLUMN_NAME = 'region') = 0,
  'ALTER TABLE nx_risk_decision ADD COLUMN region VARCHAR(32) NULL AFTER biz_no',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_risk_decision' AND COLUMN_NAME = 'user_level') = 0,
  'ALTER TABLE nx_risk_decision ADD COLUMN user_level VARCHAR(16) NULL AFTER region',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_risk_decision' AND COLUMN_NAME = 'client_ip') = 0,
  'ALTER TABLE nx_risk_decision ADD COLUMN client_ip VARCHAR(64) NULL AFTER user_level',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_risk_decision' AND COLUMN_NAME = 'device_fingerprint') = 0,
  'ALTER TABLE nx_risk_decision ADD COLUMN device_fingerprint VARCHAR(128) NULL AFTER client_ip',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_risk_decision' AND COLUMN_NAME = 'reviewed_by') = 0,
  'ALTER TABLE nx_risk_decision ADD COLUMN reviewed_by VARCHAR(64) NULL AFTER reason',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_risk_decision' AND COLUMN_NAME = 'risk_score') = 0,
  'ALTER TABLE nx_risk_decision ADD COLUMN risk_score INT NOT NULL DEFAULT 0 AFTER reason',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_risk_decision' AND COLUMN_NAME = 'rule_codes') = 0,
  'ALTER TABLE nx_risk_decision ADD COLUMN rule_codes VARCHAR(512) NULL AFTER risk_score',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_risk_decision' AND COLUMN_NAME = 'rule_snapshot') = 0,
  'ALTER TABLE nx_risk_decision ADD COLUMN rule_snapshot TEXT NULL AFTER rule_codes',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_risk_decision' AND COLUMN_NAME = 'reviewed_at') = 0,
  'ALTER TABLE nx_risk_decision ADD COLUMN reviewed_at DATETIME NULL AFTER reviewed_by',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_risk_decision' AND INDEX_NAME = 'idx_risk_decision_review') = 0,
  'ALTER TABLE nx_risk_decision ADD INDEX idx_risk_decision_review (decision, created_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_risk_decision' AND INDEX_NAME = 'idx_risk_decision_user_decision_time') = 0,
  'ALTER TABLE nx_risk_decision ADD INDEX idx_risk_decision_user_decision_time (user_id, decision, created_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_risk_decision' AND INDEX_NAME = 'idx_risk_decision_region_time') = 0,
  'ALTER TABLE nx_risk_decision ADD INDEX idx_risk_decision_region_time (region, created_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_risk_decision' AND INDEX_NAME = 'idx_risk_decision_ip_time') = 0,
  'ALTER TABLE nx_risk_decision ADD INDEX idx_risk_decision_ip_time (client_ip, created_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_risk_decision' AND INDEX_NAME = 'idx_risk_decision_device_time') = 0,
  'ALTER TABLE nx_risk_decision ADD INDEX idx_risk_decision_device_time (device_fingerprint, created_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_risk_signal (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  signal_no VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  signal_type VARCHAR(64) NOT NULL,
  severity VARCHAR(32) NOT NULL,
  evidence VARCHAR(1000) NOT NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_risk_signal_no (signal_no),
  KEY idx_risk_signal_user_time (user_id, created_at),
  KEY idx_risk_signal_type_time (signal_type, created_at),
  KEY idx_risk_signal_severity_time (severity, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_risk_blacklist (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  reason VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  source VARCHAR(64) NULL,
  risk_level VARCHAR(32) NULL,
  expires_at DATETIME NULL,
  created_by VARCHAR(64) NULL,
  released_by VARCHAR(64) NULL,
  release_reason VARCHAR(255) NULL,
  released_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_risk_blacklist_user (user_id),
  KEY idx_risk_blacklist_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_risk_blacklist' AND COLUMN_NAME = 'source') = 0,
  'ALTER TABLE nx_risk_blacklist ADD COLUMN source VARCHAR(64) NULL AFTER status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_risk_blacklist' AND COLUMN_NAME = 'risk_level') = 0,
  'ALTER TABLE nx_risk_blacklist ADD COLUMN risk_level VARCHAR(32) NULL AFTER source',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_risk_blacklist' AND COLUMN_NAME = 'expires_at') = 0,
  'ALTER TABLE nx_risk_blacklist ADD COLUMN expires_at DATETIME NULL AFTER risk_level',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_risk_blacklist' AND COLUMN_NAME = 'created_by') = 0,
  'ALTER TABLE nx_risk_blacklist ADD COLUMN created_by VARCHAR(64) NULL AFTER expires_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_risk_blacklist' AND COLUMN_NAME = 'released_by') = 0,
  'ALTER TABLE nx_risk_blacklist ADD COLUMN released_by VARCHAR(64) NULL AFTER created_by',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_risk_blacklist' AND COLUMN_NAME = 'release_reason') = 0,
  'ALTER TABLE nx_risk_blacklist ADD COLUMN release_reason VARCHAR(255) NULL AFTER released_by',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_risk_blacklist' AND COLUMN_NAME = 'released_at') = 0,
  'ALTER TABLE nx_risk_blacklist ADD COLUMN released_at DATETIME NULL AFTER release_reason',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_risk_blacklist' AND INDEX_NAME = 'idx_risk_blacklist_active_expiry') = 0,
  'ALTER TABLE nx_risk_blacklist ADD INDEX idx_risk_blacklist_active_expiry (status, expires_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_account_list (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  kind VARCHAR(16) NOT NULL,
  reason VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  expires_at DATETIME NULL,
  created_by VARCHAR(64) NOT NULL,
  released_by VARCHAR(64) NULL,
  release_reason VARCHAR(255) NULL,
  released_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_account_list_user (user_id),
  KEY idx_account_list_kind_status (kind, status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_account_list' AND COLUMN_NAME = 'released_by') = 0,
  'ALTER TABLE nx_account_list ADD COLUMN released_by VARCHAR(64) NULL AFTER created_by',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_account_list' AND COLUMN_NAME = 'release_reason') = 0,
  'ALTER TABLE nx_account_list ADD COLUMN release_reason VARCHAR(255) NULL AFTER released_by',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_account_list' AND COLUMN_NAME = 'released_at') = 0,
  'ALTER TABLE nx_account_list ADD COLUMN released_at DATETIME NULL AFTER release_reason',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_proof_asset (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  proof_no VARCHAR(96) NOT NULL,
  proof_type VARCHAR(64) NOT NULL,
  object_key VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL,
  file_name VARCHAR(255) NULL,
  content_type VARCHAR(128) NULL,
  size_bytes BIGINT NULL,
  checksum VARCHAR(128) NULL,
  related_biz_type VARCHAR(64) NULL,
  related_biz_no VARCHAR(96) NULL,
  submitted_by VARCHAR(64) NULL,
  reviewed_by VARCHAR(64) NULL,
  reviewed_at DATETIME NULL,
  reject_reason VARCHAR(255) NULL,
  review_note VARCHAR(255) NULL,
  metadata_json VARCHAR(2048) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_proof_no (proof_no),
  KEY idx_proof_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_proof_asset' AND COLUMN_NAME = 'file_name') = 0,
  'ALTER TABLE nx_proof_asset ADD COLUMN file_name VARCHAR(255) NULL AFTER status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_proof_asset' AND COLUMN_NAME = 'content_type') = 0,
  'ALTER TABLE nx_proof_asset ADD COLUMN content_type VARCHAR(128) NULL AFTER file_name',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_proof_asset' AND COLUMN_NAME = 'size_bytes') = 0,
  'ALTER TABLE nx_proof_asset ADD COLUMN size_bytes BIGINT NULL AFTER content_type',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_proof_asset' AND COLUMN_NAME = 'checksum') = 0,
  'ALTER TABLE nx_proof_asset ADD COLUMN checksum VARCHAR(128) NULL AFTER size_bytes',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_proof_asset' AND COLUMN_NAME = 'related_biz_type') = 0,
  'ALTER TABLE nx_proof_asset ADD COLUMN related_biz_type VARCHAR(64) NULL AFTER checksum',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_proof_asset' AND COLUMN_NAME = 'related_biz_no') = 0,
  'ALTER TABLE nx_proof_asset ADD COLUMN related_biz_no VARCHAR(96) NULL AFTER related_biz_type',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_proof_asset' AND COLUMN_NAME = 'submitted_by') = 0,
  'ALTER TABLE nx_proof_asset ADD COLUMN submitted_by VARCHAR(64) NULL AFTER related_biz_no',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_proof_asset' AND COLUMN_NAME = 'reviewed_by') = 0,
  'ALTER TABLE nx_proof_asset ADD COLUMN reviewed_by VARCHAR(64) NULL AFTER submitted_by',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_proof_asset' AND COLUMN_NAME = 'reviewed_at') = 0,
  'ALTER TABLE nx_proof_asset ADD COLUMN reviewed_at DATETIME NULL AFTER reviewed_by',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_proof_asset' AND COLUMN_NAME = 'reject_reason') = 0,
  'ALTER TABLE nx_proof_asset ADD COLUMN reject_reason VARCHAR(255) NULL AFTER reviewed_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_proof_asset' AND COLUMN_NAME = 'metadata_json') = 0,
  'ALTER TABLE nx_proof_asset ADD COLUMN metadata_json VARCHAR(2048) NULL AFTER reject_reason',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_proof_asset' AND COLUMN_NAME = 'review_note') = 0,
  'ALTER TABLE nx_proof_asset ADD COLUMN review_note VARCHAR(255) NULL AFTER reject_reason',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_proof_asset' AND INDEX_NAME = 'idx_proof_status_time') = 0,
  'ALTER TABLE nx_proof_asset ADD INDEX idx_proof_status_time (status, created_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_config_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  config_key VARCHAR(128) NOT NULL,
  config_value TEXT NOT NULL,
  value_type VARCHAR(32) NOT NULL DEFAULT 'STRING',
  config_group VARCHAR(64) NOT NULL DEFAULT 'general',
  visibility VARCHAR(16) NOT NULL DEFAULT 'ADMIN',
  remark VARCHAR(255) NULL,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_admin_third_batch_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  module_code VARCHAR(8) NOT NULL,
  record_type VARCHAR(48) NOT NULL,
  record_key VARCHAR(96) NOT NULL,
  title VARCHAR(180) NOT NULL,
  status VARCHAR(32) NOT NULL,
  category VARCHAR(64) DEFAULT NULL,
  owner VARCHAR(64) DEFAULT NULL,
  priority VARCHAR(16) DEFAULT NULL,
  numeric_value DECIMAL(20,6) NOT NULL DEFAULT 0,
  text_value VARCHAR(1024) DEFAULT NULL,
  impact_scope VARCHAR(512) DEFAULT NULL,
  related_object VARCHAR(160) DEFAULT NULL,
  detail_json TEXT,
  reason VARCHAR(512) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_third_module_key (module_code, record_key, is_deleted),
  KEY idx_admin_third_module_status (module_code, status, updated_at),
  KEY idx_admin_third_related (related_object)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_admin_fourth_batch_report (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  module_code VARCHAR(16) NOT NULL,
  report_id VARCHAR(64) NOT NULL,
  report_name VARCHAR(128) NOT NULL,
  report_type VARCHAR(64) NOT NULL,
  cycle VARCHAR(32) NOT NULL,
  file_format VARCHAR(16) NOT NULL,
  scope_text VARCHAR(255) NOT NULL,
  field_text VARCHAR(255) NOT NULL,
  row_count BIGINT NOT NULL DEFAULT 0,
  contains_pii TINYINT NOT NULL DEFAULT 0,
  masking_policy VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  note VARCHAR(255) DEFAULT NULL,
  last_action VARCHAR(32) DEFAULT NULL,
  last_action_at DATETIME DEFAULT NULL,
  reason VARCHAR(255) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_fourth_report (module_code, report_id),
  KEY idx_fourth_report_module_status (module_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_config_item' AND COLUMN_NAME = 'config_value') <> 'text',
  'ALTER TABLE nx_config_item MODIFY COLUMN config_value TEXT NOT NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_config_item' AND COLUMN_NAME = 'config_group') = 0,
  'ALTER TABLE nx_config_item ADD COLUMN config_group VARCHAR(64) NOT NULL DEFAULT ''general'' AFTER value_type',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_config_item' AND COLUMN_NAME = 'visibility') = 0,
  'ALTER TABLE nx_config_item ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT ''ADMIN'' AFTER config_group',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_i18n_message (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  message_key VARCHAR(128) NOT NULL,
  locale VARCHAR(16) NOT NULL,
  message_value VARCHAR(1024) NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_i18n_key_locale (message_key, locale)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_i18n_namespace (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  namespace_code VARCHAR(64) NOT NULL,
  key_count INT NOT NULL DEFAULT 0,
  coverage_pct INT NOT NULL DEFAULT 100,
  variants VARCHAR(64) NOT NULL DEFAULT '-',
  last_change VARCHAR(32) NOT NULL DEFAULT '-',
  status TINYINT NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_i18n_namespace_code (namespace_code),
  KEY idx_i18n_namespace_active (status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_i18n_integrity_issue (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  issue_code VARCHAR(64) NOT NULL,
  issue_kind VARCHAR(64) NOT NULL,
  issue_count INT NOT NULL DEFAULT 0,
  samples_text TEXT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'open',
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_i18n_integrity_issue_code (issue_code),
  KEY idx_i18n_integrity_status (status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_i18n_hardcoded_finding (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  location VARCHAR(128) NOT NULL,
  raw_copy VARCHAR(256) NOT NULL,
  suggested_key VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'open',
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_i18n_hardcoded_location (location),
  KEY idx_i18n_hardcoded_status (status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_content_page (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  page_code VARCHAR(96) NOT NULL,
  title VARCHAR(128) NOT NULL,
  content MEDIUMTEXT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_content_page_code (page_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_notification_campaign (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  campaign_no VARCHAR(64) NOT NULL,
  name VARCHAR(160) NOT NULL,
  kind VARCHAR(32) NOT NULL DEFAULT 'system',
  tier VARCHAR(16) NOT NULL,
  audience VARCHAR(160) NOT NULL,
  reach_label VARCHAR(32) NOT NULL DEFAULT '-',
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  schedule_text VARCHAR(64) NOT NULL DEFAULT '-',
  sent_label VARCHAR(32) NOT NULL DEFAULT '-',
  read_label VARCHAR(32) NOT NULL DEFAULT '-',
  body_en TEXT NOT NULL,
  body_zh TEXT NOT NULL,
  body_vi TEXT NOT NULL,
  swipe_to VARCHAR(128) NOT NULL DEFAULT '-',
  cta_label VARCHAR(64) NULL,
  cta_href VARCHAR(255) NULL,
  budget_usd DECIMAL(18,2) NULL,
  created_by VARCHAR(64) NULL,
  last_operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_notification_campaign_no (campaign_no),
  KEY idx_notification_campaign_status (status, tier, updated_at),
  KEY idx_notification_campaign_audience (audience, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_notification_cap_rule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tier VARCHAR(16) NOT NULL,
  cap_label VARCHAR(32) NOT NULL,
  policy VARCHAR(512) NOT NULL,
  locked TINYINT NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  last_operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_notification_cap_tier (tier),
  KEY idx_notification_cap_status (status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_help_article (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  article_code VARCHAR(96) NOT NULL,
  title VARCHAR(128) NOT NULL,
  content MEDIUMTEXT NULL,
  category VARCHAR(32) NOT NULL DEFAULT 'help',
  level VARCHAR(32) NOT NULL DEFAULT 'beginner',
  format VARCHAR(32) NOT NULL DEFAULT 'article',
  surface VARCHAR(32) NOT NULL DEFAULT 'Help Center',
  duration_min INT NOT NULL DEFAULT 5,
  reward_nex DECIMAL(18,6) NOT NULL DEFAULT 0.000000,
  progress_pct INT NOT NULL DEFAULT 0,
  featured TINYINT NOT NULL DEFAULT 0,
  emoji VARCHAR(16) NOT NULL DEFAULT '📘',
  tint VARCHAR(32) NOT NULL DEFAULT '#c6ff3a',
  quiz_json MEDIUMTEXT NULL,
  quiz_pass_score INT NULL,
  quiz_retry_limit INT NULL,
  completion_condition VARCHAR(255) NULL,
  reward_event VARCHAR(32) NULL,
  version_no INT NOT NULL DEFAULT 1,
  revision BIGINT NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_help_article_code (article_code),
  KEY idx_help_article_category (category, status, sort_order),
  KEY idx_help_article_featured (featured, status),
  KEY idx_help_article_sort (sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_help_article' AND COLUMN_NAME = 'category') = 0,
  'ALTER TABLE nx_help_article ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT ''help'' AFTER content',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

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

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_help_article' AND COLUMN_NAME = 'level') = 0,
  'ALTER TABLE nx_help_article ADD COLUMN level VARCHAR(32) NOT NULL DEFAULT ''beginner'' AFTER category',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_help_article' AND COLUMN_NAME = 'format') = 0,
  'ALTER TABLE nx_help_article ADD COLUMN format VARCHAR(32) NOT NULL DEFAULT ''article'' AFTER level',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_help_article' AND COLUMN_NAME = 'surface') = 0,
  'ALTER TABLE nx_help_article ADD COLUMN surface VARCHAR(32) NOT NULL DEFAULT ''Help Center'' AFTER format',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_help_article' AND COLUMN_NAME = 'duration_min') = 0,
  'ALTER TABLE nx_help_article ADD COLUMN duration_min INT NOT NULL DEFAULT 5 AFTER surface',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_help_article' AND COLUMN_NAME = 'reward_nex') = 0,
  'ALTER TABLE nx_help_article ADD COLUMN reward_nex DECIMAL(18,6) NOT NULL DEFAULT 0.000000 AFTER duration_min',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_help_article' AND COLUMN_NAME = 'progress_pct') = 0,
  'ALTER TABLE nx_help_article ADD COLUMN progress_pct INT NOT NULL DEFAULT 0 AFTER reward_nex',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_help_article' AND COLUMN_NAME = 'featured') = 0,
  'ALTER TABLE nx_help_article ADD COLUMN featured TINYINT NOT NULL DEFAULT 0 AFTER progress_pct',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_help_article' AND COLUMN_NAME = 'emoji') = 0,
  'ALTER TABLE nx_help_article ADD COLUMN emoji VARCHAR(16) NOT NULL DEFAULT ''📘'' AFTER featured',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_help_article' AND COLUMN_NAME = 'tint') = 0,
  'ALTER TABLE nx_help_article ADD COLUMN tint VARCHAR(32) NOT NULL DEFAULT ''#c6ff3a'' AFTER emoji',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_support_sla_rule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  category VARCHAR(32) NOT NULL,
  first_response_mins INT NOT NULL,
  resolution_hours INT NOT NULL,
  queue VARCHAR(64) NOT NULL,
  escalation VARCHAR(128) NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_support_sla_category (category),
  KEY idx_support_sla_status (status, category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_conversation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_no VARCHAR(40) NOT NULL,
  user_id BIGINT NULL,
  conversation_type VARCHAR(32) NOT NULL DEFAULT 'support',
  status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
  owner_agent_id VARCHAR(64) NULL,
  owner_agent_name VARCHAR(64) NULL,
  unread_count INT NOT NULL DEFAULT 0,
  last_message VARCHAR(512) NULL,
  last_message_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_conversation_no (conversation_no),
  KEY idx_conversation_status_time (status, last_message_at),
  KEY idx_conversation_owner (owner_agent_id, status),
  KEY idx_conversation_user_time (user_id, last_message_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_conversation_transfer (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_no VARCHAR(40) NOT NULL,
  from_agent_id VARCHAR(64) NULL,
  from_agent_name VARCHAR(64) NULL,
  to_type VARCHAR(32) NOT NULL,
  to_id VARCHAR(64) NOT NULL,
  to_name VARCHAR(64) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  operator VARCHAR(64) NULL,
  transferred_at DATETIME NOT NULL,
  accepted_by VARCHAR(64) NULL,
  accepted_at DATETIME NULL,
  return_reason VARCHAR(500) NULL,
  returned_by VARCHAR(64) NULL,
  returned_at DATETIME NULL,
  fallback_reason VARCHAR(500) NULL,
  fallback_by VARCHAR(64) NULL,
  fallback_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_conversation_transfer_no_status (conversation_no, status),
  KEY idx_conversation_transfer_target (to_type, to_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_conversation_transfer' AND COLUMN_NAME = 'fallback_by') = 0,
  'ALTER TABLE nx_conversation_transfer ADD COLUMN fallback_by VARCHAR(64) NULL AFTER fallback_reason',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_conversation_message (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_id BIGINT NOT NULL,
  conversation_no VARCHAR(40) NOT NULL,
  sender_id BIGINT NULL,
  sender_type VARCHAR(16) NOT NULL,
  sender_name VARCHAR(64) NULL,
  content TEXT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_conversation_message_id_time (conversation_id, created_at),
  KEY idx_conversation_message_no_time (conversation_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_conversation_message_receipt (
  message_id BIGINT PRIMARY KEY,
  conversation_no VARCHAR(40) NOT NULL,
  receipt_status VARCHAR(16) NOT NULL DEFAULT 'sent',
  read_by VARCHAR(64) NULL,
  read_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_conversation_receipt_no (conversation_no, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_support_ticket (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  ticket_no VARCHAR(40) NOT NULL,
  user_id BIGINT NOT NULL,
  category VARCHAR(32) NOT NULL,
  priority VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
  status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
  title VARCHAR(160) NOT NULL,
  last_message VARCHAR(512) NULL,
  assigned_admin_id BIGINT NULL,
  assigned_admin_name VARCHAR(64) NULL,
  user_unread_count INT NOT NULL DEFAULT 0,
  ops_unread_count INT NOT NULL DEFAULT 0,
  message_count INT NOT NULL DEFAULT 0,
  last_message_at DATETIME NULL,
  closed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_support_ticket_no (ticket_no),
  KEY idx_support_ticket_user_time (user_id, last_message_at),
  KEY idx_support_ticket_ops (status, priority, last_message_at),
  KEY idx_support_ticket_assignee (assigned_admin_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_support_ticket_message (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  ticket_id BIGINT NOT NULL,
  ticket_no VARCHAR(40) NOT NULL,
  sender_id BIGINT NULL,
  sender_type VARCHAR(16) NOT NULL,
  sender_name VARCHAR(64) NULL,
  content TEXT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_support_message_ticket (ticket_id, created_at),
  KEY idx_support_message_ticket_no (ticket_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_support_ticket_attachment (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  ticket_id BIGINT NOT NULL,
  message_id BIGINT NOT NULL,
  object_key VARCHAR(512) NOT NULL,
  file_name VARCHAR(255) NULL,
  content_type VARCHAR(96) NULL,
  file_size BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_support_attachment_ticket (ticket_id),
  KEY idx_support_attachment_message (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_nova_channel (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  channel_key VARCHAR(64) NOT NULL,
  channel_name VARCHAR(128) NOT NULL,
  trigger_rule VARCHAR(255) NOT NULL,
  tick_rule VARCHAR(64) NOT NULL,
  cooldown_rule VARCHAR(64) NOT NULL,
  phase_keyed VARCHAR(128) NOT NULL DEFAULT '',
  ctr_pct DECIMAL(8,4) NOT NULL DEFAULT 0,
  target_ctr_pct DECIMAL(8,4) NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 1000,
  operator VARCHAR(128) NULL,
  reason VARCHAR(512) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_nova_channel_key (channel_key),
  KEY idx_nova_channel_order (is_deleted, sort_order, channel_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_nova_channel' AND COLUMN_NAME = 'target_ctr_pct') = 0,
  'ALTER TABLE nx_nova_channel ADD COLUMN target_ctr_pct DECIMAL(8,4) NOT NULL DEFAULT 0 AFTER ctr_pct',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_nova_template (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  channel_key VARCHAR(64) NOT NULL,
  template_name VARCHAR(128) NOT NULL,
  cta VARCHAR(255) NOT NULL,
  version VARCHAR(32) NOT NULL,
  title_zh VARCHAR(255) NOT NULL DEFAULT '',
  body_zh TEXT NOT NULL,
  title_vi VARCHAR(255) NOT NULL DEFAULT '',
  body_vi TEXT NOT NULL,
  title_en VARCHAR(255) NOT NULL DEFAULT '',
  body_en TEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  operator VARCHAR(128) NULL,
  reason VARCHAR(512) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_nova_template_channel (channel_key),
  KEY idx_nova_template_status (is_deleted, status, channel_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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

CREATE TABLE IF NOT EXISTS nx_nova_social_pool (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  pool_key VARCHAR(64) NOT NULL,
  pool_name VARCHAR(128) NOT NULL,
  description VARCHAR(512) NOT NULL DEFAULT '',
  item_count INT NOT NULL DEFAULT 0,
  operator VARCHAR(128) NULL,
  reason VARCHAR(512) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_nova_social_pool_key (pool_key),
  KEY idx_nova_social_pool_order (is_deleted, pool_key)
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

CREATE TABLE IF NOT EXISTS nx_emergency_control_setting (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  setting_key VARCHAR(96) NOT NULL,
  setting_value VARCHAR(512) NOT NULL,
  value_type VARCHAR(32) NOT NULL DEFAULT 'STRING',
  group_code VARCHAR(64) NOT NULL DEFAULT 'emergency',
  remark VARCHAR(512) NULL,
  operator VARCHAR(64) NULL,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_emergency_control_setting_key (setting_key),
  KEY idx_emergency_control_setting_group (group_code, status, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_emergency_geo_country_policy (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  country_code VARCHAR(2) NOT NULL,
  country_name VARCHAR(128) NULL,
  policy_status VARCHAR(32) NOT NULL,
  reason VARCHAR(500) NULL,
  operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_geo_country_policy (country_code),
  KEY idx_geo_country_policy_status (policy_status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_emergency_geo_endpoint_catalog (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  endpoint_key VARCHAR(64) NOT NULL,
  endpoint_path VARCHAR(255) NOT NULL,
  label VARCHAR(128) NOT NULL,
  biz VARCHAR(128) NOT NULL,
  domain_code VARCHAR(16) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  sort_order INT NOT NULL DEFAULT 100,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_geo_endpoint_catalog_key (endpoint_key),
  KEY idx_geo_endpoint_catalog_status (status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_emergency_geo_endpoint_policy (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  endpoint_key VARCHAR(64) NOT NULL,
  endpoint_path VARCHAR(255) NOT NULL,
  label VARCHAR(128) NOT NULL,
  biz VARCHAR(128) NOT NULL,
  domain_code VARCHAR(16) NOT NULL,
  country_code VARCHAR(2) NOT NULL,
  policy_source VARCHAR(32) NOT NULL,
  reason VARCHAR(500) NULL,
  operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_geo_endpoint_country (endpoint_key, country_code),
  KEY idx_geo_endpoint_policy_endpoint (endpoint_key, is_deleted),
  KEY idx_geo_endpoint_policy_country (country_code, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_emergency_geo_block_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  country_code VARCHAR(2) NOT NULL,
  country_name VARCHAR(128) NULL,
  endpoint_key VARCHAR(64) NULL,
  source VARCHAR(64) NULL,
  event_count INT NOT NULL DEFAULT 1,
  recorded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_geo_event_country_time (country_code, recorded_at),
  KEY idx_geo_event_endpoint_time (endpoint_key, recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_emergency_tamper_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_no VARCHAR(96) NULL,
  user_id BIGINT NULL,
  user_no VARCHAR(32) NULL,
  path_key VARCHAR(64) NOT NULL,
  path_name VARCHAR(128) NOT NULL,
  description VARCHAR(500) NULL,
  cluster_code VARCHAR(64) NULL,
  k4_delta INT NOT NULL DEFAULT 0,
  event_count INT NOT NULL DEFAULT 1,
  occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_tamper_event_path_time (path_key, occurred_at),
  KEY idx_tamper_event_user_time (user_id, user_no, occurred_at),
  KEY idx_tamper_event_cluster (cluster_code, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_emergency_tamper_report (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  report_id VARCHAR(96) NOT NULL,
  window_label VARCHAR(32) NOT NULL,
  masked TINYINT NOT NULL DEFAULT 1,
  status VARCHAR(32) NOT NULL DEFAULT 'READY',
  payload_json JSON NULL,
  operator VARCHAR(64) NULL,
  reason VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_emergency_tamper_report_id (report_id),
  KEY idx_emergency_tamper_report_time (created_at, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_emergency_tamper_gate (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  gate_key VARCHAR(64) NOT NULL,
  gate_name VARCHAR(128) NOT NULL,
  event_count_24h INT NOT NULL DEFAULT 0,
  verdict VARCHAR(32) NULL,
  review_reason VARCHAR(500) NULL,
  reviewed_by VARCHAR(64) NULL,
  reviewed_at DATETIME NULL,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_emergency_tamper_gate (gate_key),
  KEY idx_emergency_tamper_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_emergency_sop_playbook (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  scene VARCHAR(64) NOT NULL,
  emergency_track TINYINT NOT NULL DEFAULT 0,
  sla VARCHAR(64) NOT NULL,
  state VARCHAR(32) NOT NULL DEFAULT 'todo',
  owner VARCHAR(64) NOT NULL,
  last_drill_at DATETIME NULL,
  notify_campaign_no VARCHAR(96) NULL,
  notify_template VARCHAR(255) NULL,
  rollback_plan VARCHAR(500) NULL,
  drill_required TINYINT NOT NULL DEFAULT 1,
  draft TINYINT NOT NULL DEFAULT 1,
  summary VARCHAR(500) NULL,
  created_by VARCHAR(64) NULL,
  updated_by VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_emergency_sop_playbook_code (code),
  KEY idx_emergency_sop_playbook_state (state, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_emergency_sop_action (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  playbook_code VARCHAR(64) NOT NULL,
  step_order INT NOT NULL,
  domain_code VARCHAR(16) NOT NULL,
  action_text VARCHAR(500) NOT NULL,
  approve_required TINYINT NOT NULL DEFAULT 0,
  ref_code VARCHAR(96) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_emergency_sop_action_step (playbook_code, step_order),
  KEY idx_emergency_sop_action_code (playbook_code, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_emergency_sop_execution (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  execution_id VARCHAR(96) NOT NULL,
  playbook_code VARCHAR(64) NOT NULL,
  playbook_name VARCHAR(128) NOT NULL,
  trigger_reason VARCHAR(500) NOT NULL,
  execution_mode VARCHAR(32) NOT NULL,
  step_status_json JSON NULL,
  operator VARCHAR(64) NULL,
  role_gate VARCHAR(64) NULL,
  idempotency_key VARCHAR(128) NULL,
  notification_json JSON NULL,
  domain_action_json JSON NULL,
  rollback_plan VARCHAR(500) NULL,
  rollback_status VARCHAR(32) NULL,
  rollback_at DATETIME NULL,
  rollback_reason VARCHAR(500) NULL,
  rollback_action_json JSON NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_emergency_sop_execution_id (execution_id),
  UNIQUE KEY uk_emergency_sop_execution_idem (playbook_code, idempotency_key),
  KEY idx_emergency_sop_execution_code_time (playbook_code, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_emergency_sop_step (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  sop_id VARCHAR(64) NOT NULL,
  step_order INT NOT NULL,
  step_title VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  status_reason VARCHAR(500) NULL,
  operator VARCHAR(64) NULL,
  operated_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_emergency_sop_id (sop_id),
  KEY idx_emergency_sop_order (step_order),
  KEY idx_emergency_sop_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_openapi_app (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  owner_user_id BIGINT NOT NULL,
  app_name VARCHAR(128) NOT NULL,
  app_key VARCHAR(96) NOT NULL,
  app_secret VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  qps_limit INT NOT NULL DEFAULT 20,
  daily_limit INT NOT NULL DEFAULT 10000,
  remark VARCHAR(255) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_openapi_app_key (app_key),
  KEY idx_openapi_owner (owner_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_openapi_call_audit (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  app_id BIGINT NOT NULL,
  app_key VARCHAR(96) NOT NULL,
  api_path VARCHAR(255) NOT NULL,
  http_method VARCHAR(16) NOT NULL,
  nonce VARCHAR(128) NOT NULL,
  request_hash VARCHAR(128) NOT NULL,
  response_code INT NULL,
  response_message VARCHAR(255) NULL,
  cost_ms BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_openapi_app_nonce (app_key, nonce),
  KEY idx_openapi_audit_app_time (app_id, created_at),
  KEY idx_openapi_audit_path_time (api_path, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_openapi_nonce (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  app_key VARCHAR(96) NOT NULL,
  nonce VARCHAR(128) NOT NULL,
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_openapi_nonce (app_key, nonce),
  KEY idx_openapi_nonce_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_webhook_subscription (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  app_id BIGINT NOT NULL,
  event_type VARCHAR(96) NOT NULL,
  callback_url VARCHAR(512) NOT NULL,
  secret VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_webhook_app_event (app_id, event_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_webhook_delivery (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  subscription_id BIGINT NOT NULL,
  app_id BIGINT NOT NULL,
  event_type VARCHAR(96) NOT NULL,
  payload JSON NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  last_status_code INT NULL,
  last_error VARCHAR(512) NULL,
  next_retry_at DATETIME NULL,
  delivered_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_webhook_delivery_status (status, next_retry_at, created_at),
  KEY idx_webhook_delivery_app_event (app_id, event_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_openapi_app' AND COLUMN_NAME = 'qps_limit') = 0,
  'ALTER TABLE nx_openapi_app ADD COLUMN qps_limit INT NOT NULL DEFAULT 20 AFTER status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_openapi_app' AND COLUMN_NAME = 'daily_limit') = 0,
  'ALTER TABLE nx_openapi_app ADD COLUMN daily_limit INT NOT NULL DEFAULT 10000 AFTER qps_limit',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_openapi_call_audit' AND INDEX_NAME = 'uk_openapi_app_nonce') > 0,
  'ALTER TABLE nx_openapi_call_audit DROP INDEX uk_openapi_app_nonce',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_openapi_call_audit' AND INDEX_NAME = 'idx_openapi_app_nonce') = 0,
  'CREATE INDEX idx_openapi_app_nonce ON nx_openapi_call_audit (app_key, nonce)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_webhook_delivery' AND COLUMN_NAME = 'last_status_code') = 0,
  'ALTER TABLE nx_webhook_delivery ADD COLUMN last_status_code INT NULL AFTER retry_count',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_webhook_delivery' AND COLUMN_NAME = 'next_retry_at') = 0,
  'ALTER TABLE nx_webhook_delivery ADD COLUMN next_retry_at DATETIME NULL AFTER last_error',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_webhook_delivery' AND COLUMN_NAME = 'delivered_at') = 0,
  'ALTER TABLE nx_webhook_delivery ADD COLUMN delivered_at DATETIME NULL AFTER next_retry_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_content_copy (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  copy_key VARCHAR(96) NOT NULL,
  description VARCHAR(255) NOT NULL,
  surface VARCHAR(32) NOT NULL,
  current_version VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED',
  i18n_key VARCHAR(128) NOT NULL,
  experiment_id VARCHAR(64) NULL,
  last_change VARCHAR(32) NULL,
  draft_version VARCHAR(32) NULL,
  draft_zh TEXT NULL,
  draft_en TEXT NULL,
  draft_vi TEXT NULL,
  copy_position VARCHAR(96) NULL,
  draft_copy_position VARCHAR(96) NULL,
  draft_surface VARCHAR(32) NULL,
  draft_audience VARCHAR(96) NULL,
  draft_audience_json JSON NULL,
  draft_traffic_split VARCHAR(32) NULL,
  draft_note VARCHAR(255) NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  last_operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_content_copy_key (copy_key),
  KEY idx_content_copy_surface_status (surface, status),
  KEY idx_content_copy_position (copy_position),
  KEY idx_content_copy_draft_position (draft_copy_position)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_content_copy_version (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  copy_key VARCHAR(96) NOT NULL,
  version VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  chain VARCHAR(128) NULL,
  ts_label VARCHAR(32) NULL,
  zh_text TEXT NOT NULL,
  en_text TEXT NOT NULL,
  vi_text TEXT NULL,
  copy_position VARCHAR(96) NULL,
  surface VARCHAR(32) NOT NULL,
  audience VARCHAR(96) NOT NULL,
  audience_json JSON NULL,
  traffic_split VARCHAR(32) NOT NULL,
  version_note VARCHAR(255) NULL,
  last_operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_content_copy_version (copy_key, version),
  KEY idx_content_copy_version_status (copy_key, status),
  KEY idx_content_copy_version_position (copy_position)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_i18n_message_version (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  message_key VARCHAR(128) NOT NULL,
  version_no INT NOT NULL,
  zh_value VARCHAR(1024) NOT NULL,
  en_value VARCHAR(1024) NOT NULL,
  vi_value VARCHAR(1024) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_i18n_message_version (message_key, version_no),
  KEY idx_i18n_message_version_status (message_key, status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification_campaign' AND COLUMN_NAME = 'body_vi') = 0,
  'ALTER TABLE nx_notification_campaign ADD COLUMN body_vi TEXT NULL AFTER body_zh',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
UPDATE nx_notification_campaign SET body_vi = body_zh WHERE body_vi IS NULL;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification_campaign' AND COLUMN_NAME = 'body_vi' AND IS_NULLABLE = 'YES') > 0,
  'ALTER TABLE nx_notification_campaign MODIFY COLUMN body_vi TEXT NOT NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification_campaign' AND COLUMN_NAME = 'cta_label') = 0,
  'ALTER TABLE nx_notification_campaign ADD COLUMN cta_label VARCHAR(64) NULL AFTER swipe_to',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification_campaign' AND COLUMN_NAME = 'cta_href') = 0,
  'ALTER TABLE nx_notification_campaign ADD COLUMN cta_href VARCHAR(255) NULL AFTER cta_label',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
UPDATE nx_notification_campaign
   SET status = 'FAILED', schedule_text = '旧排期格式无效，请重新创建', updated_at = NOW()
 WHERE status = 'SCHEDULED'
   AND schedule_text NOT REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(:[0-9]{2})?$';

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_nova_template' AND COLUMN_NAME = 'title_zh') = 0,
  'ALTER TABLE nx_nova_template ADD COLUMN title_zh VARCHAR(255) NOT NULL DEFAULT '''' AFTER version, ADD COLUMN body_zh TEXT NOT NULL AFTER title_zh, ADD COLUMN title_vi VARCHAR(255) NOT NULL DEFAULT '''' AFTER body_zh, ADD COLUMN body_vi TEXT NOT NULL AFTER title_vi, ADD COLUMN title_en VARCHAR(255) NOT NULL DEFAULT '''' AFTER body_vi, ADD COLUMN body_en TEXT NOT NULL AFTER title_en',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE nx_nova_template t
LEFT JOIN nx_nova_channel c ON c.channel_key = t.channel_key AND c.is_deleted = 0
SET t.status = 'DRAFT', c.enabled = 0, t.updated_at = NOW(), c.updated_at = NOW()
WHERE t.is_deleted = 0
  AND (TRIM(t.title_zh) = '' OR TRIM(t.body_zh) = '' OR TRIM(t.title_vi) = '' OR TRIM(t.body_vi) = '')
  AND (UPPER(t.status) <> 'DRAFT' OR COALESCE(c.enabled, 0) <> 0);

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

INSERT IGNORE INTO nx_content_copy_version_option
  (version_key, name, description, status, sort_order, revision, last_operator, created_at, updated_at, is_deleted)
VALUES
  ('v1', CONVERT(0xE78988E69CAC207631 USING utf8mb4), CONVERT(0xE5889DE5A78BE69687E6A188E78988E69CAC USING utf8mb4), 'ACTIVE', 10, 1, 'schema', NOW(), NOW(), 0),
  ('v2', CONVERT(0xE78988E69CAC207632 USING utf8mb4), CONVERT(0xE7ACACE4BA8CE78988E69687E6A188 USING utf8mb4), 'ACTIVE', 20, 1, 'schema', NOW(), NOW(), 0),
  ('v3', CONVERT(0xE78988E69CAC207633 USING utf8mb4), CONVERT(0xE7ACACE4B889E78988E69687E6A188 USING utf8mb4), 'ACTIVE', 30, 1, 'schema', NOW(), NOW(), 0),
  ('v4', CONVERT(0xE78988E69CAC207634 USING utf8mb4), CONVERT(0xE7ACACE59B9BE78988E69687E6A188 USING utf8mb4), 'ACTIVE', 40, 1, 'schema', NOW(), NOW(), 0),
  ('v5', CONVERT(0xE78988E69CAC207635 USING utf8mb4), CONVERT(0xE7ACACE4BA94E78988E69687E6A188 USING utf8mb4), 'ACTIVE', 50, 1, 'schema', NOW(), NOW(), 0);

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

INSERT IGNORE INTO nx_content_copy_position
  (position_key, name, surface, sort_order, status, last_operator, created_at, updated_at, is_deleted)
VALUES
  ('home.hero', '首页顶部主视觉', 'home', 10, 'ACTIVE', 'schema', NOW(), NOW(), 0),
  ('home.conversion-banner', '首页转化横幅', 'home', 20, 'ACTIVE', 'schema', NOW(), NOW(), 0),
  ('store.hero', '商城顶部横幅', 'store', 30, 'ACTIVE', 'schema', NOW(), NOW(), 0),
  ('earn.hero', '赚取顶部横幅', 'earn', 40, 'ACTIVE', 'schema', NOW(), NOW(), 0),
  ('me.notice', '我的页面提示', 'me', 50, 'ACTIVE', 'schema', NOW(), NOW(), 0);

CREATE TABLE IF NOT EXISTS nx_content_experiment (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  experiment_id VARCHAR(64) NOT NULL,
  copy_key VARCHAR(96) NOT NULL,
  audience VARCHAR(96) NOT NULL,
  audience_snapshot_json JSON NULL,
  impressions_label VARCHAR(32) NOT NULL,
  conversions_label VARCHAR(32) NOT NULL,
  state VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
  note VARCHAR(255) NULL,
  last_operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_content_experiment_id (experiment_id),
  KEY idx_content_experiment_copy_state (copy_key, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_content_experiment_variant (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  experiment_id VARCHAR(64) NOT NULL,
  variant_name VARCHAR(128) NOT NULL,
  copy_version VARCHAR(32) NULL,
  split_pct INT NOT NULL,
  cvr_pct DECIMAL(8,2) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_content_experiment_variant (experiment_id, variant_name),
  KEY idx_content_experiment_variant_version (experiment_id, copy_version),
  KEY idx_content_experiment_variant_order (experiment_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Backfill only when every live variant resolves to one safe audience snapshot.
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

CREATE TABLE IF NOT EXISTS nx_content_experiment_framework (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  param_key VARCHAR(64) NOT NULL,
  param_name VARCHAR(128) NOT NULL,
  current_value VARCHAR(80) NOT NULL,
  description VARCHAR(255) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  last_operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_content_framework_key (param_key),
  KEY idx_content_framework_order (sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_trust_section (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  section_key VARCHAR(64) NOT NULL,
  description VARCHAR(255) NOT NULL,
  struct_text VARCHAR(500) NOT NULL,
  version_label VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED',
  role_gate VARCHAR(64) NOT NULL,
  high_sensitivity TINYINT NOT NULL DEFAULT 0,
  last_change VARCHAR(32) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  last_operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_trust_section_key (section_key),
  KEY idx_trust_section_status_order (status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_trust_section_version (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  section_key VARCHAR(64) NOT NULL,
  version_label VARCHAR(32) NOT NULL,
  description VARCHAR(255) NOT NULL,
  struct_text VARCHAR(255) NOT NULL,
  fields_json MEDIUMTEXT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  revision BIGINT NOT NULL DEFAULT 0,
  last_operator VARCHAR(128) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_trust_section_version (section_key, version_label, is_deleted),
  KEY idx_trust_section_version_status (section_key, status, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_trust_section_field (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  section_key VARCHAR(64) NOT NULL,
  field_key VARCHAR(128) NOT NULL,
  field_value VARCHAR(500) NOT NULL,
  field_delta VARCHAR(64) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  last_operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_trust_section_field (section_key, field_key),
  KEY idx_trust_section_field_order (section_key, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_disclosure_jurisdiction (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  jurisdiction_code VARCHAR(32) NOT NULL,
  jurisdiction_name VARCHAR(64) NOT NULL,
  country_codes VARCHAR(255) NOT NULL DEFAULT '',
  version_label VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED',
  published_at_label VARCHAR(32) NULL,
  affected_count BIGINT NOT NULL DEFAULT 0,
  ack_progress_pct DECIMAL(8,2) NOT NULL DEFAULT 0,
  blocked_count BIGINT NOT NULL DEFAULT 0,
  last_operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_disclosure_jurisdiction (jurisdiction_code),
  KEY idx_disclosure_jurisdiction_version (version_label, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_disclosure_chapter (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  jurisdiction_code VARCHAR(32) NOT NULL,
  version_label VARCHAR(32) NOT NULL,
  chapter_no VARCHAR(16) NOT NULL,
  zh_title VARCHAR(255) NOT NULL,
  vi_title VARCHAR(255) NOT NULL,
  en_title VARCHAR(255) NOT NULL,
  zh_body TEXT NOT NULL,
  vi_body TEXT NOT NULL,
  en_body TEXT NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  last_operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_disclosure_chapter (jurisdiction_code, version_label, chapter_no),
  KEY idx_disclosure_chapter_order (jurisdiction_code, version_label, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_disclosure_gate_action (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  action_key VARCHAR(64) NOT NULL,
  action_name VARCHAR(128) NOT NULL,
  description VARCHAR(500) NOT NULL,
  status_label VARCHAR(64) NOT NULL,
  tone VARCHAR(32) NOT NULL DEFAULT 'dim',
  active TINYINT NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 0,
  last_operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_disclosure_gate_action (action_key),
  KEY idx_disclosure_gate_action_order (active, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_disclosure_draft (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  jurisdiction_code VARCHAR(32) NOT NULL,
  version_label VARCHAR(32) NOT NULL,
  language_scope VARCHAR(32) NOT NULL,
  effective_date VARCHAR(32) NOT NULL,
  requires_reack TINYINT NOT NULL DEFAULT 1,
  zh_body TEXT NOT NULL,
  vi_body TEXT NOT NULL,
  en_body TEXT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  revision BIGINT NOT NULL DEFAULT 1,
  content_hash CHAR(64) NOT NULL DEFAULT '',
  published_slot TINYINT GENERATED ALWAYS AS (
    CASE WHEN status = 'PUBLISHED' AND is_deleted = 0 THEN 1 ELSE NULL END
  ) STORED,
  last_operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_disclosure_draft (jurisdiction_code, version_label),
  UNIQUE KEY uk_disclosure_single_published (jurisdiction_code, published_slot),
  KEY idx_disclosure_draft_status (jurisdiction_code, status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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

CREATE TABLE IF NOT EXISTS nx_disclosure_read_token (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  token_hash CHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  jurisdiction_code VARCHAR(32) NOT NULL,
  version_label VARCHAR(32) NOT NULL,
  expires_at DATETIME NOT NULL,
  consumed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_disclosure_read_token_hash (token_hash),
  UNIQUE KEY uk_disclosure_read_token_binding (user_id, jurisdiction_code, version_label),
  KEY idx_disclosure_read_token_expiry (expires_at, consumed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_disclosure_gate_block_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  jurisdiction_code VARCHAR(32) NOT NULL,
  action_key VARCHAR(64) NOT NULL,
  business_flow_id VARCHAR(128) NOT NULL,
  blocked_at DATETIME NOT NULL,
  UNIQUE KEY uk_disclosure_gate_block_flow (user_id, action_key, business_flow_id),
  KEY idx_disclosure_gate_block_jurisdiction (jurisdiction_code, blocked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===== 客户档案:自定义标签 + 内部备注(content 域,按 user_id 聚合,跨会话共享)=====

-- 客户自定义标签(坐席手动添加;物理删除,UNIQUE 防重,操作历史走审计日志)
CREATE TABLE IF NOT EXISTS nx_customer_tag (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  tag VARCHAR(64) NOT NULL,
  last_operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_customer_tag_user_tag (user_id, tag),
  KEY idx_customer_tag_user (user_id, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 客户内部备注(坐席留档;软删除保留合规追溯)
CREATE TABLE IF NOT EXISTS nx_customer_note (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  author VARCHAR(64) NOT NULL,
  content VARCHAR(2000) NOT NULL,
  last_operator VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_customer_note_user_time (user_id, is_deleted, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_audit_object_lock (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  ticket_id VARCHAR(64) NOT NULL,
  target_domain VARCHAR(4) NOT NULL,
  target_type VARCHAR(64) NOT NULL,
  target_id VARCHAR(128) NOT NULL,
  operator VARCHAR(128) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_target (target_domain, target_type, target_id),
  KEY idx_lock_ticket (ticket_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

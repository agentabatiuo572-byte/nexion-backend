CREATE DATABASE IF NOT EXISTS nexion DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE nexion;

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

CREATE TABLE IF NOT EXISTS admin (
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

CREATE TABLE IF NOT EXISTS admin_role (
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

CREATE TABLE IF NOT EXISTS admin_permission (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  permission_code VARCHAR(96) NOT NULL,
  permission_name VARCHAR(96) NOT NULL,
  resource_type VARCHAR(32) NOT NULL,
  resource_path VARCHAR(255) NULL,
  remark VARCHAR(255) NULL,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_permission_code (permission_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS admin_menu (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  menu_code VARCHAR(96) NOT NULL,
  menu_name VARCHAR(96) NOT NULL,
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

CREATE TABLE IF NOT EXISTS admin_role_relation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  admin_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_role_relation (admin_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS admin_role_permission (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_id BIGINT NOT NULL,
  permission_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_role_permission (role_id, permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS admin_role_menu (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_id BIGINT NOT NULL,
  menu_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_role_menu (role_id, menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
  UNIQUE KEY uk_wallet_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_wallet' AND COLUMN_NAME = 'version') = 0,
  'ALTER TABLE nx_user_wallet ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER lifetime_earned',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_wallet' AND COLUMN_NAME = 'is_deleted') = 0,
  'ALTER TABLE nx_user_wallet ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0',
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
  KEY idx_wallet_ledger_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_wallet_ledger' AND INDEX_NAME = 'uk_wallet_ledger_biz') = 0,
  'ALTER TABLE nx_wallet_ledger ADD UNIQUE KEY uk_wallet_ledger_biz (biz_no, asset, direction)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_withdrawal_order (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  withdrawal_no VARCHAR(96) NOT NULL,
  asset VARCHAR(16) NOT NULL,
  amount DECIMAL(18,6) NOT NULL,
  fee DECIMAL(18,6) NOT NULL DEFAULT 0,
  target_address VARCHAR(128) NOT NULL,
  risk_decision_id BIGINT NULL,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_withdrawal_no (withdrawal_no),
  KEY idx_withdrawal_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
  KEY idx_exchange_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_product_no (product_no),
  KEY idx_product_sale (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_order (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  order_no VARCHAR(96) NOT NULL,
  product_id BIGINT NOT NULL,
  quantity INT NOT NULL DEFAULT 1,
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

CREATE TABLE IF NOT EXISTS nx_user_device (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  source_order_no VARCHAR(96) NULL,
  product_id BIGINT NULL,
  instance_no VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  device_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  hashrate DECIMAL(18,6) NOT NULL DEFAULT 0,
  daily_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  daily_nex DECIMAL(18,6) NOT NULL DEFAULT 0,
  last_seen_at DATETIME NULL,
  activated_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_device_instance_no (instance_no),
  KEY idx_user_device_user (user_id),
  KEY idx_user_device_order (source_order_no),
  KEY idx_user_device_status (status, last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND COLUMN_NAME = 'source_order_no') = 0,
  'ALTER TABLE nx_user_device ADD COLUMN source_order_no VARCHAR(96) NULL AFTER user_id',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND COLUMN_NAME = 'product_id') = 0,
  'ALTER TABLE nx_user_device ADD COLUMN product_id BIGINT NULL AFTER source_order_no',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND COLUMN_NAME = 'device_type') = 0,
  'ALTER TABLE nx_user_device ADD COLUMN device_type VARCHAR(32) NOT NULL DEFAULT ''MOBILE'' AFTER name',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND COLUMN_NAME = 'hashrate') = 0,
  'ALTER TABLE nx_user_device ADD COLUMN hashrate DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND COLUMN_NAME = 'device_id') > 0,
  'ALTER TABLE nx_user_device MODIFY COLUMN device_id BIGINT NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_device' AND INDEX_NAME = 'idx_user_device_status') = 0,
  'ALTER TABLE nx_user_device ADD INDEX idx_user_device_status (status, last_seen_at)',
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
  completed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_compute_task_no (task_no),
  KEY idx_compute_task_device_time (user_device_id, created_at),
  KEY idx_compute_task_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
  user_id BIGINT NOT NULL,
  type VARCHAR(32) NOT NULL,
  title VARCHAR(128) NOT NULL,
  body VARCHAR(512) NOT NULL,
  read_flag TINYINT NOT NULL DEFAULT 0,
  push_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_notification_user_time (user_id, created_at),
  KEY idx_notification_push (push_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification' AND COLUMN_NAME = 'push_status') = 0,
  'ALTER TABLE nx_notification ADD COLUMN push_status VARCHAR(32) NOT NULL DEFAULT ''PENDING'' AFTER read_flag',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_notification' AND INDEX_NAME = 'idx_notification_push') = 0,
  'ALTER TABLE nx_notification ADD INDEX idx_notification_push (push_status, created_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_kyc_profile (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  kyc_no VARCHAR(96) NOT NULL,
  status VARCHAR(32) NOT NULL,
  country VARCHAR(64) NULL,
  document_object_key VARCHAR(255) NULL,
  reviewed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_kyc_user (user_id),
  UNIQUE KEY uk_kyc_no (kyc_no),
  KEY idx_kyc_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_risk_decision (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  decision_no VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  biz_type VARCHAR(64) NOT NULL,
  biz_no VARCHAR(96) NOT NULL,
  decision VARCHAR(32) NOT NULL,
  reason VARCHAR(255) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_risk_decision_no (decision_no),
  KEY idx_risk_biz (biz_type, biz_no),
  KEY idx_risk_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_proof_asset (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  proof_no VARCHAR(96) NOT NULL,
  proof_type VARCHAR(64) NOT NULL,
  object_key VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_proof_no (proof_no),
  KEY idx_proof_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_config_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  config_key VARCHAR(128) NOT NULL,
  config_value VARCHAR(1024) NOT NULL,
  value_type VARCHAR(32) NOT NULL DEFAULT 'STRING',
  remark VARCHAR(255) NULL,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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

CREATE TABLE IF NOT EXISTS nx_help_article (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  article_code VARCHAR(96) NOT NULL,
  title VARCHAR(128) NOT NULL,
  content MEDIUMTEXT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_help_article_code (article_code),
  KEY idx_help_article_sort (sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

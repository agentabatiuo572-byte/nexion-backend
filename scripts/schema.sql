CREATE DATABASE IF NOT EXISTS nexion
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE nexion;

CREATE TABLE IF NOT EXISTS nx_user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  country_code VARCHAR(8) NOT NULL,
  phone VARCHAR(32) NOT NULL,
  password_hash VARCHAR(128) NOT NULL,
  nickname VARCHAR(64) NOT NULL,
  avatar_url VARCHAR(512) NULL,
  referral_code VARCHAR(32) NOT NULL,
  sponsor_code VARCHAR(32) NULL,
  kyc_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  user_level VARCHAR(16) NOT NULL DEFAULT 'L1',
  v_rank VARCHAR(16) NOT NULL DEFAULT 'V0',
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_phone (country_code, phone),
  UNIQUE KEY uk_user_referral_code (referral_code)
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
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_permission_code (permission_code)
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

CREATE TABLE IF NOT EXISTS nx_device (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  device_no VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  type VARCHAR(32) NOT NULL,
  tier VARCHAR(32) NULL,
  status VARCHAR(32) NOT NULL,
  price_usdt DECIMAL(18,2) NULL,
  hashrate DECIMAL(18,6) NOT NULL DEFAULT 0,
  estimated_daily_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  daily_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  daily_nex DECIMAL(18,6) NOT NULL DEFAULT 0,
  stock INT NOT NULL DEFAULT 0,
  cover_url VARCHAR(512) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_device_no (device_no),
  KEY idx_device_sale (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'nexion' AND TABLE_NAME = 'nx_device' AND INDEX_NAME = 'idx_device_user') > 0,
  'ALTER TABLE nx_device DROP INDEX idx_device_user',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'nexion' AND TABLE_NAME = 'nx_device' AND INDEX_NAME = 'idx_device_sale') > 0,
  'ALTER TABLE nx_device DROP INDEX idx_device_sale',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'nexion' AND TABLE_NAME = 'nx_device' AND COLUMN_NAME = 'user_id') > 0,
  'ALTER TABLE nx_device DROP COLUMN user_id',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'nexion' AND TABLE_NAME = 'nx_device' AND COLUMN_NAME = 'last_seen_at') > 0,
  'ALTER TABLE nx_device DROP COLUMN last_seen_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'nexion' AND TABLE_NAME = 'nx_device' AND COLUMN_NAME = 'tier') = 0,
  'ALTER TABLE nx_device ADD COLUMN tier VARCHAR(32) NULL AFTER type',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'nexion' AND TABLE_NAME = 'nx_device' AND COLUMN_NAME = 'price_usdt') = 0,
  'ALTER TABLE nx_device ADD COLUMN price_usdt DECIMAL(18,2) NULL AFTER status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'nexion' AND TABLE_NAME = 'nx_device' AND COLUMN_NAME = 'estimated_daily_usdt') = 0,
  'ALTER TABLE nx_device ADD COLUMN estimated_daily_usdt DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER hashrate',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'nexion' AND TABLE_NAME = 'nx_device' AND COLUMN_NAME = 'stock') = 0,
  'ALTER TABLE nx_device ADD COLUMN stock INT NOT NULL DEFAULT 0 AFTER daily_nex',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'nexion' AND TABLE_NAME = 'nx_device' AND COLUMN_NAME = 'cover_url') = 0,
  'ALTER TABLE nx_device ADD COLUMN cover_url VARCHAR(512) NULL AFTER stock',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'nexion' AND TABLE_NAME = 'nx_device' AND INDEX_NAME = 'idx_device_sale') = 0,
  'ALTER TABLE nx_device ADD INDEX idx_device_sale (status)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

DROP TABLE IF EXISTS nx_product;

CREATE TABLE IF NOT EXISTS nx_user_device (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  device_id BIGINT NOT NULL,
  instance_no VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  daily_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  daily_nex DECIMAL(18,6) NOT NULL DEFAULT 0,
  last_seen_at DATETIME NULL,
  activated_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_device_instance_no (instance_no),
  KEY idx_user_device_user (user_id),
  KEY idx_user_device_device (device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_compute_receipt (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  user_device_id BIGINT NULL,
  receipt_no VARCHAR(64) NOT NULL,
  task_type VARCHAR(64) NOT NULL,
  client_name VARCHAR(128) NOT NULL,
  reward_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  reward_nex DECIMAL(18,6) NOT NULL DEFAULT 0,
  proof_hash VARCHAR(128) NOT NULL,
  completed_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_receipt_no (receipt_no),
  KEY idx_receipt_user_time (user_id, completed_at)
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

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'nexion' AND TABLE_NAME = 'nx_v_rank_config' AND COLUMN_NAME = 'required_downline_rank') = 0,
  'ALTER TABLE nx_v_rank_config ADD COLUMN required_downline_rank VARCHAR(16) NULL AFTER team_volume_usd',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'nexion' AND TABLE_NAME = 'nx_v_rank_config' AND COLUMN_NAME = 'required_downline_count') = 0,
  'ALTER TABLE nx_v_rank_config ADD COLUMN required_downline_count INT NOT NULL DEFAULT 0 AFTER required_downline_rank',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

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

CREATE TABLE IF NOT EXISTS nx_user_wallet (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  usdt_available DECIMAL(18,6) NOT NULL DEFAULT 0,
  nex_available DECIMAL(18,6) NOT NULL DEFAULT 0,
  pending_withdraw DECIMAL(18,6) NOT NULL DEFAULT 0,
  lifetime_earned DECIMAL(18,6) NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_wallet_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_notification (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  type VARCHAR(32) NOT NULL,
  title VARCHAR(128) NOT NULL,
  body VARCHAR(512) NOT NULL,
  read_flag TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_notification_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- F3 binary settlement: explicit immutable leg ownership, paid-order facts and a daily mutex.
USE nexion;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS nx_binary_leg_assignment (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  owner_user_id BIGINT NOT NULL,
  member_user_id BIGINT NOT NULL,
  leg CHAR(1) NOT NULL,
  assigned_by_admin_id BIGINT NOT NULL,
  assigned_by VARCHAR(64) NOT NULL,
  assigned_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_binary_leg_owner_member (owner_user_id,member_user_id),
  KEY idx_binary_leg_owner_leg (owner_user_id,leg,member_user_id),
  CONSTRAINT chk_binary_leg CHECK (leg IN ('A','B')),
  CONSTRAINT chk_binary_leg_not_self CHECK (owner_user_id <> member_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_binary_paid_order_volume (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_no VARCHAR(96) NOT NULL,
  owner_user_id BIGINT NOT NULL,
  order_user_id BIGINT NOT NULL,
  root_member_user_id BIGINT NOT NULL,
  leg CHAR(1) NOT NULL,
  amount_usdt DECIMAL(18,6) NOT NULL,
  paid_at DATETIME NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  void_reason VARCHAR(128) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_binary_paid_order_owner (order_no,owner_user_id),
  KEY idx_binary_paid_owner_month (owner_user_id,paid_at,leg),
  CONSTRAINT chk_binary_paid_leg CHECK (leg IN ('A','B')),
  CONSTRAINT chk_binary_paid_amount CHECK (amount_usdt > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_binary_settlement_mutex (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  owner_user_id BIGINT NOT NULL,
  settlement_date DATE NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_binary_settlement_mutex (owner_user_id,settlement_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_config_item
  (config_key,config_value,value_type,config_group,visibility,remark,status,created_at,updated_at,is_deleted)
VALUES
  ('team.ui.F.binary.paused','false','BOOLEAN','team','ADMIN',
   'F3 settlement kill switch; missing or malformed values fail closed',1,NOW(),NOW(),0)
ON DUPLICATE KEY UPDATE
  value_type='BOOLEAN',config_group='team',visibility='ADMIN',status=1,updated_at=NOW(),is_deleted=0;

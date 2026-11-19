SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS nx_binary_platform_period (
  settlement_date DATE PRIMARY KEY,
  period_start DATE NOT NULL,
  period_days SMALLINT NOT NULL,
  platform_cap_pct DECIMAL(13,10) NOT NULL,
  paid_sales_usdt DECIMAL(30,6) NOT NULL,
  cap_amount_usdt DECIMAL(30,6) NOT NULL,
  raw_total_usdt DECIMAL(30,6) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_binary_platform_allocation (
  settlement_date DATE NOT NULL,
  user_id BIGINT NOT NULL,
  raw_amount_usdt DECIMAL(18,6) NOT NULL,
  allocated_usdt DECIMAL(18,6) NOT NULL,
  settled_raw_amount_usdt DECIMAL(18,6) NULL,
  settlement_mode VARCHAR(16) NULL,
  PRIMARY KEY (settlement_date,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_binary_auto_settlement_batch (
  settlement_date DATE PRIMARY KEY,
  status VARCHAR(16) NOT NULL,
  last_owner_id BIGINT NOT NULL DEFAULT 0,
  last_error VARCHAR(128) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_binary_auto_settlement_retry (
  settlement_date DATE NOT NULL,
  owner_user_id BIGINT NOT NULL,
  PRIMARY KEY (settlement_date,owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_config_item
  (config_key,config_value,value_type,config_group,visibility,remark,status,created_at,updated_at,is_deleted)
VALUES
  ('team.ui.F.binary.autoSettle','on','TEXT','team','ADMIN','F3 自动派发',1,NOW(),NOW(),0),
  ('team.ui.F.binary.platformCapPct','2%','TEXT','team','ADMIN','F3 全平台封顶比例',1,NOW(),NOW(),0),
  ('team.binary.autoEnabledAtUtc',DATE_FORMAT(UTC_TIMESTAMP(),'%Y-%m-%dT%H:%i:%sZ'),'TEXT','team','ADMIN','F3 自动派发生效时刻',1,NOW(),NOW(),0)
ON DUPLICATE KEY UPDATE config_key=VALUES(config_key);

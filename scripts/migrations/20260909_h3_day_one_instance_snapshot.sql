-- H3 Day-One: immutable registration-time instance definition, reward and binding snapshots.
-- Deliberately create-only: legacy nx_user_mission rows cannot prove their original definition.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS nx_growth_day_one_instance (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  instance_key VARCHAR(48) NOT NULL,
  snapshot_status VARCHAR(16) NOT NULL,
  entered_at DATETIME NOT NULL,
  eligibility_hours INT NOT NULL,
  full_reward_hours INT NOT NULL,
  eligible_until DATETIME NOT NULL,
  tri_reward VARCHAR(96) NULL,
  quest_bonus_multiplier DECIMAL(18,6) NULL,
  rhythm_month INT NULL,
  required_task_count INT NOT NULL,
  definition_hash CHAR(64) NOT NULL,
  snapshot_source VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_growth_day_one_instance_user_key (user_id,instance_key),
  KEY idx_growth_day_one_instance_user_window (user_id,eligible_until,is_deleted),
  KEY idx_growth_day_one_instance_window (snapshot_status,entered_at,eligible_until,is_deleted),
  CONSTRAINT chk_growth_day_one_instance_status CHECK (snapshot_status IN ('SNAPSHOT','EMPTY')),
  CONSTRAINT chk_growth_day_one_instance_window CHECK (eligibility_hours BETWEEN 24 AND 720
    AND full_reward_hours BETWEEN 1 AND eligibility_hours AND eligible_until > entered_at),
  CONSTRAINT chk_growth_day_one_instance_snapshot_shape CHECK (
    (snapshot_status='EMPTY' AND required_task_count=0 AND tri_reward IS NULL
      AND quest_bonus_multiplier IS NULL AND rhythm_month IS NULL)
    OR (snapshot_status='SNAPSHOT' AND required_task_count>0 AND tri_reward IS NOT NULL
      AND quest_bonus_multiplier > 0 AND rhythm_month > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_growth_day_one_instance_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  instance_id BIGINT NOT NULL,
  source_mission_id BIGINT NOT NULL,
  quest_code VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  category VARCHAR(32) NOT NULL,
  action_route VARCHAR(255) NOT NULL,
  reward_points INT NOT NULL DEFAULT 0,
  ordinal INT NOT NULL,
  completion_mode VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_growth_day_one_item_code (instance_id,quest_code),
  UNIQUE KEY uk_growth_day_one_item_source (instance_id,source_mission_id),
  KEY idx_growth_day_one_item_instance (instance_id,ordinal,is_deleted),
  CONSTRAINT chk_growth_day_one_item_ordinal CHECK (ordinal > 0),
  CONSTRAINT chk_growth_day_one_item_reward CHECK (reward_points >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_growth_day_one_instance_binding (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  instance_id BIGINT NOT NULL,
  source_mission_id BIGINT NOT NULL,
  source_binding_id BIGINT NOT NULL,
  binding_code VARCHAR(48) NOT NULL,
  producer VARCHAR(32) NOT NULL,
  event_type VARCHAR(128) NOT NULL,
  user_id_field VARCHAR(64) NOT NULL,
  rule_json JSON NOT NULL,
  binding_hash CHAR(64) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_growth_day_one_binding_code (instance_id,binding_code),
  UNIQUE KEY uk_growth_day_one_binding_source (instance_id,source_binding_id),
  KEY idx_growth_day_one_binding_event (event_type,producer,is_deleted),
  KEY idx_growth_day_one_binding_item (instance_id,source_mission_id,is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

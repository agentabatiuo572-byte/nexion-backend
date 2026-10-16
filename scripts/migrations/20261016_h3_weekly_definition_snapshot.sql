-- H3 weekly publication snapshot. Create-only; no historical user dispatch is fabricated.
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS nx_growth_weekly_definition (
  instance_key VARCHAR(48) PRIMARY KEY,
  eligible_from DATETIME NOT NULL,
  eligible_until DATETIME NOT NULL,
  quest_bonus_multiplier DECIMAL(18,6) NOT NULL,
  rhythm_month INT NOT NULL,
  required_task_count INT NOT NULL,
  localized_content LONGTEXT NULL,
  snapshot_source VARCHAR(32) NOT NULL DEFAULT 'FIRST_OBSERVED_V1',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT chk_weekly_definition_window CHECK (eligible_until > eligible_from),
  CONSTRAINT chk_weekly_definition_reward CHECK (quest_bonus_multiplier > 0 AND rhythm_month > 0 AND required_task_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_growth_weekly_definition_item (
  instance_key VARCHAR(48) NOT NULL,
  source_mission_id BIGINT NOT NULL,
  quest_code VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  layer VARCHAR(32) NOT NULL,
  category VARCHAR(32) NOT NULL,
  action_route VARCHAR(255) NOT NULL,
  reward_points INT NOT NULL,
  ordinal INT NOT NULL,
  PRIMARY KEY(instance_key,source_mission_id),
  UNIQUE KEY uk_weekly_definition_code(instance_key,quest_code),
  CONSTRAINT chk_weekly_definition_item_reward CHECK (reward_points>=0),
  CONSTRAINT fk_weekly_definition_item_header FOREIGN KEY(instance_key) REFERENCES nx_growth_weekly_definition(instance_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_growth_weekly_definition_binding (
  instance_key VARCHAR(48) NOT NULL,
  source_mission_id BIGINT NOT NULL,
  source_binding_id BIGINT NOT NULL,
  binding_code VARCHAR(64) NOT NULL,
  producer VARCHAR(32) NOT NULL,
  event_type VARCHAR(128) NOT NULL,
  user_id_field VARCHAR(64) NOT NULL,
  PRIMARY KEY(instance_key,source_binding_id),
  UNIQUE KEY uk_weekly_definition_binding_code(instance_key,binding_code),
  KEY idx_weekly_definition_event(instance_key,event_type),
  CONSTRAINT fk_weekly_definition_binding_item FOREIGN KEY(instance_key,source_mission_id)
    REFERENCES nx_growth_weekly_definition_item(instance_key,source_mission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

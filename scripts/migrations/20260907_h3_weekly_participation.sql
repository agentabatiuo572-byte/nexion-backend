-- H3 weekly-participation evaluator.  This migration has no historical inserts:
-- only observations accepted after deployment are eligible.  Rollback after
-- reverting the evaluator: DROP TABLE nx_growth_weekly_participation_rollout;
-- DROP TABLE nx_growth_weekly_participation_threshold;
-- DROP TABLE nx_growth_weekly_participation_observation;

CREATE TABLE IF NOT EXISTS nx_growth_weekly_participation_observation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  instance_key VARCHAR(48) NOT NULL,
  observation_type VARCHAR(64) NOT NULL,
  subject_key VARCHAR(96) NOT NULL,
  observed_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_h3_weekly_participation_subject
    (user_id,instance_key,observation_type,subject_key),
  KEY idx_h3_weekly_participation_count
    (user_id,instance_key,observation_type,is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS nx_growth_weekly_participation_threshold (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  instance_key VARCHAR(48) NOT NULL,
  threshold_event_type VARCHAR(96) NOT NULL,
  emitted_at DATETIME NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_h3_weekly_participation_threshold
    (user_id,instance_key,threshold_event_type),
  KEY idx_h3_weekly_participation_pending
    (user_id,instance_key,emitted_at,is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- The compute evaluator must not drain pending pre-deployment task.completed
-- records into a new weekly mission. This single rollout fence is immutable.
CREATE TABLE IF NOT EXISTS nx_growth_weekly_participation_rollout (
  id TINYINT NOT NULL,
  effective_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO nx_growth_weekly_participation_rollout
  (id,effective_at,created_at,updated_at)
VALUES (1,
        UTC_TIMESTAMP() + INTERVAL 8 HOUR,
        UTC_TIMESTAMP() + INTERVAL 8 HOUR,
        UTC_TIMESTAMP() + INTERVAL 8 HOUR);

-- H3 weekly exchange/referral completion is prospective only. Source facts at
-- or after this immutable fence may qualify; older facts are never backfilled.
CREATE TABLE IF NOT EXISTS nx_growth_weekly_exchange_referral_rollout (
  id TINYINT NOT NULL,
  effective_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO nx_growth_weekly_exchange_referral_rollout
  (id,effective_at,created_at,updated_at)
VALUES (1,
        UTC_TIMESTAMP() + INTERVAL 8 HOUR,
        UTC_TIMESTAMP() + INTERVAL 8 HOUR,
        UTC_TIMESTAMP() + INTERVAL 8 HOUR);

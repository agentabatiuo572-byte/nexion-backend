-- H3 task engine closure: durable admin mutex and fail-closed homepage promo seed.
-- Safe to rerun.

CREATE TABLE IF NOT EXISTS nx_admin_operation_mutex (
  lock_key VARCHAR(64) PRIMARY KEY,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO nx_admin_operation_mutex (lock_key) VALUES ('H3_CONFIG');

INSERT INTO nx_growth_promo_banner
  (banner_code, base_reward, multiplier, countdown_days, countdown_hours,
   target_device, target_daily, status, sort_order, created_at, updated_at, is_deleted)
VALUES
  ('HOME_WEEKLY_UPSELL', '800', '1.5', 4, 12, 'StellarBox Pro', '1.50',
   'paused', 10, NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE banner_code = VALUES(banner_code);

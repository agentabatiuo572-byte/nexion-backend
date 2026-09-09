-- One receipt is inserted in the same transaction as the Day One source outbox event.
CREATE TABLE IF NOT EXISTS nx_growth_day_one_page_observation_receipt (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  instance_key VARCHAR(64) NOT NULL,
  surface VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_h3_day_one_page_observation_receipt (user_id, instance_key, surface)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

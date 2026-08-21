-- Permanent E2 task-price history used by the App compute-market chart.
-- Development sample rows are inserted by the dev-profile initializer, never by this schema migration.
CREATE TABLE IF NOT EXISTS nx_admin_device_task_price_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id VARCHAR(64) NOT NULL,
  task_class VARCHAR(64) NOT NULL,
  price DECIMAL(18,8) NOT NULL,
  unit_text VARCHAR(32) NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  sample_key VARCHAR(128) DEFAULT NULL,
  observed_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_admin_task_price_history_sample (task_id,sample_key),
  KEY idx_admin_task_price_history_observed_at (observed_at),
  KEY idx_admin_task_price_history_task_time (task_id,observed_at),
  KEY idx_admin_task_price_history_class_time (task_class,observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

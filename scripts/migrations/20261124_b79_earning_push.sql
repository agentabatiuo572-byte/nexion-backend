-- B79-BE-04: four native-only earning reminder records and delivery expiry/thread metadata.
CREATE TABLE IF NOT EXISTS nx_earning_push_task (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  task_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id BIGINT NOT NULL,
  kind VARCHAR(24) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  reason VARCHAR(64) NULL,
  receipt_no VARCHAR(96) NULL,
  window_start DATETIME(6) NULL,
  window_end DATETIME(6) NULL,
  business_zone VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  event_count INT NOT NULL DEFAULT 0,
  device_count INT NOT NULL DEFAULT 0,
  usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  uvt DECIMAL(18,6) NOT NULL DEFAULT 0,
  title VARCHAR(160) NULL,
  body VARCHAR(512) NULL,
  cta_href VARCHAR(255) NULL,
  notification_id BIGINT NULL,
  expires_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_earning_push_task_key (task_key),
  KEY ix_earning_push_task_user (user_id, created_at),
  KEY ix_earning_push_task_window (user_id, kind, window_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_native_push_delivery' AND COLUMN_NAME='expires_at')=0,
  'ALTER TABLE nx_native_push_delivery ADD COLUMN expires_at DATETIME(6) NULL AFTER next_attempt_at',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_native_push_delivery' AND COLUMN_NAME='thread_id')=0,
  'ALTER TABLE nx_native_push_delivery ADD COLUMN thread_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER expires_at',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

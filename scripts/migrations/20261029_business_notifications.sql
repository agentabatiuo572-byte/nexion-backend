-- Apply once before enabling NEXION_BUSINESS_NOTIFICATIONS_ENABLED. No historical backfill.
CREATE TABLE IF NOT EXISTS nx_business_notification_control (
  id TINYINT NOT NULL PRIMARY KEY,
  baseline_at DATETIME(6) NOT NULL,
  baseline_utc_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Core DATETIME business facts are UTC+08 and second precision, independent of migration session timezone.
INSERT IGNORE INTO nx_business_notification_control VALUES(1,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),UTC_TIMESTAMP(6));

-- Separate permanent receipts survive station-message retention/deletion.
CREATE TABLE IF NOT EXISTS nx_business_notification_fact (
  fact_key VARCHAR(128) NOT NULL PRIMARY KEY,
  notification_biz_no VARCHAR(128) NOT NULL,
  user_id BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_business_notification_user(user_id,created_at),
  KEY idx_business_notification_notification(notification_biz_no,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_business_device_alert_state (
  device_id BIGINT NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  abnormal_since DATETIME(6) NULL,
  cycle BIGINT NOT NULL DEFAULT 0,
  alerted TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

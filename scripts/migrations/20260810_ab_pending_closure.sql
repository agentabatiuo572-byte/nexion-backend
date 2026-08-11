-- A/B pending capability closure: durable parameters, A4 lifecycle, B5 disposition/delivery.
INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, created_at, updated_at, is_deleted)
VALUES
  ('platform.global_rate_limit_per_minute', '6000', 'NUMBER', 'admin_platform_param', 'ADMIN',
   'A3 global API request ceiling consumed by PlatformGlobalRateLimitFilter', 1, NOW(), NOW(), 0),
  ('withdrawal.strong_review_threshold_usdt', '1000', 'NUMBER', 'admin_platform_param', 'ADMIN',
   'A3 strong-review threshold consumed by D2 AppWithdrawalService', 1, NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE status=1, is_deleted=0, updated_at=updated_at;

CREATE TABLE IF NOT EXISTS nx_admin_event_lifecycle (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_name VARCHAR(160) NOT NULL,
  lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'new',
  version BIGINT NOT NULL DEFAULT 0,
  changed_by VARCHAR(128) NULL,
  reason VARCHAR(255) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_event_lifecycle_name (event_name),
  KEY idx_admin_event_lifecycle_state (lifecycle_state, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO nx_admin_event_lifecycle
  (event_name, lifecycle_state, version, changed_by, reason, created_at, updated_at, is_deleted)
SELECT event_name,
       CASE WHEN status='RETIRED' THEN 'disabled' ELSE 'full' END,
       0, 'migration', 'initialize lifecycle for existing schemas', NOW(), NOW(), 0
  FROM nx_event_schema_registry
 WHERE is_deleted=0;

CREATE TABLE IF NOT EXISTS nx_admin_risk_signal_disposition (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  signal_no VARCHAR(128) NOT NULL,
  handling_status VARCHAR(32) NOT NULL DEFAULT 'open',
  version BIGINT NOT NULL DEFAULT 0,
  handled_by VARCHAR(128) NULL,
  reason VARCHAR(255) NULL,
  handled_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_risk_signal_disposition (signal_no),
  KEY idx_admin_risk_signal_status (handling_status, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_admin_risk_alert_delivery (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  signal_no VARCHAR(128) NOT NULL,
  subscriber VARCHAR(128) NOT NULL,
  channel VARCHAR(32) NOT NULL,
  delivery_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME NULL,
  last_error VARCHAR(255) NULL,
  receipt_source VARCHAR(32) NULL,
  provider_receipt VARCHAR(255) NULL,
  processing_started_at DATETIME NULL,
  read_at DATETIME NULL,
  acknowledged_at DATETIME NULL,
  delivered_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_risk_alert_delivery (signal_no, subscriber, channel),
  KEY idx_admin_risk_alert_delivery_due (delivery_status, next_retry_at, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

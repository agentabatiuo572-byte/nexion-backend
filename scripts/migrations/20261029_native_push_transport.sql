-- Apply before enabling native-push.scheduler-enabled. Times follow nx_user_session (UTC+08).
CREATE TABLE IF NOT EXISTS nx_native_push_installation (
  installation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  client_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  binding_token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id BIGINT NOT NULL,
  session_chain_id VARCHAR(128) NOT NULL,
  platform VARCHAR(16) NOT NULL,
  permission VARCHAR(16) NOT NULL,
  app_version VARCHAR(64) NOT NULL,
  binding_epoch BIGINT NOT NULL DEFAULT 1,
  active TINYINT NOT NULL DEFAULT 1,
  bound_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_native_push_cid (client_id),
  KEY ix_native_push_user (user_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_native_push_delivery (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  notification_id BIGINT NOT NULL,
  installation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id BIGINT NOT NULL,
  binding_epoch BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NOT NULL,
  lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  lease_until DATETIME(6) NULL,
  provider_task_id VARCHAR(128) NULL,
  last_error VARCHAR(64) NULL,
  accepted_at DATETIME(6) NULL,
  opened_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_native_push_notification_installation (notification_id, installation_id),
  KEY ix_native_push_ready (status, next_attempt_at, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_native_push_cursor (
  id INT NOT NULL PRIMARY KEY,
  notification_id BIGINT NOT NULL,
  updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Idempotent cutover: rerunning migration must not reset the cursor or replay history.
INSERT IGNORE INTO nx_native_push_cursor (id,notification_id,updated_at)
SELECT 1,COALESCE(MAX(id),0),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 8 HOUR) FROM nx_notification;

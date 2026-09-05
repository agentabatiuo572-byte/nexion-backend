-- Preserve the former calendar-date counter conservatively, then use retained send events
-- for an exact trailing 24-hour OTP policy window. Safe to run with the application stopped.
CREATE TABLE IF NOT EXISTS nx_user_otp_send_guard (
  login_key CHAR(64) PRIMARY KEY,
  last_sent_at DATETIME(3) DEFAULT NULL,
  window_started_at DATETIME(3) NOT NULL,
  window_send_count INT NOT NULL DEFAULT 0,
  day_started_at DATETIME(3) NOT NULL,
  day_send_count INT NOT NULL DEFAULT 0,
  legacy_window_until DATETIME(3) DEFAULT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_user_otp_send_guard_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE nx_user_otp_send_guard
  ADD COLUMN IF NOT EXISTS legacy_window_until DATETIME(3) NULL AFTER day_send_count;

-- A DATE row has no trustworthy send time. Retain its count until the latest possible
-- event time (end of that date plus 24 hours) rather than granting an unproven new quota.
UPDATE nx_user_otp_send_guard
   SET legacy_window_until=DATE_ADD(day_started_at, INTERVAL 2 DAY)
 WHERE legacy_window_until IS NULL AND day_send_count>0;

ALTER TABLE nx_user_otp_send_guard
  MODIFY COLUMN day_started_at DATETIME(3) NOT NULL;

CREATE TABLE IF NOT EXISTS nx_user_otp_send_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  login_key CHAR(64) NOT NULL,
  sent_at DATETIME(3) NOT NULL,
  KEY idx_user_otp_send_event_key_time (login_key,sent_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

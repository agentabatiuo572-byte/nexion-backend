-- Account login phone-number change history and short-lived dual-number challenge.
-- No handset/device replacement data is read or modified.
CREATE TABLE IF NOT EXISTS nx_user_phone_number_change (
  change_no VARCHAR(64) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  old_country_code VARCHAR(8) NULL,
  old_phone VARCHAR(32) NULL,
  new_country_code VARCHAR(8) NULL,
  new_phone VARCHAR(32) NULL,
  old_code_hash CHAR(64) NOT NULL,
  new_code_hash CHAR(64) NOT NULL,
  expires_at DATETIME NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  completed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_phone_number_change_user_completed (user_id, completed_at),
  KEY idx_phone_number_change_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS nx_user_otp_challenge (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  challenge_no VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  code_hash CHAR(64) NOT NULL,
  expires_at DATETIME NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  consumed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_otp_challenge_no (challenge_no),
  KEY idx_user_otp_active (user_id, expires_at, consumed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

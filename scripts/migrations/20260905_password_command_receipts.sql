-- No credential, request body, or password fingerprint is retained.
CREATE TABLE IF NOT EXISTS nx_user_password_command (
    user_id BIGINT NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    command_key VARCHAR(128) NOT NULL,
    changed_at DATETIME NOT NULL,
    revoked_count INT NOT NULL,
  PRIMARY KEY (user_id, command_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

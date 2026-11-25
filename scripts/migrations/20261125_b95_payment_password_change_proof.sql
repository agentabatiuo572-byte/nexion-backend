CREATE TABLE IF NOT EXISTS nx_payment_password_change_proof (
  user_id BIGINT NOT NULL,
  session_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  proof_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  credential_version BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,
  consumed_at BIGINT NULL,
  PRIMARY KEY (user_id, session_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

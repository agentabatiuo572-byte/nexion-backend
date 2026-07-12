USE nexion;

CREATE TABLE IF NOT EXISTS nx_disclosure_read_token (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  token_hash CHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  jurisdiction_code VARCHAR(32) NOT NULL,
  version_label VARCHAR(32) NOT NULL,
  expires_at DATETIME NOT NULL,
  consumed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_disclosure_read_token_hash (token_hash),
  UNIQUE KEY uk_disclosure_read_token_binding (user_id, jurisdiction_code, version_label),
  KEY idx_disclosure_read_token_expiry (expires_at, consumed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_disclosure_gate_block_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  jurisdiction_code VARCHAR(32) NOT NULL,
  action_key VARCHAR(64) NOT NULL,
  business_flow_id VARCHAR(128) NOT NULL,
  blocked_at DATETIME NOT NULL,
  UNIQUE KEY uk_disclosure_gate_block_flow (user_id, action_key, business_flow_id),
  KEY idx_disclosure_gate_block_jurisdiction (jurisdiction_code, blocked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

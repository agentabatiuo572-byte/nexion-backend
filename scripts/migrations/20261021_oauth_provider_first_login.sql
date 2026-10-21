-- Provider identities can create an account before a phone is verified.
-- Existing phone values remain unchanged; the unique phone index permits multiple NULL pairs.
ALTER TABLE nx_user MODIFY country_code VARCHAR(8) NULL, MODIFY phone VARCHAR(32) NULL;

CREATE TABLE IF NOT EXISTS nx_oauth_provider_flow (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  proof_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  receipt_hash VARCHAR(64) NULL,
  state VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  provider VARCHAR(16) NOT NULL, nonce VARCHAR(64) NOT NULL, verifier VARCHAR(64) NOT NULL,
  browser_hash VARCHAR(64) NULL, link_user_id BIGINT NULL, sponsor_code VARCHAR(32) NULL,
  status VARCHAR(16) NOT NULL, subject VARCHAR(191) NULL, display_name VARCHAR(128) NULL,
  client_hash VARCHAR(64) NOT NULL, expires_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_oauth_flow_state(state), KEY idx_oauth_flow_expiry(expires_at),
  KEY idx_oauth_flow_client(client_hash,expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @oauth_flow_ddl = IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_oauth_provider_flow' AND column_name='sponsor_code')=0,
  'ALTER TABLE nx_oauth_provider_flow ADD COLUMN sponsor_code VARCHAR(32) NULL', 'SELECT 1');
PREPARE oauth_flow_stmt FROM @oauth_flow_ddl; EXECUTE oauth_flow_stmt; DEALLOCATE PREPARE oauth_flow_stmt;

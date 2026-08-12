-- M-support acceptance sandbox.  These facts are deliberately isolated from
-- nx_support_ticket / nx_conversation and must never be joined by production
-- inbox, notification, audit, or scheduler projections.
CREATE TABLE IF NOT EXISTS nx_support_acceptance_sandbox_run (
  run_id VARCHAR(80) NOT NULL,
  account_id BIGINT NOT NULL,
  source VARCHAR(16) NOT NULL,
  source_environment VARCHAR(16) NOT NULL,
  status VARCHAR(24) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (run_id, account_id),
  KEY idx_support_acceptance_run_account (account_id, updated_at),
  CONSTRAINT chk_support_acceptance_run_source
    CHECK (source = 'mock' AND source_environment = 'SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_support_acceptance_sandbox_ticket (
  id BIGINT NOT NULL AUTO_INCREMENT,
  ticket_no VARCHAR(80) NOT NULL,
  run_id VARCHAR(80) NOT NULL,
  account_id BIGINT NOT NULL,
  category VARCHAR(32) NOT NULL,
  priority VARCHAR(16) NOT NULL,
  title VARCHAR(160) NOT NULL,
  status VARCHAR(24) NOT NULL,
  owner_agent_id VARCHAR(80),
  owner_agent_name VARCHAR(120) NOT NULL,
  last_message_at DATETIME NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  source VARCHAR(16) NOT NULL,
  source_environment VARCHAR(16) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_support_acceptance_ticket_no (ticket_no),
  KEY idx_support_acceptance_ticket_account (account_id, run_id, updated_at),
  KEY idx_support_acceptance_ticket_run_status (run_id, status, updated_at),
  CONSTRAINT chk_support_acceptance_ticket_source
    CHECK (source = 'mock' AND source_environment = 'SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_support_acceptance_sandbox_ticket_message (
  id BIGINT NOT NULL AUTO_INCREMENT,
  ticket_no VARCHAR(80) NOT NULL,
  run_id VARCHAR(80) NOT NULL,
  account_id BIGINT NOT NULL,
  sender_type VARCHAR(16) NOT NULL,
  sender_name VARCHAR(120) NOT NULL,
  content VARCHAR(2000) NOT NULL,
  client_message_id VARCHAR(128),
  source VARCHAR(16) NOT NULL,
  source_environment VARCHAR(16) NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_support_acceptance_ticket_message_client (ticket_no, client_message_id),
  KEY idx_support_acceptance_ticket_message_account (account_id, run_id, ticket_no, id),
  CONSTRAINT chk_support_acceptance_ticket_message_source
    CHECK (source = 'mock' AND source_environment = 'SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_support_acceptance_sandbox_conversation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  conversation_no VARCHAR(80) NOT NULL,
  run_id VARCHAR(80) NOT NULL,
  account_id BIGINT NOT NULL,
  conversation_type VARCHAR(24) NOT NULL,
  status VARCHAR(24) NOT NULL,
  owner_agent_id VARCHAR(80),
  owner_agent_name VARCHAR(120) NOT NULL,
  unread_count INT NOT NULL DEFAULT 0,
  last_message VARCHAR(2000) NOT NULL,
  last_message_at DATETIME NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  source VARCHAR(16) NOT NULL,
  source_environment VARCHAR(16) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_support_acceptance_conversation_no (conversation_no),
  KEY idx_support_acceptance_conversation_account (account_id, run_id, updated_at),
  KEY idx_support_acceptance_conversation_run_status (run_id, status, updated_at),
  CONSTRAINT chk_support_acceptance_conversation_source
    CHECK (source = 'mock' AND source_environment = 'SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_support_acceptance_sandbox_conversation_message (
  id BIGINT NOT NULL AUTO_INCREMENT,
  conversation_no VARCHAR(80) NOT NULL,
  run_id VARCHAR(80) NOT NULL,
  account_id BIGINT NOT NULL,
  sender_type VARCHAR(16) NOT NULL,
  sender_name VARCHAR(120) NOT NULL,
  content VARCHAR(2000) NOT NULL,
  client_message_id VARCHAR(128),
  source VARCHAR(16) NOT NULL,
  source_environment VARCHAR(16) NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_support_acceptance_conversation_message_client (conversation_no, client_message_id),
  KEY idx_support_acceptance_conversation_message_account (account_id, run_id, conversation_no, id),
  CONSTRAINT chk_support_acceptance_conversation_message_source
    CHECK (source = 'mock' AND source_environment = 'SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_support_acceptance_sandbox_receipt (
  message_id BIGINT NOT NULL,
  conversation_no VARCHAR(80) NOT NULL,
  run_id VARCHAR(80) NOT NULL,
  account_id BIGINT NOT NULL,
  receipt_status VARCHAR(16) NOT NULL,
  read_by VARCHAR(120),
  read_at DATETIME,
  source VARCHAR(16) NOT NULL,
  source_environment VARCHAR(16) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (message_id),
  KEY idx_support_acceptance_receipt_account (account_id, run_id, conversation_no, receipt_status),
  CONSTRAINT chk_support_acceptance_receipt_source
    CHECK (source = 'mock' AND source_environment = 'SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_support_acceptance_sandbox_idempotency (
  command_key VARCHAR(160) NOT NULL,
  run_id VARCHAR(80) NOT NULL,
  account_id BIGINT NOT NULL,
  command_type VARCHAR(48) NOT NULL,
  business_key VARCHAR(128) NOT NULL,
  reason VARCHAR(255) NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  result_json JSON NOT NULL,
  result_type VARCHAR(32) NOT NULL,
  result_id VARCHAR(128) NOT NULL,
  status VARCHAR(24) NOT NULL,
  source VARCHAR(16) NOT NULL,
  source_environment VARCHAR(16) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (run_id, account_id, command_key),
  KEY idx_support_acceptance_command_business (account_id, run_id, command_type, business_key),
  KEY idx_support_acceptance_command_account (account_id, run_id, updated_at),
  CONSTRAINT chk_support_acceptance_command_source
    CHECK (source = 'mock' AND source_environment = 'SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Existing acceptance baselines used utf8mb4_unicode_ci.  Observation joins
-- compare sandbox identifiers with the production 0900 baseline, so normalize
-- all seven isolated tables before any observer query can run.
SET @support_acceptance_collation_sql := IF((SELECT table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_support_acceptance_sandbox_run') <> 'utf8mb4_0900_ai_ci', 'ALTER TABLE nx_support_acceptance_sandbox_run CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1'); PREPARE support_acceptance_ddl FROM @support_acceptance_collation_sql; EXECUTE support_acceptance_ddl; DEALLOCATE PREPARE support_acceptance_ddl;
SET @support_acceptance_collation_sql := IF((SELECT table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_support_acceptance_sandbox_ticket') <> 'utf8mb4_0900_ai_ci', 'ALTER TABLE nx_support_acceptance_sandbox_ticket CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1'); PREPARE support_acceptance_ddl FROM @support_acceptance_collation_sql; EXECUTE support_acceptance_ddl; DEALLOCATE PREPARE support_acceptance_ddl;
SET @support_acceptance_collation_sql := IF((SELECT table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_support_acceptance_sandbox_ticket_message') <> 'utf8mb4_0900_ai_ci', 'ALTER TABLE nx_support_acceptance_sandbox_ticket_message CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1'); PREPARE support_acceptance_ddl FROM @support_acceptance_collation_sql; EXECUTE support_acceptance_ddl; DEALLOCATE PREPARE support_acceptance_ddl;
SET @support_acceptance_collation_sql := IF((SELECT table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_support_acceptance_sandbox_conversation') <> 'utf8mb4_0900_ai_ci', 'ALTER TABLE nx_support_acceptance_sandbox_conversation CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1'); PREPARE support_acceptance_ddl FROM @support_acceptance_collation_sql; EXECUTE support_acceptance_ddl; DEALLOCATE PREPARE support_acceptance_ddl;
SET @support_acceptance_collation_sql := IF((SELECT table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_support_acceptance_sandbox_conversation_message') <> 'utf8mb4_0900_ai_ci', 'ALTER TABLE nx_support_acceptance_sandbox_conversation_message CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1'); PREPARE support_acceptance_ddl FROM @support_acceptance_collation_sql; EXECUTE support_acceptance_ddl; DEALLOCATE PREPARE support_acceptance_ddl;
SET @support_acceptance_collation_sql := IF((SELECT table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_support_acceptance_sandbox_receipt') <> 'utf8mb4_0900_ai_ci', 'ALTER TABLE nx_support_acceptance_sandbox_receipt CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1'); PREPARE support_acceptance_ddl FROM @support_acceptance_collation_sql; EXECUTE support_acceptance_ddl; DEALLOCATE PREPARE support_acceptance_ddl;
SET @support_acceptance_collation_sql := IF((SELECT table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_support_acceptance_sandbox_idempotency') <> 'utf8mb4_0900_ai_ci', 'ALTER TABLE nx_support_acceptance_sandbox_idempotency CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1'); PREPARE support_acceptance_ddl FROM @support_acceptance_collation_sql; EXECUTE support_acceptance_ddl; DEALLOCATE PREPARE support_acceptance_ddl;

-- Forward-compatible and rerunnable upgrade from the earlier sandbox baseline.
-- Dynamic DDL only runs when information_schema proves the legacy object exists.
SET @support_acceptance_legacy_pk := (
  SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_support_acceptance_sandbox_idempotency'
    AND CONSTRAINT_NAME = 'PRIMARY' AND COLUMN_NAME = 'command_key' AND ORDINAL_POSITION = 1
);
SET @support_acceptance_pk_sql := IF(@support_acceptance_legacy_pk = 1,
  'ALTER TABLE nx_support_acceptance_sandbox_idempotency DROP PRIMARY KEY, ADD PRIMARY KEY (run_id, account_id, command_key)',
  'SELECT 1');
PREPARE support_acceptance_ddl FROM @support_acceptance_pk_sql;
EXECUTE support_acceptance_ddl;
DEALLOCATE PREPARE support_acceptance_ddl;

SET @support_acceptance_legacy_business_unique := (
  SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_support_acceptance_sandbox_idempotency'
    AND CONSTRAINT_NAME = 'uk_support_acceptance_command_business' AND CONSTRAINT_TYPE = 'UNIQUE'
);
SET @support_acceptance_business_drop_sql := IF(@support_acceptance_legacy_business_unique = 1,
  'ALTER TABLE nx_support_acceptance_sandbox_idempotency DROP INDEX uk_support_acceptance_command_business',
  'SELECT 1');
PREPARE support_acceptance_ddl FROM @support_acceptance_business_drop_sql;
EXECUTE support_acceptance_ddl;
DEALLOCATE PREPARE support_acceptance_ddl;

SET @support_acceptance_business_index := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_support_acceptance_sandbox_idempotency'
    AND INDEX_NAME = 'idx_support_acceptance_command_business'
);
SET @support_acceptance_business_add_sql := IF(@support_acceptance_business_index = 0,
  'ALTER TABLE nx_support_acceptance_sandbox_idempotency ADD KEY idx_support_acceptance_command_business (account_id, run_id, command_type, business_key)',
  'SELECT 1');
PREPARE support_acceptance_ddl FROM @support_acceptance_business_add_sql;
EXECUTE support_acceptance_ddl;
DEALLOCATE PREPARE support_acceptance_ddl;

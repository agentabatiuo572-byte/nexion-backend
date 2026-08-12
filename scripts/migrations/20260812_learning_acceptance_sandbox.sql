-- Acceptance-only learning facts. The controlled startup runner owns ordered execution.
-- Do not merge these tables with nx_learning_* or nx_earnings_release_entry.

CREATE TABLE IF NOT EXISTS nx_learning_sandbox_progress (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    course_id VARCHAR(96) NOT NULL,
    course_version VARCHAR(64) NOT NULL,
    progress_pct INT NOT NULL DEFAULT 0,
    attempts INT NOT NULL DEFAULT 0,
    last_score INT NOT NULL DEFAULT 0,
    started_at DATETIME NULL,
    completed_at DATETIME NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'mock',
    source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_sandbox_progress_run_user_course_version (run_id, user_id, course_id, course_version),
    KEY idx_learning_sandbox_progress_user_updated (user_id, updated_at),
    CONSTRAINT chk_learning_sandbox_progress_pct CHECK (progress_pct BETWEEN 0 AND 100),
    CONSTRAINT chk_learning_sandbox_progress_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_learning_sandbox_progress_source CHECK (source = 'mock' AND source_environment = 'SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_learning_sandbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    course_id VARCHAR(96) NOT NULL,
    course_version VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    event_payload JSON NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'mock',
    source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_sandbox_event_once (run_id, user_id, course_id, course_version, event_type),
    KEY idx_learning_sandbox_event_user_created (user_id, created_at),
    CONSTRAINT chk_learning_sandbox_event_source CHECK (source = 'mock' AND source_environment = 'SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_learning_sandbox_reward_ledger (
    id BIGINT NOT NULL AUTO_INCREMENT,
    reward_no VARCHAR(160) NOT NULL,
    user_id BIGINT NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    course_id VARCHAR(96) NOT NULL,
    course_version VARCHAR(64) NOT NULL,
    amount_nex DECIMAL(24,6) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'GRANTED',
    source VARCHAR(16) NOT NULL DEFAULT 'mock',
    source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_sandbox_reward_no (reward_no),
    UNIQUE KEY uk_learning_sandbox_reward_user_course_version (run_id, user_id, course_id, course_version),
    KEY idx_learning_sandbox_reward_user_created (user_id, created_at),
    CONSTRAINT chk_learning_sandbox_reward_amount CHECK (amount_nex > 0),
    CONSTRAINT chk_learning_sandbox_reward_source CHECK (source = 'mock' AND source_environment = 'SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_learning_sandbox_idempotency (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    course_id VARCHAR(96) NOT NULL,
    course_version VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    result_json JSON NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'mock',
    source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_sandbox_idempotency_attempt (run_id, user_id, course_id, course_version, idempotency_key),
    KEY idx_learning_sandbox_idempotency_user_updated (user_id, updated_at),
    CONSTRAINT chk_learning_sandbox_idempotency_status CHECK (status IN ('PENDING', 'COMPLETED')),
    CONSTRAINT chk_learning_sandbox_idempotency_source CHECK (source = 'mock' AND source_environment = 'SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_learning_sandbox_course (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL, course_id VARCHAR(96) NOT NULL, course_version VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', title_zh VARCHAR(255) NOT NULL, title_en VARCHAR(255) NOT NULL DEFAULT '', title_vi VARCHAR(255) NOT NULL DEFAULT '',
    body_zh TEXT NOT NULL, body_en TEXT NOT NULL, body_vi TEXT NOT NULL, category VARCHAR(64) NOT NULL, format VARCHAR(32) NOT NULL, level VARCHAR(32) NOT NULL,
    reward_nex DECIMAL(24,6) NOT NULL, duration VARCHAR(64) NOT NULL, featured TINYINT NOT NULL DEFAULT 0, quiz_json JSON NULL,
    pass_score INT NULL, retry_limit INT NULL, completion_condition VARCHAR(128) NOT NULL DEFAULT '', reward_event VARCHAR(96) NOT NULL DEFAULT '',
    source VARCHAR(16) NOT NULL DEFAULT 'mock', source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX', revision BIGINT NOT NULL DEFAULT 0,
    published_course_id VARCHAR(96) GENERATED ALWAYS AS (CASE WHEN status='PUBLISHED' AND is_deleted=0 THEN course_id ELSE NULL END) STORED,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, is_deleted TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_learning_sandbox_course_run_version (run_id,course_id,course_version),
    UNIQUE KEY uk_learning_sandbox_course_one_published (run_id,published_course_id),
    KEY idx_learning_sandbox_course_run_status (run_id,status,updated_at),
    CONSTRAINT chk_learning_sandbox_course_source CHECK (source='mock' AND source_environment='SANDBOX'),
    CONSTRAINT chk_learning_sandbox_course_status CHECK (status IN ('DRAFT','PUBLISHED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_learning_sandbox_admin_idempotency (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, run_id VARCHAR(64) NOT NULL, command_scope VARCHAR(96) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL, request_hash CHAR(64) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'PENDING', result_json JSON NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'mock', source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, is_deleted TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_learning_sandbox_admin_idempotency (run_id,command_scope,idempotency_key),
    CONSTRAINT chk_learning_sandbox_admin_idempotency_source CHECK (source='mock' AND source_environment='SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Existing acceptance baselines may already contain the first four sandbox
-- tables. The CREATE statements above make these ALTERs safe on fresh and
-- older databases alike before mapper queries require run_id.
SET @learning_sql := IF((SELECT table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_learning_sandbox_progress') <> 'utf8mb4_0900_ai_ci', 'ALTER TABLE nx_learning_sandbox_progress CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1'); PREPARE learning_stmt FROM @learning_sql; EXECUTE learning_stmt; DEALLOCATE PREPARE learning_stmt;
SET @learning_sql := IF((SELECT table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_learning_sandbox_event') <> 'utf8mb4_0900_ai_ci', 'ALTER TABLE nx_learning_sandbox_event CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1'); PREPARE learning_stmt FROM @learning_sql; EXECUTE learning_stmt; DEALLOCATE PREPARE learning_stmt;
SET @learning_sql := IF((SELECT table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_learning_sandbox_reward_ledger') <> 'utf8mb4_0900_ai_ci', 'ALTER TABLE nx_learning_sandbox_reward_ledger CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1'); PREPARE learning_stmt FROM @learning_sql; EXECUTE learning_stmt; DEALLOCATE PREPARE learning_stmt;
SET @learning_sql := IF((SELECT table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_learning_sandbox_idempotency') <> 'utf8mb4_0900_ai_ci', 'ALTER TABLE nx_learning_sandbox_idempotency CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1'); PREPARE learning_stmt FROM @learning_sql; EXECUTE learning_stmt; DEALLOCATE PREPARE learning_stmt;
SET @learning_sql := IF((SELECT table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_learning_sandbox_course') <> 'utf8mb4_0900_ai_ci', 'ALTER TABLE nx_learning_sandbox_course CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1'); PREPARE learning_stmt FROM @learning_sql; EXECUTE learning_stmt; DEALLOCATE PREPARE learning_stmt;
SET @learning_sql := IF((SELECT table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_learning_sandbox_admin_idempotency') <> 'utf8mb4_0900_ai_ci', 'ALTER TABLE nx_learning_sandbox_admin_idempotency CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1'); PREPARE learning_stmt FROM @learning_sql; EXECUTE learning_stmt; DEALLOCATE PREPARE learning_stmt;
SET @learning_sql := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_learning_sandbox_progress' AND column_name='run_id')=0, 'ALTER TABLE nx_learning_sandbox_progress ADD COLUMN run_id VARCHAR(64) NOT NULL DEFAULT ''legacy''', 'SELECT 1');
PREPARE learning_stmt FROM @learning_sql; EXECUTE learning_stmt; DEALLOCATE PREPARE learning_stmt;
SET @learning_sql := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_learning_sandbox_course' AND column_name='published_course_id')=0, 'ALTER TABLE nx_learning_sandbox_course ADD COLUMN published_course_id VARCHAR(96) GENERATED ALWAYS AS (CASE WHEN status=''PUBLISHED'' AND is_deleted=0 THEN course_id ELSE NULL END) STORED', 'SELECT 1');
PREPARE learning_stmt FROM @learning_sql; EXECUTE learning_stmt; DEALLOCATE PREPARE learning_stmt;
SET @learning_sql := IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='nx_learning_sandbox_course' AND index_name='uk_learning_sandbox_course_one_published')=0, 'ALTER TABLE nx_learning_sandbox_course ADD UNIQUE KEY uk_learning_sandbox_course_one_published (run_id,published_course_id)', 'SELECT 1');
PREPARE learning_stmt FROM @learning_sql; EXECUTE learning_stmt; DEALLOCATE PREPARE learning_stmt;
SET @learning_sql := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_learning_sandbox_admin_idempotency' AND column_name='result_json')=0, 'ALTER TABLE nx_learning_sandbox_admin_idempotency ADD COLUMN result_json JSON NULL', 'SELECT 1');
PREPARE learning_stmt FROM @learning_sql; EXECUTE learning_stmt; DEALLOCATE PREPARE learning_stmt;
SET @learning_sql := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_learning_sandbox_event' AND column_name='run_id')=0, 'ALTER TABLE nx_learning_sandbox_event ADD COLUMN run_id VARCHAR(64) NOT NULL DEFAULT ''legacy''', 'SELECT 1');
PREPARE learning_stmt FROM @learning_sql; EXECUTE learning_stmt; DEALLOCATE PREPARE learning_stmt;
SET @learning_sql := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_learning_sandbox_reward_ledger' AND column_name='run_id')=0, 'ALTER TABLE nx_learning_sandbox_reward_ledger ADD COLUMN run_id VARCHAR(64) NOT NULL DEFAULT ''legacy''', 'SELECT 1');
PREPARE learning_stmt FROM @learning_sql; EXECUTE learning_stmt; DEALLOCATE PREPARE learning_stmt;
SET @learning_sql := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_learning_sandbox_idempotency' AND column_name='run_id')=0, 'ALTER TABLE nx_learning_sandbox_idempotency ADD COLUMN run_id VARCHAR(64) NOT NULL DEFAULT ''legacy''', 'SELECT 1');
PREPARE learning_stmt FROM @learning_sql; EXECUTE learning_stmt; DEALLOCATE PREPARE learning_stmt;
-- Rebuild only a legacy/malformed index. A correctly run-scoped index is a no-op on every later startup.
SET @learning_sql := IF((SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='nx_learning_sandbox_progress' AND index_name='uk_learning_sandbox_progress_user_course_version') IS NOT NULL, 'ALTER TABLE nx_learning_sandbox_progress DROP INDEX uk_learning_sandbox_progress_user_course_version', 'SELECT 1'); PREPARE learning_stmt FROM @learning_sql; EXECUTE learning_stmt; DEALLOCATE PREPARE learning_stmt;
SET @learning_sql := IF((SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='nx_learning_sandbox_progress' AND index_name='uk_learning_sandbox_progress_run_user_course_version') <> 'run_id,user_id,course_id,course_version', 'ALTER TABLE nx_learning_sandbox_progress DROP INDEX uk_learning_sandbox_progress_run_user_course_version', 'SELECT 1'); PREPARE learning_stmt FROM @learning_sql; EXECUTE learning_stmt; DEALLOCATE PREPARE learning_stmt;
SET @learning_sql := IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='nx_learning_sandbox_progress' AND index_name='uk_learning_sandbox_progress_run_user_course_version')=0, 'ALTER TABLE nx_learning_sandbox_progress ADD UNIQUE KEY uk_learning_sandbox_progress_run_user_course_version (run_id, user_id, course_id, course_version)', 'SELECT 1'); PREPARE learning_stmt FROM @learning_sql; EXECUTE learning_stmt; DEALLOCATE PREPARE learning_stmt;
SET @learning_sql := IF((SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='nx_learning_sandbox_event' AND index_name='uk_learning_sandbox_event_once') <> 'run_id,user_id,course_id,course_version,event_type', 'ALTER TABLE nx_learning_sandbox_event DROP INDEX uk_learning_sandbox_event_once', 'SELECT 1'); PREPARE learning_stmt FROM @learning_sql; EXECUTE learning_stmt; DEALLOCATE PREPARE learning_stmt;
SET @learning_sql := IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='nx_learning_sandbox_event' AND index_name='uk_learning_sandbox_event_once')=0, 'ALTER TABLE nx_learning_sandbox_event ADD UNIQUE KEY uk_learning_sandbox_event_once (run_id, user_id, course_id, course_version, event_type)', 'SELECT 1'); PREPARE learning_stmt FROM @learning_sql; EXECUTE learning_stmt; DEALLOCATE PREPARE learning_stmt;
SET @learning_sql := IF((SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='nx_learning_sandbox_reward_ledger' AND index_name='uk_learning_sandbox_reward_user_course_version') <> 'run_id,user_id,course_id,course_version', 'ALTER TABLE nx_learning_sandbox_reward_ledger DROP INDEX uk_learning_sandbox_reward_user_course_version', 'SELECT 1'); PREPARE learning_stmt FROM @learning_sql; EXECUTE learning_stmt; DEALLOCATE PREPARE learning_stmt;
SET @learning_sql := IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='nx_learning_sandbox_reward_ledger' AND index_name='uk_learning_sandbox_reward_user_course_version')=0, 'ALTER TABLE nx_learning_sandbox_reward_ledger ADD UNIQUE KEY uk_learning_sandbox_reward_user_course_version (run_id, user_id, course_id, course_version)', 'SELECT 1'); PREPARE learning_stmt FROM @learning_sql; EXECUTE learning_stmt; DEALLOCATE PREPARE learning_stmt;
SET @learning_sql := IF((SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='nx_learning_sandbox_idempotency' AND index_name='uk_learning_sandbox_idempotency_attempt') <> 'run_id,user_id,course_id,course_version,idempotency_key', 'ALTER TABLE nx_learning_sandbox_idempotency DROP INDEX uk_learning_sandbox_idempotency_attempt', 'SELECT 1'); PREPARE learning_stmt FROM @learning_sql; EXECUTE learning_stmt; DEALLOCATE PREPARE learning_stmt;
SET @learning_sql := IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='nx_learning_sandbox_idempotency' AND index_name='uk_learning_sandbox_idempotency_attempt')=0, 'ALTER TABLE nx_learning_sandbox_idempotency ADD UNIQUE KEY uk_learning_sandbox_idempotency_attempt (run_id, user_id, course_id, course_version, idempotency_key)', 'SELECT 1'); PREPARE learning_stmt FROM @learning_sql; EXECUTE learning_stmt; DEALLOCATE PREPARE learning_stmt;

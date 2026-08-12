-- Controlled startup migration. Acceptance L6 observations are physically
-- separate from nx_behavior_event_fact and nx_event_outbox.
CREATE TABLE IF NOT EXISTS nx_behavior_sandbox_fact (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL,
  client_event_id CHAR(32) NOT NULL,
  dedupe_key CHAR(64) NOT NULL,
  fingerprint CHAR(64) NOT NULL,
  run_id VARCHAR(64) NOT NULL,
  observation_token CHAR(64) NOT NULL,
  source VARCHAR(16) NOT NULL DEFAULT 'mock',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  event_name VARCHAR(64) NOT NULL,
  session_hash CHAR(64) NOT NULL,
  actor_hash CHAR(64) NOT NULL,
  route VARCHAR(160) NOT NULL,
  page_level TINYINT NOT NULL,
  parent_l1 VARCHAR(160) NOT NULL,
  parent_l2 VARCHAR(160) NOT NULL,
  dwell_ms BIGINT NULL,
  x_norm DECIMAL(6,4) NULL,
  y_norm DECIMAL(6,4) NULL,
  zone VARCHAR(32) NULL,
  element_id VARCHAR(64) NULL,
  device_type VARCHAR(16) NOT NULL,
  locale VARCHAR(16) NOT NULL,
  occurred_at DATETIME(3) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_behavior_sandbox_run_client_event_id (run_id, client_event_id),
  UNIQUE KEY uk_behavior_sandbox_run_dedupe_key (run_id, dedupe_key),
  KEY idx_behavior_sandbox_fingerprint (fingerprint),
  KEY idx_behavior_sandbox_run_time (run_id, occurred_at),
  KEY idx_behavior_sandbox_observation_token (observation_token),
  KEY idx_behavior_sandbox_run_actor_session_time (run_id, actor_hash, session_hash, occurred_at),
  KEY idx_behavior_sandbox_run_route_time (run_id, route, occurred_at),
  KEY idx_behavior_sandbox_event_time (event_name, occurred_at),
  CONSTRAINT chk_behavior_sandbox_provenance
    CHECK (source = 'mock' AND source_environment = 'SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- MySQL 8 needs a guarded column-addition form. These forward-only
-- additions keep an existing baseline compatible without rerunning DDL.
SET @l6_add_production_fingerprint := IF(
  EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE table_schema=DATABASE()
    AND table_name='nx_behavior_event_fact' AND column_name='fingerprint'),
  'SELECT 1',
  'ALTER TABLE nx_behavior_event_fact ADD COLUMN fingerprint CHAR(64) NOT NULL DEFAULT '''' AFTER dedupe_key');
PREPARE l6_stmt FROM @l6_add_production_fingerprint;
EXECUTE l6_stmt;
DEALLOCATE PREPARE l6_stmt;

SET @l6_add_sandbox_fingerprint := IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE table_schema=DATABASE()
  AND table_name='nx_behavior_sandbox_fact' AND column_name='fingerprint'),'SELECT 1',
  'ALTER TABLE nx_behavior_sandbox_fact ADD COLUMN fingerprint CHAR(64) NOT NULL DEFAULT '''' AFTER dedupe_key');
PREPARE l6_stmt FROM @l6_add_sandbox_fingerprint; EXECUTE l6_stmt; DEALLOCATE PREPARE l6_stmt;
SET @l6_add_sandbox_run_id := IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE table_schema=DATABASE()
  AND table_name='nx_behavior_sandbox_fact' AND column_name='run_id'),'SELECT 1',
  'ALTER TABLE nx_behavior_sandbox_fact ADD COLUMN run_id VARCHAR(64) NOT NULL DEFAULT '''' AFTER fingerprint');
PREPARE l6_stmt FROM @l6_add_sandbox_run_id; EXECUTE l6_stmt; DEALLOCATE PREPARE l6_stmt;
SET @l6_add_sandbox_observation_token := IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE table_schema=DATABASE()
  AND table_name='nx_behavior_sandbox_fact' AND column_name='observation_token'),'SELECT 1',
  'ALTER TABLE nx_behavior_sandbox_fact ADD COLUMN observation_token CHAR(64) NOT NULL DEFAULT '''' AFTER run_id');
PREPARE l6_stmt FROM @l6_add_sandbox_observation_token; EXECUTE l6_stmt; DEALLOCATE PREPARE l6_stmt;
SET @l6_add_sandbox_source := IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE table_schema=DATABASE()
  AND table_name='nx_behavior_sandbox_fact' AND column_name='source'),'SELECT 1',
  'ALTER TABLE nx_behavior_sandbox_fact ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT ''mock'' AFTER observation_token');
PREPARE l6_stmt FROM @l6_add_sandbox_source; EXECUTE l6_stmt; DEALLOCATE PREPARE l6_stmt;
SET @l6_add_sandbox_source_environment := IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE table_schema=DATABASE()
  AND table_name='nx_behavior_sandbox_fact' AND column_name='source_environment'),'SELECT 1',
  'ALTER TABLE nx_behavior_sandbox_fact ADD COLUMN source_environment VARCHAR(16) NOT NULL DEFAULT ''SANDBOX'' AFTER source');
PREPARE l6_stmt FROM @l6_add_sandbox_source_environment; EXECUTE l6_stmt; DEALLOCATE PREPARE l6_stmt;

-- Upgrade the pre-P1 sandbox safely: client idempotency, dedupe, ordering and
-- rate limits are all a run boundary, never a cross-run global boundary.
SET @l6_drop_old_client_unique := IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE table_schema=DATABASE()
  AND table_name='nx_behavior_sandbox_fact' AND index_name='uk_behavior_sandbox_client_event_id'),
  'ALTER TABLE nx_behavior_sandbox_fact DROP INDEX uk_behavior_sandbox_client_event_id','SELECT 1');
PREPARE l6_stmt FROM @l6_drop_old_client_unique; EXECUTE l6_stmt; DEALLOCATE PREPARE l6_stmt;
SET @l6_drop_old_dedupe_unique := IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE table_schema=DATABASE()
  AND table_name='nx_behavior_sandbox_fact' AND index_name='uk_behavior_sandbox_dedupe_key'),
  'ALTER TABLE nx_behavior_sandbox_fact DROP INDEX uk_behavior_sandbox_dedupe_key','SELECT 1');
PREPARE l6_stmt FROM @l6_drop_old_dedupe_unique; EXECUTE l6_stmt; DEALLOCATE PREPARE l6_stmt;
SET @l6_add_run_client_unique := IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE table_schema=DATABASE()
  AND table_name='nx_behavior_sandbox_fact' AND index_name='uk_behavior_sandbox_run_client_event_id'),'SELECT 1',
  'ALTER TABLE nx_behavior_sandbox_fact ADD UNIQUE KEY uk_behavior_sandbox_run_client_event_id (run_id,client_event_id)');
PREPARE l6_stmt FROM @l6_add_run_client_unique; EXECUTE l6_stmt; DEALLOCATE PREPARE l6_stmt;
SET @l6_add_run_dedupe_unique := IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE table_schema=DATABASE()
  AND table_name='nx_behavior_sandbox_fact' AND index_name='uk_behavior_sandbox_run_dedupe_key'),'SELECT 1',
  'ALTER TABLE nx_behavior_sandbox_fact ADD UNIQUE KEY uk_behavior_sandbox_run_dedupe_key (run_id,dedupe_key)');
PREPARE l6_stmt FROM @l6_add_run_dedupe_unique; EXECUTE l6_stmt; DEALLOCATE PREPARE l6_stmt;
SET @l6_add_observation_index := IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE table_schema=DATABASE()
  AND table_name='nx_behavior_sandbox_fact' AND index_name='idx_behavior_sandbox_observation_token'),'SELECT 1',
  'ALTER TABLE nx_behavior_sandbox_fact ADD KEY idx_behavior_sandbox_observation_token (observation_token)');
PREPARE l6_stmt FROM @l6_add_observation_index; EXECUTE l6_stmt; DEALLOCATE PREPARE l6_stmt;

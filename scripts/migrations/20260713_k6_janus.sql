-- K6 Janus C2 authoritative business tables.
-- No demo rows are inserted: devices/evaluations are written by the Janus reporting pipeline.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_janus_device (
  sid VARCHAR(96) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  device_id VARCHAR(128) NOT NULL,
  first_seen_at DATETIME(3) NOT NULL,
  last_seen_at DATETIME(3) NOT NULL,
  install_at DATETIME(3) NOT NULL,
  invite_code VARCHAR(96) DEFAULT NULL,
  channel VARCHAR(64) DEFAULT NULL,
  cohort_id VARCHAR(96) DEFAULT NULL,
  reported_status VARCHAR(32) NOT NULL DEFAULT 'NEW',
  desired_status VARCHAR(32) DEFAULT NULL,
  desired_revision BIGINT NOT NULL DEFAULT 0,
  acked_revision BIGINT NOT NULL DEFAULT 0,
  command_state VARCHAR(24) DEFAULT NULL,
  status_source VARCHAR(24) NOT NULL DEFAULT 'system',
  activated TINYINT NOT NULL DEFAULT 0,
  remote_url_key VARCHAR(64) DEFAULT NULL,
  maturity_score INT NOT NULL DEFAULT 0,
  recommendation_score INT NOT NULL DEFAULT 0,
  environment_risk_score INT NOT NULL DEFAULT 0,
  priority_score INT NOT NULL DEFAULT 0,
  ua VARCHAR(1000) DEFAULT NULL,
  platform VARCHAR(64) DEFAULT NULL,
  model VARCHAR(128) DEFAULT NULL,
  os_name VARCHAR(128) DEFAULT NULL,
  browser VARCHAR(128) DEFAULT NULL,
  maturity_json JSON NOT NULL,
  environment_json JSON NOT NULL,
  hit_strategy VARCHAR(96) DEFAULT NULL,
  hit_strategy_version INT DEFAULT NULL,
  latest_decision_json JSON DEFAULT NULL,
  latest_session_json JSON DEFAULT NULL,
  manual_override_json JSON DEFAULT NULL,
  last_operator_id VARCHAR(96) DEFAULT NULL,
  last_operation_reason VARCHAR(500) DEFAULT NULL,
  activation_kind VARCHAR(48) DEFAULT NULL,
  tags_json JSON NOT NULL,
  lock_version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT ck_janus_device_reported_status CHECK (reported_status IN ('NEW','OBSERVING','RECOMMENDED','HIT','ACTIVATED','ENV_FILTERED','MANUAL_HOLD','MANUAL_FORCED','BLOCKED','STALE','RESET','ERROR')),
  CONSTRAINT ck_janus_device_desired_status CHECK (desired_status IS NULL OR desired_status IN ('NEW','OBSERVING','RECOMMENDED','HIT','ACTIVATED','ENV_FILTERED','MANUAL_HOLD','MANUAL_FORCED','BLOCKED','STALE','RESET','ERROR')),
  UNIQUE KEY uk_janus_device_owner (user_id, device_id),
  KEY idx_janus_device_queue (reported_status, priority_score, last_seen_at),
  KEY idx_janus_device_channel (channel, reported_status),
  KEY idx_janus_device_strategy (hit_strategy),
  KEY idx_janus_device_risk (environment_risk_score)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Safe upgrade path for a checkout that started the earlier K6 table definition.
SET @janus_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_janus_device' AND COLUMN_NAME='user_id')=0,
  'ALTER TABLE nx_janus_device ADD COLUMN user_id BIGINT NOT NULL DEFAULT 0 AFTER sid', 'SELECT 1');
PREPARE janus_stmt FROM @janus_sql; EXECUTE janus_stmt; DEALLOCATE PREPARE janus_stmt;
SET @janus_sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_janus_device' AND INDEX_NAME='uk_janus_device_owner')=0,
  'ALTER TABLE nx_janus_device ADD UNIQUE KEY uk_janus_device_owner (user_id,device_id)', 'SELECT 1');
PREPARE janus_stmt FROM @janus_sql; EXECUTE janus_stmt; DEALLOCATE PREPARE janus_stmt;

CREATE TABLE IF NOT EXISTS nx_janus_strategy (
  strategy_id VARCHAR(96) PRIMARY KEY,
  name VARCHAR(160) NOT NULL,
  description VARCHAR(1000) DEFAULT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'draft',
  version INT NOT NULL DEFAULT 1,
  priority INT NOT NULL DEFAULT 0,
  owner VARCHAR(96) NOT NULL,
  scope_json JSON NOT NULL,
  rule_tree_json JSON NOT NULL,
  action_json JSON NOT NULL,
  safeguards_json JSON NOT NULL,
  rollout_json JSON NOT NULL,
  health_config_json JSON NOT NULL,
  template_key VARCHAR(64) DEFAULT NULL,
  published_at DATETIME(3) DEFAULT NULL,
  lock_version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT ck_janus_strategy_status CHECK (status IN ('draft','active','paused','archived')),
  KEY idx_janus_strategy_state (status, priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_janus_strategy_version (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  strategy_id VARCHAR(96) NOT NULL,
  version INT NOT NULL,
  note VARCHAR(500) NOT NULL,
  actor_id VARCHAR(96) NOT NULL,
  snapshot_json JSON NOT NULL,
  config_hash CHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_janus_strategy_version (strategy_id, version),
  KEY idx_janus_strategy_version_time (strategy_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_janus_evaluation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  report_id VARCHAR(128) NOT NULL,
  sid VARCHAR(96) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  session_id VARCHAR(128) DEFAULT NULL,
  strategy_id VARCHAR(96) DEFAULT NULL,
  strategy_version INT DEFAULT NULL,
  input_snapshot_json JSON NOT NULL,
  rule_results_json JSON NOT NULL,
  action VARCHAR(48) DEFAULT NULL,
  recommended_status VARCHAR(32) DEFAULT NULL,
  error_code VARCHAR(96) DEFAULT NULL,
  elapsed_ms INT DEFAULT NULL,
  engine_version VARCHAR(64) NOT NULL,
  evaluated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_janus_evaluation_report (sid, report_id),
  KEY idx_janus_evaluation_strategy (strategy_id, strategy_version, evaluated_at),
  KEY idx_janus_evaluation_time (evaluated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @janus_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_janus_evaluation' AND COLUMN_NAME='request_hash')=0,
  'ALTER TABLE nx_janus_evaluation ADD COLUMN request_hash CHAR(64) DEFAULT NULL AFTER sid', 'SELECT 1');
PREPARE janus_stmt FROM @janus_sql; EXECUTE janus_stmt; DEALLOCATE PREPARE janus_stmt;
UPDATE nx_janus_evaluation
SET request_hash=SHA2(CONCAT(sid,':',report_id,':',CAST(input_snapshot_json AS CHAR)),256)
WHERE request_hash IS NULL;
ALTER TABLE nx_janus_evaluation MODIFY COLUMN request_hash CHAR(64) NOT NULL;

CREATE TABLE IF NOT EXISTS nx_janus_daily_quota (
  strategy_id VARCHAR(96) NOT NULL,
  quota_day DATE NOT NULL,
  action VARCHAR(48) NOT NULL,
  used_count INT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (strategy_id, quota_day, action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_janus_daily_quota(strategy_id,quota_day,action,used_count)
SELECT strategy_id,CURRENT_DATE,action,COUNT(*) FROM nx_janus_evaluation
WHERE strategy_id IS NOT NULL AND action IS NOT NULL AND evaluated_at>=CURRENT_DATE
GROUP BY strategy_id,action
ON DUPLICATE KEY UPDATE used_count=GREATEST(used_count,VALUES(used_count));

CREATE TABLE IF NOT EXISTS nx_janus_dry_run (
  dry_run_id VARCHAR(96) PRIMARY KEY,
  strategy_id VARCHAR(96) NOT NULL,
  expected_version BIGINT NOT NULL,
  config_hash CHAR(64) NOT NULL,
  result_json JSON NOT NULL,
  actor_id VARCHAR(96) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at DATETIME(3) NOT NULL,
  KEY idx_janus_dry_run_strategy (strategy_id, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_janus_command (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  idempotency_key VARCHAR(128) NOT NULL,
  command_type VARCHAR(64) NOT NULL,
  target_id VARCHAR(96) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  actor_id VARCHAR(96) NOT NULL,
  state VARCHAR(24) NOT NULL,
  payload_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at DATETIME(3) DEFAULT NULL,
  UNIQUE KEY uk_janus_command_idem (idempotency_key),
  KEY idx_janus_command_target (target_id, created_at),
  KEY idx_janus_command_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_janus_command' AND COLUMN_NAME = 'expires_at') = 0,
  'ALTER TABLE nx_janus_command ADD COLUMN expires_at DATETIME(3) DEFAULT NULL AFTER created_at, ADD KEY idx_janus_command_expiry (expires_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE nx_janus_command SET expires_at = DATE_ADD(created_at, INTERVAL 24 HOUR) WHERE expires_at IS NULL;

-- Commands are also written to the existing nx_event_outbox in the same Spring transaction.

CREATE TABLE IF NOT EXISTS nx_user_login_guard (
  login_key CHAR(64) PRIMARY KEY,
  failed_count INT NOT NULL DEFAULT 0,
  window_started_at DATETIME(3) NOT NULL,
  locked_until DATETIME(3) DEFAULT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_user_login_guard_lock (locked_until),
  KEY idx_user_login_guard_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_login_guard' AND INDEX_NAME='idx_user_login_guard_updated') = 0,
  'ALTER TABLE nx_user_login_guard ADD KEY idx_user_login_guard_updated (updated_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Register K6 against either menu-code generation. Existing databases use the
-- compact PC codes (K/K6), while fresh databases may use MENU_RISK(_K6).
-- Reuse the canonical K6 row when present and retire aliases after transferring
-- their grants, otherwise A7 can return two rows that normalize to the same page.
SET @risk_menu_id = (
  SELECT id FROM nx_admin_menu
  WHERE menu_code IN ('K','MENU_RISK') AND status=1 AND is_deleted=0
  ORDER BY CASE menu_code WHEN 'K' THEN 0 ELSE 1 END, id
  LIMIT 1
);
SET @risk_recover_id = (
  SELECT id FROM nx_admin_menu
  WHERE menu_code IN ('K','MENU_RISK')
  ORDER BY CASE menu_code WHEN 'K' THEN 0 ELSE 1 END, id
  LIMIT 1
);
UPDATE nx_admin_menu
SET status=1,is_deleted=0,updated_at=NOW()
WHERE @risk_menu_id IS NULL AND id=@risk_recover_id;
SET @risk_menu_id = COALESCE(@risk_menu_id,@risk_recover_id);

INSERT INTO nx_admin_menu
  (menu_code,menu_name,menu_name_zh,menu_name_en,parent_id,route_path,icon,sort_order,remark,status,is_deleted)
SELECT 'MENU_RISK','风控与反作弊','风控与反作弊','risk',NULL,'/risk','Radar',2000,
       '风控与反作弊目录。',1,0
WHERE @risk_menu_id IS NULL;

SET @risk_menu_id = COALESCE(@risk_menu_id, (
  SELECT id FROM nx_admin_menu
  WHERE menu_code='MENU_RISK' AND status=1 AND is_deleted=0
  LIMIT 1
));
SET @k6_menu_id = (
  SELECT id FROM nx_admin_menu
  WHERE menu_code IN ('K6','MENU_RISK_K6')
  ORDER BY (status=1 AND is_deleted=0) DESC,
           CASE menu_code WHEN 'K6' THEN 0 ELSE 1 END, id
  LIMIT 1
);

INSERT INTO nx_admin_menu
  (menu_code,menu_name,menu_name_zh,menu_name_en,parent_id,route_path,icon,sort_order,remark,status,is_deleted)
SELECT 'MENU_RISK_K6','Janus C2 控制台','Janus C2 控制台','K6',
       @risk_menu_id,'/risk/janus-c2','Document',2006,'K6 Janus C2 控制台。',1,0
WHERE @k6_menu_id IS NULL;

SET @k6_menu_id = COALESCE(@k6_menu_id, (
  SELECT id FROM nx_admin_menu WHERE menu_code='MENU_RISK_K6' LIMIT 1
));

UPDATE nx_admin_menu
SET menu_name='Janus C2 控制台',menu_name_zh='Janus C2 控制台',menu_name_en='K6',
    parent_id=@risk_menu_id,route_path='/risk/janus-c2',icon='Document',
    sort_order=CASE WHEN menu_code='K6' THEN 6 ELSE 2006 END,
    remark='K6 Janus C2 控制台。',status=1,is_deleted=0
WHERE id=@k6_menu_id;

INSERT INTO nx_admin_role_menu(role_id,menu_id,created_at,updated_at,is_deleted)
SELECT relation.role_id,@k6_menu_id,NOW(),NOW(),0
FROM nx_admin_role_menu relation
JOIN nx_admin_menu duplicate ON duplicate.id=relation.menu_id
WHERE duplicate.menu_code IN ('K6','MENU_RISK_K6') AND duplicate.id<>@k6_menu_id
  AND relation.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0,updated_at=NOW();

UPDATE nx_admin_permission permission
JOIN nx_admin_menu duplicate ON duplicate.id=permission.menu_id
SET permission.menu_id=@k6_menu_id,permission.updated_at=NOW()
WHERE duplicate.menu_code IN ('K6','MENU_RISK_K6') AND duplicate.id<>@k6_menu_id;

UPDATE nx_admin_role_menu relation
JOIN nx_admin_menu duplicate ON duplicate.id=relation.menu_id
SET relation.is_deleted=1,relation.updated_at=NOW()
WHERE duplicate.menu_code IN ('K6','MENU_RISK_K6') AND duplicate.id<>@k6_menu_id;

UPDATE nx_admin_menu
SET status=0,is_deleted=1,updated_at=NOW()
WHERE menu_code IN ('K6','MENU_RISK_K6') AND id<>@k6_menu_id;

INSERT INTO nx_admin_role_menu(role_id,menu_id,created_at,updated_at,is_deleted)
SELECT role.id,menu.id,NOW(),NOW(),0
FROM nx_admin_role role JOIN nx_admin_menu menu ON menu.id=@k6_menu_id AND menu.is_deleted=0
WHERE role.role_code IN ('SUPER_ADMIN','RISK','AUDITOR') AND role.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0,updated_at=NOW();

INSERT INTO nx_admin_permission
  (permission_code,permission_name,resource_type,resource_path,menu_id,perm_type,amplifies,status,is_deleted)
VALUES
  ('risk_k6_read','Janus C2 控制台-读','API','/risk/janus-c2',
   @k6_menu_id,'READ',0,1,0),
  ('risk_k6_write','Janus C2 控制台-写','API','/risk/janus-c2',
   @k6_menu_id,'HIGH',1,1,0),
  ('risk_k6_senior','Janus C2 控制台-高级操作','API','/risk/janus-c2',
   @k6_menu_id,'HIGH',1,1,0),
  ('risk_k6_admin','Janus C2 控制台-发布与回滚','API','/risk/janus-c2',
   @k6_menu_id,'HIGH',1,1,0)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),resource_type=VALUES(resource_type),
  resource_path=VALUES(resource_path),menu_id=VALUES(menu_id),perm_type=VALUES(perm_type),
  amplifies=VALUES(amplifies),status=1,is_deleted=0;

INSERT INTO nx_admin_role_permission(role_id,permission_id,created_at,updated_at,is_deleted)
SELECT role.id,permission.id,NOW(),NOW(),0
FROM nx_admin_role role JOIN nx_admin_permission permission
  ON permission.permission_code IN ('risk_k6_read','risk_k6_write') AND permission.is_deleted=0
WHERE role.role_code IN ('SUPER_ADMIN','RISK') AND role.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0,updated_at=NOW();

INSERT INTO nx_admin_role_permission(role_id,permission_id,created_at,updated_at,is_deleted)
SELECT role.id,permission.id,NOW(),NOW(),0
FROM nx_admin_role role JOIN nx_admin_permission permission
  ON permission.permission_code='risk_k6_senior' AND permission.is_deleted=0
WHERE role.role_code IN ('SUPER_ADMIN','RISK') AND role.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0,updated_at=NOW();

INSERT INTO nx_admin_role_permission(role_id,permission_id,created_at,updated_at,is_deleted)
SELECT role.id,permission.id,NOW(),NOW(),0
FROM nx_admin_role role JOIN nx_admin_permission permission
  ON permission.permission_code='risk_k6_admin' AND permission.is_deleted=0
WHERE role.role_code='SUPER_ADMIN' AND role.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0,updated_at=NOW();

-- K6 publish/rollback is deliberately limited to the platform super administrator.
-- Re-running this migration must also repair a previously over-granted role relation.
UPDATE nx_admin_role_permission relation
JOIN nx_admin_role role ON role.id=relation.role_id AND role.is_deleted=0
JOIN nx_admin_permission permission ON permission.id=relation.permission_id AND permission.is_deleted=0
SET relation.is_deleted=1,relation.updated_at=NOW()
WHERE permission.permission_code='risk_k6_admin'
  AND role.role_code<>'SUPER_ADMIN'
  AND relation.is_deleted=0;

INSERT INTO nx_admin_role_permission(role_id,permission_id,created_at,updated_at,is_deleted)
SELECT role.id,permission.id,NOW(),NOW(),0
FROM nx_admin_role role JOIN nx_admin_permission permission
  ON permission.permission_code='risk_k6_read' AND permission.is_deleted=0
WHERE role.role_code='AUDITOR' AND role.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0,updated_at=NOW();

-- Auditor is read-only for K6. Retire any historical write grant explicitly.
UPDATE nx_admin_role_permission relation
JOIN nx_admin_role role ON role.id=relation.role_id AND role.role_code='AUDITOR'
JOIN nx_admin_permission permission ON permission.id=relation.permission_id
  AND permission.permission_code='risk_k6_write'
SET relation.is_deleted=1,relation.updated_at=NOW()
WHERE relation.is_deleted=0;

-- H4 Lucky Spin local-sandbox isolation.
-- These tables are intentionally separate from canonical wheel facts,
-- wallet/release-ledger, audit and outbox tables.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS nx_growth_wheel_sandbox_scope (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  source VARCHAR(16) NOT NULL DEFAULT 'mock',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_growth_wheel_sandbox_scope (run_id,user_id),
  CONSTRAINT chk_growth_wheel_sandbox_scope_source CHECK (source='mock' AND source_environment='SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_growth_wheel_sandbox_tier (
  tier_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  tier_name VARCHAR(128) NOT NULL,
  reward_name VARCHAR(128) NOT NULL,
  probability_pct DECIMAL(8,4) NOT NULL DEFAULT 0,
  reward_kind VARCHAR(32) NOT NULL,
  reward_amount DECIMAL(18,6) NOT NULL DEFAULT 0,
  real_outflow TINYINT NOT NULL DEFAULT 0,
  daily_stock INT NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 100,
  status TINYINT NOT NULL DEFAULT 1,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  source VARCHAR(16) NOT NULL DEFAULT 'mock',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_growth_wheel_sandbox_tier (run_id,user_id,tier_name),
  KEY idx_growth_wheel_sandbox_tier_scope (run_id,user_id,status,is_deleted),
  CONSTRAINT chk_growth_wheel_sandbox_tier_source CHECK (source='mock' AND source_environment='SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_growth_wheel_sandbox_guard (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  guard_key VARCHAR(64) NOT NULL,
  guard_value VARCHAR(255) NOT NULL DEFAULT '',
  status TINYINT NOT NULL DEFAULT 1,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  source VARCHAR(16) NOT NULL DEFAULT 'mock',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_growth_wheel_sandbox_guard (run_id,user_id,guard_key),
  KEY idx_growth_wheel_sandbox_guard_scope (run_id,user_id,status,is_deleted),
  CONSTRAINT chk_growth_wheel_sandbox_guard_source CHECK (source='mock' AND source_environment='SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_growth_wheel_sandbox_ticket (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  ticket_id VARCHAR(96) NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  source_id VARCHAR(96) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
  used_event_code VARCHAR(64) NULL,
  spin_date DATE NULL,
  used_at DATETIME NULL,
  source VARCHAR(16) NOT NULL DEFAULT 'mock',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_growth_wheel_sandbox_ticket_id (ticket_id),
  UNIQUE KEY uk_growth_wheel_sandbox_ticket_source (run_id,user_id,source_type,source_id),
  KEY idx_growth_wheel_sandbox_ticket_scope (run_id,user_id,status,created_at),
  CONSTRAINT chk_growth_wheel_sandbox_ticket_source CHECK (source='mock' AND source_environment='SANDBOX'),
  CONSTRAINT chk_growth_wheel_sandbox_ticket_kind CHECK (source_type='DAILY_MILESTONE')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_growth_wheel_sandbox_spin (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  spin_no VARCHAR(96) NOT NULL,
  event_code VARCHAR(64) NOT NULL,
  spin_date DATE NOT NULL,
  source_type VARCHAR(16) NOT NULL,
  source_id VARCHAR(96) NOT NULL,
  tier_id BIGINT NOT NULL,
  tier_name VARCHAR(128) NOT NULL,
  reward_name VARCHAR(128) NOT NULL,
  reward_kind VARCHAR(32) NOT NULL,
  reward_amount DECIMAL(18,6) NOT NULL,
  real_outflow TINYINT NOT NULL DEFAULT 0,
  downgraded TINYINT NOT NULL DEFAULT 0,
  downgrade_reason VARCHAR(64) NOT NULL DEFAULT 'NONE',
  source VARCHAR(16) NOT NULL DEFAULT 'mock',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_growth_wheel_sandbox_spin_no (spin_no),
  UNIQUE KEY uk_growth_wheel_sandbox_spin_source (run_id,user_id,event_code,spin_date,source_type,source_id),
  KEY idx_growth_wheel_sandbox_spin_scope (run_id,user_id,event_code,created_at),
  CONSTRAINT chk_growth_wheel_sandbox_spin_source CHECK (source='mock' AND source_environment='SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_growth_wheel_sandbox_reward_ledger (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  biz_no VARCHAR(128) NOT NULL,
  asset VARCHAR(32) NOT NULL,
  amount DECIMAL(24,8) NOT NULL,
  balance_after DECIMAL(24,8) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'POSTED',
  source VARCHAR(16) NOT NULL DEFAULT 'mock',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_growth_wheel_sandbox_reward (run_id,user_id,biz_no,asset),
  KEY idx_growth_wheel_sandbox_reward_scope (run_id,user_id,asset,created_at),
  CONSTRAINT chk_growth_wheel_sandbox_reward_source CHECK (source='mock' AND source_environment='SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_growth_wheel_sandbox_command (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  operation VARCHAR(48) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  spin_no VARCHAR(96) NULL,
  source VARCHAR(16) NOT NULL DEFAULT 'mock',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_growth_wheel_sandbox_command (run_id,user_id,operation,idempotency_key),
  KEY idx_growth_wheel_sandbox_command_scope (run_id,user_id,created_at),
  CONSTRAINT chk_growth_wheel_sandbox_command_source CHECK (source='mock' AND source_environment='SANDBOX')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Repair tables created by the canonical schema before this migration landed.
-- CREATE TABLE IF NOT EXISTS is intentionally not relied on for upgrades.
SET @h4_idx_sql = IF(EXISTS(SELECT 1 FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='nx_growth_wheel_sandbox_tier' AND index_name='idx_growth_wheel_sandbox_tier_scope'),
  'SELECT 1','ALTER TABLE nx_growth_wheel_sandbox_tier ADD KEY idx_growth_wheel_sandbox_tier_scope (run_id,user_id,status,is_deleted)');
PREPARE h4_idx_stmt FROM @h4_idx_sql; EXECUTE h4_idx_stmt; DEALLOCATE PREPARE h4_idx_stmt;
SET @h4_idx_sql = IF(EXISTS(SELECT 1 FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='nx_growth_wheel_sandbox_guard' AND index_name='idx_growth_wheel_sandbox_guard_scope'),
  'SELECT 1','ALTER TABLE nx_growth_wheel_sandbox_guard ADD KEY idx_growth_wheel_sandbox_guard_scope (run_id,user_id,status,is_deleted)');
PREPARE h4_idx_stmt FROM @h4_idx_sql; EXECUTE h4_idx_stmt; DEALLOCATE PREPARE h4_idx_stmt;
SET @h4_idx_sql = IF(EXISTS(SELECT 1 FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='nx_growth_wheel_sandbox_ticket' AND index_name='idx_growth_wheel_sandbox_ticket_scope'),
  'SELECT 1','ALTER TABLE nx_growth_wheel_sandbox_ticket ADD KEY idx_growth_wheel_sandbox_ticket_scope (run_id,user_id,status,created_at)');
PREPARE h4_idx_stmt FROM @h4_idx_sql; EXECUTE h4_idx_stmt; DEALLOCATE PREPARE h4_idx_stmt;
SET @h4_idx_sql = IF(EXISTS(SELECT 1 FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='nx_growth_wheel_sandbox_spin' AND index_name='idx_growth_wheel_sandbox_spin_scope'),
  'SELECT 1','ALTER TABLE nx_growth_wheel_sandbox_spin ADD KEY idx_growth_wheel_sandbox_spin_scope (run_id,user_id,event_code,created_at)');
PREPARE h4_idx_stmt FROM @h4_idx_sql; EXECUTE h4_idx_stmt; DEALLOCATE PREPARE h4_idx_stmt;
SET @h4_idx_sql = IF(EXISTS(SELECT 1 FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='nx_growth_wheel_sandbox_reward_ledger' AND index_name='idx_growth_wheel_sandbox_reward_scope'),
  'SELECT 1','ALTER TABLE nx_growth_wheel_sandbox_reward_ledger ADD KEY idx_growth_wheel_sandbox_reward_scope (run_id,user_id,asset,created_at)');
PREPARE h4_idx_stmt FROM @h4_idx_sql; EXECUTE h4_idx_stmt; DEALLOCATE PREPARE h4_idx_stmt;
SET @h4_idx_sql = IF(EXISTS(SELECT 1 FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='nx_growth_wheel_sandbox_command' AND index_name='idx_growth_wheel_sandbox_command_scope'),
  'SELECT 1','ALTER TABLE nx_growth_wheel_sandbox_command ADD KEY idx_growth_wheel_sandbox_command_scope (run_id,user_id,created_at)');
PREPARE h4_idx_stmt FROM @h4_idx_sql; EXECUTE h4_idx_stmt; DEALLOCATE PREPARE h4_idx_stmt;

SET @h4_check_sql = IF(EXISTS(SELECT 1 FROM information_schema.check_constraints
  WHERE constraint_schema=DATABASE() AND constraint_name='chk_growth_wheel_sandbox_scope_source'),
  'SELECT 1','ALTER TABLE nx_growth_wheel_sandbox_scope ADD CONSTRAINT chk_growth_wheel_sandbox_scope_source CHECK (source=''mock'' AND source_environment=''SANDBOX'')');
PREPARE h4_check_stmt FROM @h4_check_sql; EXECUTE h4_check_stmt; DEALLOCATE PREPARE h4_check_stmt;
SET @h4_check_sql = IF(EXISTS(SELECT 1 FROM information_schema.check_constraints
  WHERE constraint_schema=DATABASE() AND constraint_name='chk_growth_wheel_sandbox_tier_source'),
  'SELECT 1','ALTER TABLE nx_growth_wheel_sandbox_tier ADD CONSTRAINT chk_growth_wheel_sandbox_tier_source CHECK (source=''mock'' AND source_environment=''SANDBOX'')');
PREPARE h4_check_stmt FROM @h4_check_sql; EXECUTE h4_check_stmt; DEALLOCATE PREPARE h4_check_stmt;
SET @h4_check_sql = IF(EXISTS(SELECT 1 FROM information_schema.check_constraints
  WHERE constraint_schema=DATABASE() AND constraint_name='chk_growth_wheel_sandbox_guard_source'),
  'SELECT 1','ALTER TABLE nx_growth_wheel_sandbox_guard ADD CONSTRAINT chk_growth_wheel_sandbox_guard_source CHECK (source=''mock'' AND source_environment=''SANDBOX'')');
PREPARE h4_check_stmt FROM @h4_check_sql; EXECUTE h4_check_stmt; DEALLOCATE PREPARE h4_check_stmt;
SET @h4_check_sql = IF(EXISTS(SELECT 1 FROM information_schema.check_constraints
  WHERE constraint_schema=DATABASE() AND constraint_name='chk_growth_wheel_sandbox_ticket_source'),
  'SELECT 1','ALTER TABLE nx_growth_wheel_sandbox_ticket ADD CONSTRAINT chk_growth_wheel_sandbox_ticket_source CHECK (source=''mock'' AND source_environment=''SANDBOX'')');
PREPARE h4_check_stmt FROM @h4_check_sql; EXECUTE h4_check_stmt; DEALLOCATE PREPARE h4_check_stmt;
SET @h4_check_sql = IF(EXISTS(SELECT 1 FROM information_schema.check_constraints
  WHERE constraint_schema=DATABASE() AND constraint_name='chk_growth_wheel_sandbox_ticket_kind'),
  'SELECT 1','ALTER TABLE nx_growth_wheel_sandbox_ticket ADD CONSTRAINT chk_growth_wheel_sandbox_ticket_kind CHECK (source_type=''DAILY_MILESTONE'')');
PREPARE h4_check_stmt FROM @h4_check_sql; EXECUTE h4_check_stmt; DEALLOCATE PREPARE h4_check_stmt;
SET @h4_check_sql = IF(EXISTS(SELECT 1 FROM information_schema.check_constraints
  WHERE constraint_schema=DATABASE() AND constraint_name='chk_growth_wheel_sandbox_spin_source'),
  'SELECT 1','ALTER TABLE nx_growth_wheel_sandbox_spin ADD CONSTRAINT chk_growth_wheel_sandbox_spin_source CHECK (source=''mock'' AND source_environment=''SANDBOX'')');
PREPARE h4_check_stmt FROM @h4_check_sql; EXECUTE h4_check_stmt; DEALLOCATE PREPARE h4_check_stmt;
SET @h4_check_sql = IF(EXISTS(SELECT 1 FROM information_schema.check_constraints
  WHERE constraint_schema=DATABASE() AND constraint_name='chk_growth_wheel_sandbox_reward_source'),
  'SELECT 1','ALTER TABLE nx_growth_wheel_sandbox_reward_ledger ADD CONSTRAINT chk_growth_wheel_sandbox_reward_source CHECK (source=''mock'' AND source_environment=''SANDBOX'')');
PREPARE h4_check_stmt FROM @h4_check_sql; EXECUTE h4_check_stmt; DEALLOCATE PREPARE h4_check_stmt;
SET @h4_check_sql = IF(EXISTS(SELECT 1 FROM information_schema.check_constraints
  WHERE constraint_schema=DATABASE() AND constraint_name='chk_growth_wheel_sandbox_command_source'),
  'SELECT 1','ALTER TABLE nx_growth_wheel_sandbox_command ADD CONSTRAINT chk_growth_wheel_sandbox_command_source CHECK (source=''mock'' AND source_environment=''SANDBOX'')');
PREPARE h4_check_stmt FROM @h4_check_sql; EXECUTE h4_check_stmt; DEALLOCATE PREPARE h4_check_stmt;

-- Local sandbox quest progress. Active weekly definitions may be mirrored
-- read-only from PC-managed nx_mission configuration, but nx_user_mission is
-- never read or written, so a run cannot change production quest state.
CREATE TABLE IF NOT EXISTS nx_growth_quest_sandbox (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  quest_code VARCHAR(64) NOT NULL,
  quest_name VARCHAR(128) NOT NULL,
  layer VARCHAR(32) NOT NULL,
  reward_nex DECIMAL(18,6) NOT NULL,
  mission_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  claim_idempotency_key VARCHAR(128) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  source VARCHAR(32) NOT NULL DEFAULT 'mock',
  source_environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_growth_quest_sandbox_scope (run_id,user_id,quest_code),
  KEY idx_growth_quest_sandbox_user (run_id,user_id,source_environment,mission_status),
  CONSTRAINT chk_growth_quest_sandbox_source CHECK (source='mock' AND source_environment='SANDBOX'),
  CONSTRAINT chk_growth_quest_sandbox_status CHECK (mission_status IN ('PENDING','COMPLETED','CLAIMABLE','CLAIMED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_growth_quest_sandbox'
                 AND COLUMN_NAME='claim_idempotency_key')=0,
  'ALTER TABLE nx_growth_quest_sandbox ADD COLUMN claim_idempotency_key VARCHAR(128) NULL AFTER mission_status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

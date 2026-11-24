SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_v_rank_config' AND COLUMN_NAME='monthly_salary_usdt')=0,
  'ALTER TABLE nx_v_rank_config ADD monthly_salary_usdt DECIMAL(18,6) NOT NULL DEFAULT 0',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_user_level_log' AND COLUMN_NAME='salary_usdt')=0,
  'ALTER TABLE nx_user_level_log MODIFY created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), ADD salary_usdt DECIMAL(18,6) NULL',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_v_rank_salary_rate (
  rank_code VARCHAR(16) NOT NULL,
  effective_at DATETIME(6) NOT NULL,
  amount_usdt DECIMAL(18,6) NOT NULL,
  PRIMARY KEY (rank_code, effective_at),
  KEY idx_vrank_salary_effective_at (effective_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_v_rank_salary_claim (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  salary_month DATE NOT NULL,
  rank_code VARCHAR(16) NOT NULL,
  amount_usdt DECIMAL(18,6) NOT NULL,
  claim_no VARCHAR(48) NOT NULL,
  commission_event_id BIGINT NOT NULL,
  claimable_from DATETIME(6) NOT NULL,
  claimed_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_vrank_salary_claim_month (user_id, salary_month),
  UNIQUE KEY uk_vrank_salary_claim_no (claim_no),
  UNIQUE KEY uk_vrank_salary_claim_event (commission_event_id),
  KEY idx_vrank_salary_claim_time (claimed_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

START TRANSACTION;
SELECT lock_key FROM nx_admin_operation_mutex WHERE lock_key='F1_VRANK_BOOTSTRAP' FOR UPDATE;
SET @salary_seed = (SELECT COUNT(*)=9 FROM nx_v_rank_config
  WHERE is_deleted=0 AND rank_code IN ('V0','V1','V2','V3','V4','V5','V6','V7','V8'))
  AND NOT EXISTS (SELECT 1 FROM nx_v_rank_salary_rate);
UPDATE nx_v_rank_config
   SET monthly_salary_usdt=ELT(CAST(SUBSTRING(rank_code,2) AS UNSIGNED)+1,0,0,20,50,150,500,1500,4000,15000)
 WHERE @salary_seed AND is_deleted=0 AND rank_code IN ('V0','V1','V2','V3','V4','V5','V6','V7','V8');
INSERT INTO nx_v_rank_salary_rate(rank_code,effective_at,amount_usdt)
SELECT rank_code,NOW(6),monthly_salary_usdt FROM nx_v_rank_config
 WHERE @salary_seed AND is_deleted=0 AND rank_code IN ('V0','V1','V2','V3','V4','V5','V6','V7','V8');
INSERT INTO nx_audit_log(service_name,action,resource_type,resource_id,actor_type,actor_username,risk_level,detail_json)
SELECT 'nexion-backend','F_VRANK_SALARY_SEEDED','TEAM_POLICY','vrank_salary','SYSTEM','SYSTEM','HIGH',
       JSON_OBJECT('monthlySalaryUsdt',JSON_ARRAY(0,0,20,50,150,500,1500,4000,15000))
 WHERE @salary_seed;
COMMIT;

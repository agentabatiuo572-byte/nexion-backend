-- Device monthly target V3. NULL keeps the pre-target pricing/evidence contract.
-- No history is republished and no wallet trigger is added.
SET @dmt_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='nx_admin_device_sku' AND column_name='monthly_target_usdt'),
  'SELECT 1','ALTER TABLE nx_admin_device_sku ADD COLUMN monthly_target_usdt DECIMAL(12,2) NULL');
PREPARE dmt_stmt FROM @dmt_ddl;
EXECUTE dmt_stmt;
DEALLOCATE PREPARE dmt_stmt;
SET @dmt_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='nx_admin_device_sku' AND column_name='monthly_target_band_pct'),
  'SELECT 1','ALTER TABLE nx_admin_device_sku ADD COLUMN monthly_target_band_pct DECIMAL(4,1) NULL');
PREPARE dmt_stmt FROM @dmt_ddl;
EXECUTE dmt_stmt;
DEALLOCATE PREPARE dmt_stmt;
SET @dmt_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='nx_product' AND column_name='monthly_target_usdt'),
  'SELECT 1','ALTER TABLE nx_product ADD COLUMN monthly_target_usdt DECIMAL(12,2) NULL');
PREPARE dmt_stmt FROM @dmt_ddl;
EXECUTE dmt_stmt;
DEALLOCATE PREPARE dmt_stmt;
SET @dmt_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='nx_product' AND column_name='monthly_target_band_pct'),
  'SELECT 1','ALTER TABLE nx_product ADD COLUMN monthly_target_band_pct DECIMAL(4,1) NULL');
PREPARE dmt_stmt FROM @dmt_ddl;
EXECUTE dmt_stmt;
DEALLOCATE PREPARE dmt_stmt;
SET @dmt_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='nx_sim_product_task_policy_version' AND column_name='monthly_target_usdt'),
  'SELECT 1','ALTER TABLE nx_sim_product_task_policy_version ADD COLUMN monthly_target_usdt DECIMAL(12,2) NULL');
PREPARE dmt_stmt FROM @dmt_ddl;
EXECUTE dmt_stmt;
DEALLOCATE PREPARE dmt_stmt;
SET @dmt_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='nx_sim_product_task_policy_version' AND column_name='monthly_target_band_pct'),
  'SELECT 1','ALTER TABLE nx_sim_product_task_policy_version ADD COLUMN monthly_target_band_pct DECIMAL(4,1) NULL');
PREPARE dmt_stmt FROM @dmt_ddl;
EXECUTE dmt_stmt;
DEALLOCATE PREPARE dmt_stmt;
SET @dmt_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='nx_simulated_task' AND column_name='target_cycle_start'),
  'SELECT 1','ALTER TABLE nx_simulated_task ADD COLUMN target_cycle_start DATETIME(6) NULL');
PREPARE dmt_stmt FROM @dmt_ddl;
EXECUTE dmt_stmt;
DEALLOCATE PREPARE dmt_stmt;
SET @dmt_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='nx_simulated_task' AND column_name='target_snapshot_json'),
  'SELECT 1','ALTER TABLE nx_simulated_task ADD COLUMN target_snapshot_json JSON NULL');
PREPARE dmt_stmt FROM @dmt_ddl;
EXECUTE dmt_stmt;
DEALLOCATE PREPARE dmt_stmt;
SET @dmt_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='nx_commerce_sandbox_catalog' AND column_name='monthly_target_usdt'),
  'SELECT 1','ALTER TABLE nx_commerce_sandbox_catalog ADD COLUMN monthly_target_usdt DECIMAL(12,2) NULL');
PREPARE dmt_stmt FROM @dmt_ddl;
EXECUTE dmt_stmt;
DEALLOCATE PREPARE dmt_stmt;
SET @dmt_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='nx_commerce_sandbox_catalog' AND column_name='monthly_target_band_pct'),
  'SELECT 1','ALTER TABLE nx_commerce_sandbox_catalog ADD COLUMN monthly_target_band_pct DECIMAL(4,1) NULL');
PREPARE dmt_stmt FROM @dmt_ddl;
EXECUTE dmt_stmt;
DEALLOCATE PREPARE dmt_stmt;
-- daily_earn is a mirror; exact monthly_target_usdt remains authoritative.
ALTER TABLE nx_admin_device_sku MODIFY daily_earn DECIMAL(18,6) NOT NULL DEFAULT 0,
  MODIFY share_yield_min DECIMAL(24,4) NULL, MODIFY share_yield_max DECIMAL(24,4) NULL;
ALTER TABLE nx_product MODIFY share_yield_min DECIMAL(24,4) NULL, MODIFY share_yield_max DECIMAL(24,4) NULL;
CREATE TABLE IF NOT EXISTS nx_sim_device_target_cycle (
  user_device_id BIGINT NOT NULL,
  cycle_start DATETIME(6) NOT NULL,
  cycle_end DATETIME(6) NOT NULL,
  cycle_index INT NOT NULL,
  user_id BIGINT NOT NULL,
  instance_no VARCHAR(96) NOT NULL,
  algorithm VARCHAR(32) NOT NULL,
  u DECIMAL(30,24) NOT NULL,
  weights_json JSON NOT NULL,
  p DECIMAL(50,24) NOT NULL DEFAULT 0,
  lo DECIMAL(50,24) NOT NULL DEFAULT 0,
  hi DECIMAL(50,24) NOT NULL DEFAULT 0,
  allocated_usdt DECIMAL(24,6) NOT NULL DEFAULT 0,
  credited_usdt DECIMAL(24,6) NOT NULL DEFAULT 0,
  first_product_version BIGINT NOT NULL,
  first_e3_version BIGINT NOT NULL,
  first_monthly_target_usdt DECIMAL(12,2) NOT NULL,
  first_monthly_target_band_pct DECIMAL(4,1) NOT NULL,
  first_capacity_pct DECIMAL(18,6) NOT NULL,
  config_changed TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY(user_device_id,cycle_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

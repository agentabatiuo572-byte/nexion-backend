CREATE TABLE IF NOT EXISTS nx_sim_task_type_version (
  task_type_id VARCHAR(64) NOT NULL,
  version BIGINT NOT NULL,
  effective_at DATETIME(6) NOT NULL,
  name VARCHAR(128) NOT NULL,
  task_class VARCHAR(8) NOT NULL,
  duration_seconds INT NOT NULL,
  base_reward DECIMAL(18,6) NOT NULL,
  asset VARCHAR(8) NOT NULL,
  enabled TINYINT NOT NULL,
  PRIMARY KEY(task_type_id,version),
  KEY idx_sim_task_type_effective(task_type_id,effective_at,version),
  CHECK(duration_seconds BETWEEN 60 AND 86400 AND MOD(duration_seconds,60)=0),
  CHECK(base_reward>=0), CHECK(asset IN ('USDT','NEX'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS nx_sim_product_task_policy_version (
  product_key VARCHAR(64) NOT NULL,
  version BIGINT NOT NULL,
  effective_at DATETIME(6) NOT NULL,
  allowed_task_type_ids JSON NOT NULL,
  PRIMARY KEY(product_key,version),
  KEY idx_sim_product_policy_effective(product_key,effective_at,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS nx_sim_e3_version (
  version BIGINT NOT NULL PRIMARY KEY,
  effective_at DATETIME(6) NOT NULL,
  config_json JSON NOT NULL,
  KEY idx_sim_e3_effective(effective_at,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @sim_sku_policy_column = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_device_sku'
    AND COLUMN_NAME='allowed_task_type_ids_json')=0,
  'ALTER TABLE nx_admin_device_sku ADD COLUMN allowed_task_type_ids_json JSON NULL', 'SELECT 1');
PREPARE sim_sku_policy_stmt FROM @sim_sku_policy_column;
EXECUTE sim_sku_policy_stmt;
DEALLOCATE PREPARE sim_sku_policy_stmt;

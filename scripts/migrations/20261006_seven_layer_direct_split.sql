-- One order owns one immutable generation and all seven budget decisions. No production cutover is seeded.
CREATE TABLE IF NOT EXISTS nx_unilevel_order_settlement (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 source_environment VARCHAR(16) NOT NULL, run_id VARCHAR(96) NOT NULL DEFAULT '',
 order_no VARCHAR(96) COLLATE utf8mb4_bin NOT NULL, source_user_id BIGINT NOT NULL,
 source_occurred_at DATETIME(6) NOT NULL, prepared_at DATETIME(6) NOT NULL,
 settlement_mode VARCHAR(24) NOT NULL, split_enabled TINYINT NOT NULL DEFAULT 0,
 policy_version BIGINT NOT NULL, seven_layer_revision BIGINT NOT NULL,
 policy_snapshot JSON NOT NULL, chain_and_rules JSON NOT NULL,
 allocated_budget_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
 status VARCHAR(32) NOT NULL, refund_confirmed TINYINT NOT NULL DEFAULT 0,
 reason VARCHAR(200) NOT NULL DEFAULT '',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_unilevel_order_source (source_environment,run_id,order_no),
 KEY idx_unilevel_order_pending (status,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_direct_referral_settlement' AND COLUMN_NAME='layer_no')=0,
 'ALTER TABLE nx_direct_referral_settlement ADD COLUMN layer_no INT NOT NULL DEFAULT 1 AFTER source_ref, ADD COLUMN price_locked_at DATETIME(6) NULL AFTER nex_usdt_price', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_direct_referral_settlement' AND INDEX_NAME='uk_direct_source' AND COLUMN_NAME='layer_no')=0,
 'ALTER TABLE nx_direct_referral_settlement DROP INDEX uk_direct_source, ADD UNIQUE KEY uk_direct_source (source_environment,run_id,source_type,source_ref,layer_no)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
INSERT IGNORE INTO nx_config_item(config_key,config_value,value_type,config_group,visibility,remark,status,is_deleted)
VALUES('team.seven-layer.revision','0','NUMBER','team','ADMIN','七层配置一致性版本锁',1,0);

CREATE TABLE IF NOT EXISTS nx_unilevel_event_recovery (
 event_id BIGINT NOT NULL PRIMARY KEY, order_no VARCHAR(96) NOT NULL, source_environment VARCHAR(16) NOT NULL,
 run_id VARCHAR(96) NOT NULL DEFAULT '', user_id BIGINT NOT NULL, asset VARCHAR(8) NOT NULL,
 target_amount DECIMAL(18,6) NOT NULL, recovered_amount DECIMAL(18,6) NOT NULL DEFAULT 0,
 pending_amount DECIMAL(18,6) NOT NULL DEFAULT 0, updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 KEY idx_unilevel_recovery_source (source_environment,run_id,order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @seven_asset_columns_added = (SELECT COUNT(*)=0 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_direct_referral_settlement' AND COLUMN_NAME='cancelled_usdt');
SET @sql = IF(@seven_asset_columns_added=1,
 'ALTER TABLE nx_direct_referral_settlement ADD COLUMN credited_usdt_at DATETIME(3) NULL AFTER credited_at, ADD COLUMN credited_nex_at DATETIME(3) NULL AFTER credited_usdt_at, ADD COLUMN cancelled_usdt DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER amount_nex, ADD COLUMN cancelled_nex DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER cancelled_usdt', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
UPDATE nx_direct_referral_settlement SET credited_usdt_at=COALESCE(credited_usdt_at,credited_at) WHERE @seven_asset_columns_added=1 AND credited_at IS NOT NULL AND amount_usdt>0;
UPDATE nx_direct_referral_settlement SET credited_nex_at=COALESCE(credited_nex_at,credited_at) WHERE @seven_asset_columns_added=1 AND credited_at IS NOT NULL AND amount_nex>0;
UPDATE nx_direct_referral_settlement SET cancelled_usdt=amount_usdt,cancelled_nex=amount_nex WHERE @seven_asset_columns_added=1 AND reversal_recorded=1;

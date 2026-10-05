CREATE TABLE IF NOT EXISTS nx_direct_referral_policy (
  policy_version BIGINT NOT NULL PRIMARY KEY,
  effective_at DATETIME(6) NOT NULL,
  purchase_json JSON NOT NULL,
  device_earning_json JSON NOT NULL,
  operation_id VARCHAR(96) NOT NULL,
  reason VARCHAR(200) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_direct_policy_effective (effective_at, policy_version),
  UNIQUE KEY uk_direct_policy_operation (operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_direct_referral_settlement (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  settlement_no VARCHAR(64) NOT NULL,
  source_environment VARCHAR(16) NOT NULL,
  run_id VARCHAR(96) NOT NULL DEFAULT '',
  source_type VARCHAR(32) NOT NULL,
  source_ref VARCHAR(96) COLLATE utf8mb4_bin NOT NULL,
  source_user_id BIGINT NOT NULL,
  beneficiary_user_id BIGINT NULL,
  source_user_name VARCHAR(64) NULL,
  source_device_id BIGINT NULL,
  source_occurred_at DATETIME(6) NOT NULL,
  policy_version BIGINT NOT NULL,
  policy_snapshot JSON NOT NULL,
  basis_usdt DECIMAL(24,12) NOT NULL DEFAULT 0,
  nex_usdt_price DECIMAL(24,12) NULL,
  amount_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  amount_nex DECIMAL(18,6) NOT NULL DEFAULT 0,
  usdt_event_id BIGINT NULL,
  nex_event_id BIGINT NULL,
  status VARCHAR(32) NOT NULL,
  release_at DATETIME(6) NOT NULL,
  credited_at DATETIME(3) NULL,
  reversal_recorded TINYINT NOT NULL DEFAULT 0,
  refund_ratio DECIMAL(18,12) NOT NULL DEFAULT 0,
  recovered_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  recovered_nex DECIMAL(18,6) NOT NULL DEFAULT 0,
  recovery_pending_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  recovery_pending_nex DECIMAL(18,6) NOT NULL DEFAULT 0,
  reason VARCHAR(200) NOT NULL DEFAULT '',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_direct_source (source_environment,run_id,source_type,source_ref),
  UNIQUE KEY uk_direct_settlement (settlement_no),
  UNIQUE KEY uk_direct_usdt_event (usdt_event_id),
  UNIQUE KEY uk_direct_nex_event (nex_event_id),
  KEY idx_direct_recipient (beneficiary_user_id,source_environment,created_at),
  KEY idx_direct_status (status,release_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE nx_commission_event MODIFY COLUMN order_no VARCHAR(128) NULL;

-- Preserve ordering across approval and sources in the same second, including task/receipt joins and binary snapshots.
ALTER TABLE nx_order MODIFY COLUMN paid_at DATETIME(6) NULL;
ALTER TABLE nx_compute_task MODIFY COLUMN completed_at DATETIME(6) NULL;
ALTER TABLE nx_compute_receipt MODIFY COLUMN completed_at DATETIME(6) NOT NULL;
ALTER TABLE nx_direct_referral_policy MODIFY COLUMN effective_at DATETIME(6) NOT NULL;
ALTER TABLE nx_direct_referral_settlement MODIFY COLUMN source_occurred_at DATETIME(6) NOT NULL,
  MODIFY COLUMN release_at DATETIME(6) NOT NULL;
SET @direct_sql = IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_binary_paid_order_volume'),
  'ALTER TABLE nx_binary_paid_order_volume MODIFY COLUMN paid_at DATETIME(6) NOT NULL', 'SELECT 1');
PREPARE direct_stmt FROM @direct_sql; EXECUTE direct_stmt; DEALLOCATE PREPARE direct_stmt;
ALTER TABLE nx_audit_operation_ticket MODIFY COLUMN before_value VARCHAR(1024) NOT NULL,
  MODIFY COLUMN after_value VARCHAR(1024) NOT NULL;

-- Establish the mutex at migration time; INSERT IGNORE in each approval would acquire shared locks before FOR UPDATE.
INSERT IGNORE INTO nx_config_item(config_key,config_value,value_type,config_group,visibility,remark,status,is_deleted)
VALUES('team.direct-referral.policy-version','0','NUMBER','team','ADMIN','直属分成版本锁',1,0);

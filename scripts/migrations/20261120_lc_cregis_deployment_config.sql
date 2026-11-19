-- Schema-only replacement for the legacy auto-forward bootstrap and multi-network migration.
-- The application installs disabled policies from deployment configuration after schema bootstrap.
-- Existing policies and tasks retain their project, network, approval, and submission state.
CREATE TABLE IF NOT EXISTS nx_cregis_auto_forward_policy (
 project_id BIGINT NOT NULL PRIMARY KEY,
 collection_enabled TINYINT NOT NULL DEFAULT 0,
 payment_enabled TINYINT NOT NULL DEFAULT 0,
 start_event_id BIGINT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 pending_collection TINYINT NULL,
 pending_payment TINYINT NULL,
 maker_id BIGINT NULL,
 reason VARCHAR(300) NULL,
 proposed_at DATETIME NULL,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_cregis_auto_forward_task (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 project_id BIGINT NOT NULL,
 event_id BIGINT NOT NULL,
 source_address VARCHAR(42) NOT NULL,
 platform_address VARCHAR(42) NOT NULL,
 counterparty_address VARCHAR(42) NOT NULL,
 gross_amount DECIMAL(18,6) NOT NULL,
 net_amount DECIMAL(18,6) NOT NULL,
 fee_amount DECIMAL(18,6) NOT NULL,
 payment_key VARCHAR(70) NOT NULL,
 state VARCHAR(30) NOT NULL DEFAULT 'WAIT_COLLECTION',
 collection_cid BIGINT NULL,
 collection_txid VARCHAR(66) NULL,
 collection_floor BIGINT NULL,
 payment_cid BIGINT NULL,
 payment_txid VARCHAR(66) NULL,
 payment_floor BIGINT NULL,
 intent_at DATETIME NULL,
 last_error VARCHAR(100) NULL,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 active_source VARCHAR(42) GENERATED ALWAYS AS (IF(state='COMPLETED',NULL,source_address)) STORED,
 UNIQUE KEY uk_cregis_auto_event(project_id,event_id),
 UNIQUE KEY uk_cregis_auto_source(project_id,active_source),
 UNIQUE KEY uk_cregis_auto_payment_key(payment_key),
 UNIQUE KEY uk_cregis_auto_collection_cid(project_id,collection_cid),
 UNIQUE KEY uk_cregis_auto_payment_cid(project_id,payment_cid),
 KEY ix_cregis_auto_page(project_id,id),
 CONSTRAINT ck_cregis_auto_money CHECK (gross_amount>0 AND net_amount>0 AND fee_amount>=0 AND gross_amount=net_amount+fee_amount),
 CONSTRAINT ck_cregis_auto_state CHECK(state IN ('WAIT_COLLECTION','COLLECTION_SUBMITTING','COLLECTION_PENDING','COLLECTION_UNKNOWN','COLLECTION_FAILED','WAIT_PAYMENT','PAYMENT_SUBMITTING','PAYMENT_PENDING','PAYMENT_UNKNOWN','PAYMENT_FAILED','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_auto_forward_policy' AND COLUMN_NAME='chain_id'),'SELECT 1','ALTER TABLE nx_cregis_auto_forward_policy ADD COLUMN chain_id VARCHAR(16) NOT NULL DEFAULT ''2510''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_auto_forward_policy' AND INDEX_NAME='PRIMARY' AND COLUMN_NAME='chain_id'),'SELECT 1','ALTER TABLE nx_cregis_auto_forward_policy DROP PRIMARY KEY, ADD PRIMARY KEY(project_id,chain_id)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_auto_forward_task' AND COLUMN_NAME='chain_id'),'SELECT 1','ALTER TABLE nx_cregis_auto_forward_task ADD COLUMN chain_id VARCHAR(16) NOT NULL DEFAULT ''2510''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- TRON addresses and the generated active-source key compare byte-for-byte.
SET @sql=IF(EXISTS(
 SELECT 1 FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_auto_forward_task'
   AND COLUMN_NAME IN ('source_address','platform_address','counterparty_address','active_source')
   AND COLLATION_NAME<>'utf8mb4_bin'
),
 'ALTER TABLE nx_cregis_auto_forward_task
  MODIFY source_address VARCHAR(42) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  MODIFY platform_address VARCHAR(42) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  MODIFY counterparty_address VARCHAR(42) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  MODIFY active_source VARCHAR(42) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin GENERATED ALWAYS AS (IF(state=''COMPLETED'',NULL,source_address)) STORED',
 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_auto_forward_task' AND INDEX_NAME='uk_cregis_auto_source' AND COLUMN_NAME='chain_id'),'SELECT 1','ALTER TABLE nx_cregis_auto_forward_task DROP INDEX uk_cregis_auto_source, ADD UNIQUE KEY uk_cregis_auto_source(project_id,chain_id,active_source)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_auto_forward_policy' AND CONSTRAINT_NAME='ck_cregis_auto_policy_network'),'SELECT 1','ALTER TABLE nx_cregis_auto_forward_policy ADD CONSTRAINT ck_cregis_auto_policy_network CHECK(chain_id IN (''2510'',''60'',''195''))');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_auto_forward_task' AND CONSTRAINT_NAME='ck_cregis_auto_task_network'),'SELECT 1','ALTER TABLE nx_cregis_auto_forward_task ADD CONSTRAINT ck_cregis_auto_task_network CHECK(chain_id IN (''2510'',''60'',''195''))');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Forward-only TRON transport schema. This migration grants no new funds permission.
SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_network_switch' AND CONSTRAINT_NAME='chk_cregis_network_switch') AND NOT EXISTS(SELECT 1 FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='chk_cregis_network_switch' AND CHECK_CLAUSE LIKE '%TRC20%'), 'ALTER TABLE nx_cregis_network_switch DROP CHECK chk_cregis_network_switch','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_network_switch' AND CONSTRAINT_NAME='chk_cregis_network_switch'),'SELECT 1','ALTER TABLE nx_cregis_network_switch ADD CONSTRAINT chk_cregis_network_switch CHECK(network IN (''BEP20'',''ERC20'',''TRC20''))');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
INSERT IGNORE INTO nx_cregis_network_switch(network,deposit_enabled) VALUES('TRC20',0);
INSERT IGNORE INTO nx_cregis_address_pool_policy(id) VALUES(3);
-- Existing D5 values and BEP20/ERC20 switches are deliberately retained.

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='chk_cregis_policy_snapshot' AND CHECK_CLAUSE NOT LIKE '%TRON%'),'ALTER TABLE nx_cregis_deposit_policy_snapshot DROP CHECK chk_cregis_policy_snapshot','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='chk_cregis_policy_snapshot'),'SELECT 1','ALTER TABLE nx_cregis_deposit_policy_snapshot ADD CONSTRAINT chk_cregis_policy_snapshot CHECK(project_id>0 AND required_confirmations>=15 AND ((chain_id=''TRON'' AND token_id=''USDT-TRC20'' AND txid REGEXP ''^[0-9a-f]{64}$'') OR (chain_id IN (''BSC'',''ETH'') AND txid REGEXP ''^0x[0-9a-f]{64}$'')))');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_exception_claim' AND COLUMN_NAME='chain_id'),'SELECT 1','ALTER TABLE nx_cregis_exception_claim ADD COLUMN chain_id VARCHAR(16) NOT NULL DEFAULT ''2510''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_exception_refund' AND COLUMN_NAME='chain_id'),'SELECT 1','ALTER TABLE nx_cregis_exception_refund ADD COLUMN chain_id VARCHAR(16) NOT NULL DEFAULT ''2510''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Canonical Base58Check addresses compare byte-for-byte. Existing EVM values are not rewritten.

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_deposit_address' AND COLUMN_NAME='address' AND COLLATION_NAME='utf8mb4_bin'),'SELECT 1','ALTER TABLE nx_cregis_deposit_address MODIFY COLUMN address VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_deposit_delivery' AND COLUMN_NAME='address' AND COLLATION_NAME='utf8mb4_bin'),'SELECT 1','ALTER TABLE nx_cregis_deposit_delivery MODIFY COLUMN address VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_deposit_event' AND COLUMN_NAME='address' AND COLLATION_NAME='utf8mb4_bin'),'SELECT 1','ALTER TABLE nx_cregis_deposit_event MODIFY COLUMN address VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_deposit_event' AND COLUMN_NAME='transfer_from_address' AND COLLATION_NAME='utf8mb4_bin'),'SELECT 1','ALTER TABLE nx_cregis_deposit_event MODIFY COLUMN transfer_from_address VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_chain_observation' AND COLUMN_NAME='address' AND COLLATION_NAME='utf8mb4_bin'),'SELECT 1','ALTER TABLE nx_cregis_chain_observation MODIFY COLUMN address VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_exception_source' AND COLUMN_NAME='address' AND COLLATION_NAME='utf8mb4_bin'),'SELECT 1','ALTER TABLE nx_cregis_exception_source MODIFY COLUMN address VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_exception_claim' AND COLUMN_NAME='address' AND COLLATION_NAME='utf8mb4_bin'),'SELECT 1','ALTER TABLE nx_cregis_exception_claim MODIFY COLUMN address VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_exception_claim' AND COLUMN_NAME='from_address' AND COLLATION_NAME='utf8mb4_bin'),'SELECT 1','ALTER TABLE nx_cregis_exception_claim MODIFY COLUMN from_address VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_ownership_challenge' AND COLUMN_NAME='signer' AND COLLATION_NAME='utf8mb4_bin'),'SELECT 1','ALTER TABLE nx_cregis_ownership_challenge MODIFY COLUMN signer VARCHAR(42) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_exception_refund' AND COLUMN_NAME='return_address' AND COLLATION_NAME='utf8mb4_bin'),'SELECT 1','ALTER TABLE nx_cregis_exception_refund MODIFY COLUMN return_address VARCHAR(42) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_exception_refund' AND COLUMN_NAME='deposit_address' AND COLLATION_NAME='utf8mb4_bin'),'SELECT 1','ALTER TABLE nx_cregis_exception_refund MODIFY COLUMN deposit_address VARCHAR(42) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_purchase_payment_intent' AND COLUMN_NAME='address' AND COLLATION_NAME='utf8mb4_bin'),'SELECT 1','ALTER TABLE nx_purchase_payment_intent MODIFY COLUMN address VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_purchase_payment_arrival' AND COLUMN_NAME='from_address' AND COLLATION_NAME='utf8mb4_bin'),'SELECT 1','ALTER TABLE nx_purchase_payment_arrival MODIFY COLUMN from_address VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

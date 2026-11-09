-- Forward-only: preserve existing BEP20 policy, intents, CIDs and watermarks. New networks stay OFF.
SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_auto_forward_policy' AND COLUMN_NAME='chain_id'),'SELECT 1','ALTER TABLE nx_cregis_auto_forward_policy ADD COLUMN chain_id VARCHAR(16) NOT NULL DEFAULT ''2510''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_auto_forward_policy' AND INDEX_NAME='PRIMARY' AND COLUMN_NAME='chain_id'),'SELECT 1','ALTER TABLE nx_cregis_auto_forward_policy DROP PRIMARY KEY, ADD PRIMARY KEY(project_id,chain_id)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
INSERT IGNORE INTO nx_cregis_auto_forward_policy(project_id,chain_id) SELECT project_id,'60' FROM nx_cregis_auto_forward_policy WHERE chain_id='2510';
INSERT IGNORE INTO nx_cregis_auto_forward_policy(project_id,chain_id) SELECT project_id,'195' FROM nx_cregis_auto_forward_policy WHERE chain_id='2510';

SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_auto_forward_task' AND COLUMN_NAME='chain_id'),'SELECT 1','ALTER TABLE nx_cregis_auto_forward_task ADD COLUMN chain_id VARCHAR(16) NOT NULL DEFAULT ''2510''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
-- TRON addresses must compare byte-for-byte, including the generated active-source key.
ALTER TABLE nx_cregis_auto_forward_task
 MODIFY source_address VARCHAR(42) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 MODIFY platform_address VARCHAR(42) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 MODIFY counterparty_address VARCHAR(42) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 MODIFY active_source VARCHAR(42) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin GENERATED ALWAYS AS (IF(state='COMPLETED',NULL,source_address)) STORED;
SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_auto_forward_task' AND INDEX_NAME='uk_cregis_auto_source' AND COLUMN_NAME='chain_id'),'SELECT 1','ALTER TABLE nx_cregis_auto_forward_task DROP INDEX uk_cregis_auto_source, ADD UNIQUE KEY uk_cregis_auto_source(project_id,chain_id,active_source)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_auto_forward_policy' AND CONSTRAINT_NAME='ck_cregis_auto_policy_network'),'SELECT 1','ALTER TABLE nx_cregis_auto_forward_policy ADD CONSTRAINT ck_cregis_auto_policy_network CHECK(chain_id IN (''2510'',''60'',''195''))');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_auto_forward_task' AND CONSTRAINT_NAME='ck_cregis_auto_task_network'),'SELECT 1','ALTER TABLE nx_cregis_auto_forward_task ADD CONSTRAINT ck_cregis_auto_task_network CHECK(chain_id IN (''2510'',''60'',''195''))');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

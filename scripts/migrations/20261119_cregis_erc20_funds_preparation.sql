-- Forward-only: give manual funds-preparation plans a network column.
-- Existing rows are BEP20. The business-reference unique key stays cross-network:
-- one business may be prepared only once, never once per network.
SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_funds_preparation_plan' AND COLUMN_NAME='network'),'SELECT 1','ALTER TABLE nx_cregis_funds_preparation_plan ADD COLUMN network VARCHAR(10) NOT NULL DEFAULT ''BEP20''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_funds_preparation_plan' AND CONSTRAINT_NAME='ck_cregis_preparation_network'),'SELECT 1','ALTER TABLE nx_cregis_funds_preparation_plan ADD CONSTRAINT ck_cregis_preparation_network CHECK(network IN (''BEP20'',''ERC20''))');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_funds_preparation_plan' AND INDEX_NAME='ix_cregis_preparation_page' AND COLUMN_NAME='network'),'SELECT 1','ALTER TABLE nx_cregis_funds_preparation_plan DROP INDEX ix_cregis_preparation_page, ADD KEY ix_cregis_preparation_page(project_id,network,id)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

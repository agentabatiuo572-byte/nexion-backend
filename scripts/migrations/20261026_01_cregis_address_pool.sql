-- Candidate verification has an independent durable cursor, separate from the global scan.
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_deposit_address'
 AND COLUMN_NAME='verification_block')=0,
 'ALTER TABLE nx_cregis_deposit_address ADD COLUMN verification_block BIGINT NULL AFTER creation_block',
 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_deposit_address'
 AND INDEX_NAME='idx_cregis_pool_state')=0,
 'ALTER TABLE nx_cregis_deposit_address ADD INDEX idx_cregis_pool_state(project_id,chain_id,state,id)',
 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

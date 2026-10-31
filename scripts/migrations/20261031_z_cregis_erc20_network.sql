-- Apply after the purchase direct-payment migration. Existing funds switches stay unchanged.
-- Historical rows are all USDT-BEP20; ERC20 requires separate explicit configuration approval.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_cregis_deposit_event' AND COLUMN_NAME='chain_id')=0,
 'ALTER TABLE nx_cregis_deposit_event ADD COLUMN chain_id VARCHAR(16) NOT NULL DEFAULT ''2510''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_cregis_chain_observation' AND COLUMN_NAME='chain_id')=0,
 'ALTER TABLE nx_cregis_chain_observation ADD COLUMN chain_id VARCHAR(16) NOT NULL DEFAULT ''2510''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_cregis_deposit_delivery' AND COLUMN_NAME='chain_id')=0,
 'ALTER TABLE nx_cregis_deposit_delivery ADD COLUMN chain_id VARCHAR(16) NOT NULL DEFAULT ''2510''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_cregis_exception_source' AND COLUMN_NAME='chain_id')=0,
 'ALTER TABLE nx_cregis_exception_source ADD COLUMN chain_id VARCHAR(16) NOT NULL DEFAULT ''2510''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_cregis_reconcile_run' AND COLUMN_NAME='chain_id')=0,
 'ALTER TABLE nx_cregis_reconcile_run ADD COLUMN chain_id VARCHAR(16) NOT NULL DEFAULT ''2510''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_purchase_payment_arrival' AND COLUMN_NAME='chain_id')=0,
 'ALTER TABLE nx_purchase_payment_arrival ADD COLUMN chain_id VARCHAR(16) NOT NULL DEFAULT ''2510''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_cregis_deposit_event' AND INDEX_NAME='uk_cregis_event_log' AND COLUMN_NAME='chain_id')=0,
 'ALTER TABLE nx_cregis_deposit_event DROP INDEX uk_cregis_event_log, ADD UNIQUE KEY uk_cregis_event_log (project_id,chain_id,txid,log_index)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_cregis_chain_observation' AND INDEX_NAME='uk_cregis_observation_log' AND COLUMN_NAME='chain_id')=0,
 'ALTER TABLE nx_cregis_chain_observation DROP INDEX uk_cregis_observation_log, ADD UNIQUE KEY uk_cregis_observation_log (project_id,chain_id,txid,log_index)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_deposit_order' AND INDEX_NAME='uk_deposit_chain_tx_asset_log' AND COLUMN_NAME='chain_name')=0,
 'ALTER TABLE nx_deposit_order DROP INDEX uk_deposit_chain_tx_asset_log, ADD UNIQUE KEY uk_deposit_chain_tx_asset_log (chain_name,chain_tx_hash,asset,chain_log_index)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_purchase_payment_intent' AND INDEX_NAME='uk_purchase_address' AND COLUMN_NAME='payment_method')=0,
 'ALTER TABLE nx_purchase_payment_intent DROP INDEX uk_purchase_address, ADD UNIQUE KEY uk_purchase_address (payment_method,address)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_purchase_payment_arrival' AND INDEX_NAME='uk_purchase_arrival' AND COLUMN_NAME='chain_id')=0,
 'ALTER TABLE nx_purchase_payment_arrival DROP INDEX uk_purchase_arrival, ADD UNIQUE KEY uk_purchase_arrival (provider_project,chain_id,txid,log_index)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='nx_cregis_exception_source' AND INDEX_NAME='PRIMARY' AND COLUMN_NAME='chain_id')=0,
 'ALTER TABLE nx_cregis_exception_source DROP PRIMARY KEY, ADD PRIMARY KEY(project_id,chain_id,txid,log_index)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
-- Runtime refill/retry state and cursors are independent; the editable pool target stays shared.
INSERT IGNORE INTO nx_cregis_address_pool_policy(id) VALUES(2);

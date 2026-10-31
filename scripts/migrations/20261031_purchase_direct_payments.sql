CREATE TABLE IF NOT EXISTS nx_purchase_payment_policy (
 id TINYINT PRIMARY KEY,version BIGINT NOT NULL,policy_json JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
INSERT IGNORE INTO nx_purchase_payment_policy(id,version,policy_json) VALUES
(1,1,JSON_ARRAY(JSON_OBJECT('paymentMethod','HDPAY_BANK','configured',false,'channelFeeRatePct',NULL,'channelFeeFixed',NULL,'networkConfirmFeeUsdt',NULL,'minTopupPrincipal','10'),
JSON_OBJECT('paymentMethod','CREGIS_USDT_BSC','configured',false,'channelFeeRatePct',NULL,'channelFeeFixed',NULL,'networkConfirmFeeUsdt',NULL,'minTopupPrincipal','1')));
CREATE TABLE IF NOT EXISTS nx_purchase_payment_quote (
 quote_id VARCHAR(48) PRIMARY KEY,user_id BIGINT NOT NULL,kind VARCHAR(24) NOT NULL,payment_method VARCHAR(24) NOT NULL,
 payload_json JSON NOT NULL,quote_json JSON NOT NULL,pricing_hash CHAR(64) NOT NULL,expires_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),KEY idx_purchase_quote_owner(user_id,created_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS nx_purchase_payment_intent (
 intent_no VARCHAR(48) PRIMARY KEY,quote_id VARCHAR(48) NOT NULL,user_id BIGINT NOT NULL,kind VARCHAR(24) NOT NULL,
 payment_method VARCHAR(24) NOT NULL,command_key VARCHAR(128) NOT NULL,payload_json JSON NOT NULL,quote_json JSON NOT NULL,
 pricing_hash CHAR(64) NOT NULL,status VARCHAR(24) NOT NULL,reason VARCHAR(128) NOT NULL DEFAULT '',
 provider_reference VARCHAR(128) NULL,provider_actual_fee_usdt DECIMAL(24,6) NULL,payment_url VARCHAR(2048) NULL,
 address VARCHAR(64) NULL,scan_block BIGINT NULL,provider_project BIGINT NULL,receipt_json JSON NULL,
 expires_at DATETIME(6) NOT NULL,confirmed_at DATETIME(6) NULL,observed_at DATETIME(6) NULL,version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_purchase_quote(quote_id),UNIQUE KEY uk_purchase_command(user_id,kind,command_key),UNIQUE KEY uk_purchase_address(address),
 KEY idx_purchase_status(status,updated_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS nx_purchase_payment_arrival (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,intent_no VARCHAR(48) NOT NULL,provider_project BIGINT NOT NULL,
 txid VARCHAR(128) NOT NULL,log_index INT NOT NULL,amount DECIMAL(38,18) NOT NULL,block_number BIGINT NOT NULL,
 block_hash VARCHAR(66) NOT NULL,confirmations INT NOT NULL,from_address VARCHAR(64) NULL,status VARCHAR(24) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),UNIQUE KEY uk_purchase_arrival(provider_project,txid,log_index),
 KEY idx_purchase_arrival_intent(intent_no)
) ENGINE=InnoDB;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_cregis_deposit_address' AND COLUMN_NAME='purpose')=0,
 'ALTER TABLE nx_cregis_deposit_address ADD COLUMN purpose VARCHAR(24) NOT NULL DEFAULT ''WALLET_TOPUP'',ADD COLUMN purchase_intent_no VARCHAR(48) NULL','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

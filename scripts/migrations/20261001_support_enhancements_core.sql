-- Incremental, repeatable migration. Run only in the explicitly selected catalog.
-- Existing assignments, financial facts and conversation status are preserved.
DELIMITER $$
DROP PROCEDURE IF EXISTS support_enhancements_core_migrate$$
CREATE PROCEDURE support_enhancements_core_migrate()
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_customer_note' AND column_name='author_admin_id') THEN
  ALTER TABLE nx_customer_note ADD COLUMN author_admin_id BIGINT NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_admin_account_state' AND column_name='avatar_asset_id') THEN
  ALTER TABLE nx_admin_account_state ADD COLUMN avatar_asset_id VARCHAR(36) NULL,ADD COLUMN avatar_version BIGINT NOT NULL DEFAULT 0;
 END IF;
 CREATE TABLE IF NOT EXISTS nx_support_admin_avatar_asset (
  id VARCHAR(36) PRIMARY KEY,uploader_id BIGINT NOT NULL,client_upload_id VARCHAR(128) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,request_hash CHAR(64) NOT NULL,mime VARCHAR(32) NOT NULL,
  byte_count BIGINT NOT NULL,object_key VARCHAR(255) NOT NULL,state VARCHAR(16) NOT NULL DEFAULT 'READY',
  attached_admin_id BIGINT NULL,expires_at DATETIME(6) NOT NULL,created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_upload(uploader_id,client_upload_id),UNIQUE KEY uk_command(uploader_id,idempotency_key)
 ) ENGINE=InnoDB;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_support_human_message' AND column_name='sku_id') THEN
  ALTER TABLE nx_support_human_message ADD COLUMN sku_id VARCHAR(64) NULL,ADD COLUMN sku_name VARCHAR(255) NULL,ADD COLUMN link_target_json JSON NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_conversation' AND column_name='archived') THEN
  ALTER TABLE nx_conversation ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_support_rules' AND column_name='unbound_assignment_mode') THEN
  ALTER TABLE nx_support_rules ADD COLUMN unbound_assignment_mode VARCHAR(16) NOT NULL DEFAULT 'SUPERVISOR', ADD COLUMN mode_effective_at DATETIME(6) NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_support_binding_pool' AND column_name='auto_eligible') THEN
  ALTER TABLE nx_support_binding_pool ADD COLUMN auto_eligible BOOLEAN NOT NULL DEFAULT FALSE,
   ADD COLUMN auto_rule_version BIGINT NULL, ADD COLUMN auto_attempt_state VARCHAR(24) NOT NULL DEFAULT 'NONE',
   ADD COLUMN attempts INT NOT NULL DEFAULT 0, ADD COLUMN last_attempt_at DATETIME(6) NULL,
   ADD COLUMN last_outcome VARCHAR(64) NULL, ADD COLUMN operation_id VARCHAR(128) NULL;
 END IF;
 CREATE TABLE IF NOT EXISTS nx_support_random_preview (
  id VARCHAR(36) PRIMARY KEY,actor_id BIGINT NOT NULL,rules_version BIGINT NOT NULL,
  customers_json JSON NOT NULL,excluded_json JSON NOT NULL,created_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL, INDEX idx_actor(actor_id,created_at)
 ) ENGINE=InnoDB;
 CREATE TABLE IF NOT EXISTS nx_support_random_result (
  operation_id VARCHAR(128) NOT NULL,actor_id BIGINT NOT NULL,preview_id VARCHAR(36) NOT NULL,
  customer_id BIGINT NOT NULL,status VARCHAR(24) NOT NULL,assignment_id BIGINT NULL,
  agent_admin_id BIGINT NULL,outcome VARCHAR(64) NOT NULL,created_at DATETIME(6) NOT NULL,
  PRIMARY KEY(actor_id,operation_id,customer_id)
 ) ENGINE=InnoDB;
 CREATE TABLE IF NOT EXISTS nx_support_random_operation (
  actor_id BIGINT NOT NULL,operation_id VARCHAR(128) NOT NULL,preview_id VARCHAR(36) NOT NULL,
  request_hash CHAR(64) NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
  created_at DATETIME(6) NOT NULL,updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(actor_id,operation_id)
 ) ENGINE=InnoDB;
END$$
CALL support_enhancements_core_migrate()$$
DROP PROCEDURE support_enhancements_core_migrate$$
DELIMITER ;

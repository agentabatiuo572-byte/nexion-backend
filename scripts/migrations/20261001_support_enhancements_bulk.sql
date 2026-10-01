-- Additive, repeatable migration. No existing customer, message or financial fact is changed.
CREATE TABLE IF NOT EXISTS nx_support_bulk_job (
  id CHAR(36) NOT NULL PRIMARY KEY,
  record_type VARCHAR(8) NOT NULL,
  actor_id BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL,
  version BIGINT NOT NULL DEFAULT 1,
  command_key VARCHAR(128) NULL,
  client_upload_id VARCHAR(128) NULL,
  request_hash CHAR(64) NULL,
  selection_mode VARCHAR(24) NULL,
  filters_json JSON NULL,
  excluded_json JSON NULL,
  content_json JSON NULL,
  asset_id CHAR(36) NULL,
  asset_json JSON NULL,
  frozen_count BIGINT NOT NULL DEFAULT 0,
  cancel_requested TINYINT NOT NULL DEFAULT 0,
  evaluated_at DATETIME(6) NULL,
  expires_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  updated_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  UNIQUE KEY uq_support_bulk_command (actor_id,record_type,command_key),
  UNIQUE KEY uq_support_bulk_upload (actor_id,record_type,client_upload_id),
  KEY ix_support_bulk_scan (record_type,state,updated_at,id),
  KEY ix_support_bulk_actor (actor_id,record_type,created_at,id),
  KEY ix_support_bulk_asset_expiry (record_type,state,expires_at),
  CONSTRAINT ck_support_bulk_record CHECK (record_type IN ('JOB','ASSET')),
  CONSTRAINT ck_support_bulk_count CHECK (frozen_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Several private per-customer references may own the same controlled bulk bytes.
-- Preserve every other original uniqueness constraint and all historical attachment rows.
SET @support_bulk_drop_object_unique = IF(EXISTS(
  SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='nx_support_attachment' AND index_name='uk_support_attachment_object' AND non_unique=0
), 'ALTER TABLE nx_support_attachment DROP INDEX uk_support_attachment_object', 'SELECT 1');
PREPARE support_bulk_migration FROM @support_bulk_drop_object_unique;
EXECUTE support_bulk_migration;
DEALLOCATE PREPARE support_bulk_migration;
SET @support_bulk_add_object_lookup = IF(EXISTS(
  SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='nx_support_attachment' AND index_name='ix_support_attachment_object'
), 'SELECT 1', 'ALTER TABLE nx_support_attachment ADD INDEX ix_support_attachment_object(object_key)');
PREPARE support_bulk_migration FROM @support_bulk_add_object_lookup;
EXECUTE support_bulk_migration;
DEALLOCATE PREPARE support_bulk_migration;

CREATE TABLE IF NOT EXISTS nx_support_bulk_recipient (
  batch_id CHAR(36) NOT NULL,
  customer_id BIGINT NOT NULL,
  expected_assignment_id BIGINT NOT NULL,
  client_message_id VARCHAR(128) NOT NULL,
  attachment_id CHAR(36) NULL,
  operation VARCHAR(128) NULL,
  conversation_no VARCHAR(128) NULL,
  request_json JSON NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  result_certainty VARCHAR(8) NOT NULL DEFAULT 'KNOWN',
  message_id BIGINT NULL,
  failure_code VARCHAR(128) NULL,
  retryable TINYINT NOT NULL DEFAULT 0,
  attempts INT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  updated_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
  PRIMARY KEY (batch_id,customer_id),
  UNIQUE KEY uq_support_bulk_client (client_message_id),
  UNIQUE KEY uq_support_bulk_attachment (attachment_id),
  KEY ix_support_bulk_recipient_scan (state,batch_id,customer_id),
  CONSTRAINT fk_support_bulk_recipient_job FOREIGN KEY (batch_id) REFERENCES nx_support_bulk_job(id),
  CONSTRAINT ck_support_bulk_recipient_state CHECK (state IN ('PENDING','SENT','FAILED','SKIPPED','CANCELLED')),
  CONSTRAINT ck_support_bulk_recipient_certainty CHECK (result_certainty IN ('KNOWN','UNKNOWN')),
  CONSTRAINT ck_support_bulk_sent CHECK (state <> 'SENT' OR (message_id IS NOT NULL AND result_certainty='KNOWN')),
  CONSTRAINT ck_support_bulk_unknown CHECK (result_certainty <> 'UNKNOWN' OR state='PENDING')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

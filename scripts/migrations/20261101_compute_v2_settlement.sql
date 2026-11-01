-- Compute V2 immutable lifecycle checkpoints and per-currency financial batches.
-- Requires the existing support fund journal/operation/party migrations and legacy compute tables.
-- No business/history backfill. Application writers need SELECT/INSERT only on these four tables.
CREATE TABLE IF NOT EXISTS nx_compute_v2_checkpoint (
 batch_id VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY,
 user_id BIGINT NOT NULL,
 content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 event_ids JSON NOT NULL,
 allocation_ids JSON NOT NULL,
 window_start_utc DATETIME(6) NOT NULL,
 window_end_utc DATETIME(6) NOT NULL,
 posted_at_utc DATETIME(6) NOT NULL,
 CONSTRAINT ck_compute_v2_checkpoint_arrays CHECK (JSON_TYPE(event_ids)='ARRAY' AND JSON_LENGTH(event_ids) BETWEEN 1 AND 200 AND JSON_TYPE(allocation_ids)='ARRAY'),
 CONSTRAINT ck_compute_v2_checkpoint_window CHECK (TIMESTAMPDIFF(MICROSECOND,window_start_utc,window_end_utc)=60000000 AND MICROSECOND(window_start_utc)=0),
 KEY idx_compute_v2_checkpoint_user(user_id,posted_at_utc)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS nx_compute_v2_event (
 event_id VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY,
 batch_id VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 deployment_scope VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 task_no VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 generation BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 user_device_id BIGINT NOT NULL,
 kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 occurred_at_utc DATETIME(6) NOT NULL,
 payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 payload JSON NOT NULL,
 request_id VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin GENERATED ALWAYS AS (JSON_UNQUOTE(JSON_EXTRACT(payload,'$.task.requestId'))) STORED,
 CONSTRAINT ck_compute_v2_event_kind CHECK (kind IN ('ISSUED','STARTED','VERIFIED','FAILED','FENCED','EXITED')),
 CONSTRAINT ck_compute_v2_event_identity CHECK (generation>0 AND user_id>0 AND user_device_id>0 AND JSON_TYPE(payload)='OBJECT'),
 UNIQUE KEY uk_compute_v2_lifecycle(task_no,generation,kind),
 UNIQUE KEY uk_compute_v2_device_generation(user_device_id,generation,kind),
 UNIQUE KEY uk_compute_v2_request_kind(user_id,user_device_id,request_id,kind),
 KEY idx_compute_v2_latest(deployment_scope,user_device_id,generation),
 KEY idx_compute_v2_event_scope_task(deployment_scope,task_no,kind),
 KEY idx_compute_v2_event_batch(batch_id),
 KEY idx_compute_v2_event_user(user_id,occurred_at_utc)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS nx_compute_v2_settlement (
 settlement_id VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY,
 batch_id VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 user_id BIGINT NOT NULL,
 asset VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 total_micros BIGINT NOT NULL,
 wallet_id BIGINT NOT NULL,
 wallet_incarnation CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 before_revision BIGINT NOT NULL,
 after_revision BIGINT NOT NULL,
 operation_key VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 window_start_utc DATETIME(6) NOT NULL,
 window_end_utc DATETIME(6) NOT NULL,
 posted_at_utc DATETIME(6) NOT NULL,
 CONSTRAINT ck_compute_v2_settlement_amount CHECK(total_micros>=0 AND asset IN ('USDT','UVT') AND before_revision>=0 AND after_revision=before_revision+IF(total_micros>0,1,0)),
 CONSTRAINT ck_compute_v2_settlement_window CHECK (TIMESTAMPDIFF(MICROSECOND,window_start_utc,window_end_utc)=60000000 AND MICROSECOND(window_start_utc)=0),
 UNIQUE KEY uk_compute_v2_batch_asset(batch_id,asset),
 UNIQUE KEY uk_compute_v2_operation(operation_key),
 KEY idx_compute_v2_settlement_user(user_id,posted_at_utc)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS nx_compute_v2_allocation (
 allocation_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 settlement_id VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 event_id VARCHAR(192) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 task_no VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 receipt_no VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 user_id BIGINT NOT NULL,
 user_device_id BIGINT NOT NULL,
 asset VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 amount_micros BIGINT NOT NULL,
 daily_entitlement_key VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
 completed_at_utc DATETIME(6) NOT NULL,
 posted_at_utc DATETIME(6) NOT NULL,
 CONSTRAINT ck_compute_v2_allocation_amount CHECK(amount_micros>=0 AND asset IN ('USDT','UVT')),
 CONSTRAINT ck_compute_v2_daily_asset CHECK((asset='USDT' AND daily_entitlement_key IS NULL) OR (asset='UVT' AND amount_micros>0 AND daily_entitlement_key IS NOT NULL)),
 UNIQUE KEY uk_compute_v2_task_asset(task_no,asset),
 UNIQUE KEY uk_compute_v2_event_asset(event_id,asset),
 UNIQUE KEY uk_compute_v2_receipt_asset(receipt_no,asset),
 UNIQUE KEY uk_compute_v2_daily(daily_entitlement_key),
 KEY idx_compute_v2_allocation_settlement(settlement_id),
 KEY idx_compute_v2_daily_recovery(asset,completed_at_utc,event_id),
 KEY idx_compute_v2_allocation_user(user_id,posted_at_utc)
) ENGINE=InnoDB;

CREATE TRIGGER IF NOT EXISTS nx_compute_v2_checkpoint_bu BEFORE UPDATE ON nx_compute_v2_checkpoint FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='COMPUTE_V2_RECORD_IMMUTABLE';
CREATE TRIGGER IF NOT EXISTS nx_compute_v2_checkpoint_bd BEFORE DELETE ON nx_compute_v2_checkpoint FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='COMPUTE_V2_RECORD_IMMUTABLE';
CREATE TRIGGER IF NOT EXISTS nx_compute_v2_event_bu BEFORE UPDATE ON nx_compute_v2_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='COMPUTE_V2_RECORD_IMMUTABLE';
CREATE TRIGGER IF NOT EXISTS nx_compute_v2_event_bd BEFORE DELETE ON nx_compute_v2_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='COMPUTE_V2_RECORD_IMMUTABLE';
CREATE TRIGGER IF NOT EXISTS nx_compute_v2_settlement_bu BEFORE UPDATE ON nx_compute_v2_settlement FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='COMPUTE_V2_RECORD_IMMUTABLE';
CREATE TRIGGER IF NOT EXISTS nx_compute_v2_settlement_bd BEFORE DELETE ON nx_compute_v2_settlement FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='COMPUTE_V2_RECORD_IMMUTABLE';
CREATE TRIGGER IF NOT EXISTS nx_compute_v2_allocation_bu BEFORE UPDATE ON nx_compute_v2_allocation FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='COMPUTE_V2_RECORD_IMMUTABLE';
CREATE TRIGGER IF NOT EXISTS nx_compute_v2_allocation_bd BEFORE DELETE ON nx_compute_v2_allocation FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='COMPUTE_V2_RECORD_IMMUTABLE';

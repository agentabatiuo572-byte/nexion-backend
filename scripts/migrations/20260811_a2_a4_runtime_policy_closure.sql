-- A2/A4 authoritative runtime-policy bootstrap and bounded retention structures.
-- Existing policy values are deliberately never overwritten and legacy audit rows are never backfilled.
SET @schema_name = DATABASE();

INSERT IGNORE INTO nx_config_item
  (config_key,config_value,value_type,config_group,visibility,remark,status,is_deleted)
VALUES
  ('admin.a2.reason_min_chars','8 字','STRING','admin_a2','ADMIN','A2 authoritative reason minimum',1,0),
  ('admin.a2.retention_months','13 个月','STRING','admin_a2','ADMIN','A2 audit retention policy',1,0),
  ('admin.a2.schema_version','v3','STRING','admin_a2','ADMIN','A2 audit schema version',1,0),
  ('admin.a4.event.kpi.day0','90 秒','STRING','admin_a4_event','ADMIN','A4 PRD default',1,0),
  ('admin.a4.event.kpi.event_retention','13 个月','STRING','admin_a4_event','ADMIN','A4 PRD minimum',1,0),
  ('admin.a4.event.kpi.sampling','浏览/会话 10% · 资金/风控/转化 100%','STRING','admin_a4_event','ADMIN','A4 authoritative runtime sampling',1,0);

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='nx_audit_log' AND COLUMN_NAME='retention_policy_months')=0,
 'ALTER TABLE nx_audit_log ADD COLUMN retention_policy_months INT NULL COMMENT ''Null for legacy or retention-executor audit rows'' AFTER detail_json', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='nx_audit_log' AND COLUMN_NAME='expire_at')=0,
 'ALTER TABLE nx_audit_log ADD COLUMN expire_at DATETIME(3) NULL COMMENT ''Assigned only to new governed audit rows'' AFTER retention_policy_months', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='nx_audit_log' AND INDEX_NAME='idx_audit_expiry_batch')=0,
 'ALTER TABLE nx_audit_log ADD INDEX idx_audit_expiry_batch(expire_at,id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_audit_log_archive (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  original_audit_id BIGINT NOT NULL,
  retention_policy_months INT NOT NULL,
  expire_at DATETIME(3) NOT NULL,
  original_created_at DATETIME(3) NOT NULL,
  cold_payload JSON NOT NULL,
  content_sha256 CHAR(64) NOT NULL,
  archived_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_audit_archive_original (original_audit_id),
  KEY idx_audit_archive_expiry (expire_at,original_audit_id),
  KEY idx_audit_archive_digest (content_sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Append-only A2 cold archive; no update/delete application API';

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='nx_behavior_event_fact' AND INDEX_NAME='idx_behavior_retention_batch')=0,
 'ALTER TABLE nx_behavior_event_fact ADD INDEX idx_behavior_retention_batch(occurred_at,id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='nx_event_outbox' AND INDEX_NAME='idx_event_outbox_retention_batch')=0,
 'ALTER TABLE nx_event_outbox ADD INDEX idx_event_outbox_retention_batch(analytics_event,status,event_ts,is_deleted,id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

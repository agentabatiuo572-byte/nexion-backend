-- K4/D2/A4 closure: persist the current K4 snapshot used by D2 and register
-- the server-canonical escalation fact consumed by B5/C1.
SET NAMES utf8mb4;

-- d2_routing_priority is the immutable submission-time snapshot. The D2 query derives
-- its current authoritative priority from the fresh active K4 score/thresholds.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_routing_priority')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_routing_priority VARCHAR(16) NOT NULL DEFAULT ''NORMAL'' AFTER d2_previous_status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_k4_withdrawal_alert_receipt (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL,
  recipient_admin_id BIGINT NOT NULL,
  withdrawal_no VARCHAR(64) NOT NULL,
  payload_json JSON NOT NULL,
  read_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_k4_withdraw_alert_event_recipient(event_id,recipient_admin_id),
  KEY idx_k4_withdraw_alert_recipient_time(recipient_admin_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_k3_risk_route')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_k3_risk_route VARCHAR(16) NULL AFTER d2_routing_priority',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_k4_risk_score')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_k4_risk_score INT NULL AFTER d2_routing_priority', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_k4_model_version')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_k4_model_version VARCHAR(64) NULL AFTER d2_k4_risk_score', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_k4_as_of')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_k4_as_of DATETIME NULL AFTER d2_k4_model_version', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_k4_band_low_max')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_k4_band_low_max INT NULL AFTER d2_k4_as_of', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_k4_band_high_min')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_k4_band_high_min INT NULL AFTER d2_k4_band_low_max', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_k4_auto_escalate_score')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_k4_auto_escalate_score INT NULL AFTER d2_k4_band_high_min', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE nx_withdrawal_order w
LEFT JOIN nx_admin_risk_score_user k4
  ON k4.user_no=CONCAT('U',LPAD(w.user_id,8,'0')) AND k4.is_deleted=0
LEFT JOIN nx_admin_risk_score_model k4m
  ON k4m.state='active' AND k4m.is_deleted=0
 AND k4.model_version=CONCAT('k4-v',k4m.model_version)
LEFT JOIN nx_admin_risk_score_override k4o
  ON k4o.user_no=k4.user_no AND k4o.active=1 AND k4o.is_deleted=0
SET w.d2_k4_risk_score=COALESCE(w.d2_k4_risk_score,k4o.override_score,k4.model_score),
    w.d2_k4_model_version=COALESCE(w.d2_k4_model_version,k4.model_version),
    w.d2_k4_as_of=COALESCE(w.d2_k4_as_of,k4.as_of),
    w.d2_k4_band_low_max=COALESCE(w.d2_k4_band_low_max,k4m.band_low_max),
    w.d2_k4_band_high_min=COALESCE(w.d2_k4_band_high_min,k4m.band_high_min),
    w.d2_k4_auto_escalate_score=COALESCE(w.d2_k4_auto_escalate_score,k4m.auto_escalate_score),
    w.d2_routing_priority=CASE
      WHEN COALESCE(k4o.override_score,k4.model_score)>=k4m.auto_escalate_score THEN 'ESCALATED'
      WHEN COALESCE(k4o.override_score,k4.model_score)>=k4m.band_high_min THEN 'HIGH'
      WHEN COALESCE(k4o.override_score,k4.model_score)>=k4m.band_low_max THEN 'NORMAL'
      WHEN COALESCE(k4o.override_score,k4.model_score) IS NOT NULL THEN 'LOW'
      ELSE w.d2_routing_priority END
WHERE w.is_deleted=0;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND INDEX_NAME='idx_withdraw_d2_routing_created')=0,
  'CREATE INDEX idx_withdraw_d2_routing_created ON nx_withdrawal_order(d2_routing_priority,created_at,id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE nx_event_schema_registry
   SET current_revision=GREATEST(current_revision,273),
       updated_by='migration:k4-d2-routing',
       reason='K3 and K4 d2_routing_priority snapshot persisted at withdrawal submission',
       updated_at=NOW()
 WHERE event_name='withdraw.submitted' AND is_deleted=0;

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,s.current_revision,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'k3_risk_route' property_name,'enum' property_type UNION ALL
    SELECT 'k4_priority','enum' UNION ALL
    SELECT 'k4_risk_score','number' UNION ALL
    SELECT 'k4_model_version','string' UNION ALL
    SELECT 'k4_as_of','timestamp'
  ) p
 WHERE s.event_name='withdraw.submitted' AND s.is_deleted=0
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=VALUES(registry_revision),is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name='risk.withdraw_escalated'
   AND p.property_name NOT IN ('withdrawal_id','user_no','risk_score','priority','notify_permission','model_version','score_as_of');

INSERT INTO nx_event_domain_extension
  (domain_name,event_name,producer,consumer,status,created_by,reason,is_deleted)
VALUES
  ('risk','risk.withdraw_escalated','AppWithdrawalService','K4WithdrawalEscalationAlertConsumer','REGISTERED',
   'migration:k4-d2-routing','Reliable per-authorized-K4-operator escalation alert with idempotent consumer delivery',0)
ON DUPLICATE KEY UPDATE producer=VALUES(producer),consumer=VALUES(consumer),status='REGISTERED',
  reason=VALUES(reason),is_deleted=0;

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('risk.withdraw_escalated','risk','risk','AppWithdrawalService','A4/B5/C1/D2/K4',1,
   '100%',274,'ACTIVE','migration:k4-d2-routing',
   'K4 d2_routing_priority escalated to the risk lead',0)
ON DUPLICATE KEY UPDATE
  owner_domain=VALUES(owner_domain),family_key=VALUES(family_key),producer=VALUES(producer),
  consumers=VALUES(consumers),is_server_authoritative=1,sampling_policy='100%',
  current_revision=VALUES(current_revision),status='ACTIVE',updated_by='migration:k4-d2-routing',
  reason=VALUES(reason),is_deleted=0;

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,s.current_revision,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'withdrawal_id' property_name,'id' property_type UNION ALL
    SELECT 'user_no','id' UNION ALL
    SELECT 'risk_score','number' UNION ALL
    SELECT 'priority','enum' UNION ALL
    SELECT 'notify_permission','string' UNION ALL
    SELECT 'model_version','string' UNION ALL
    SELECT 'score_as_of','timestamp'
  ) p
 WHERE s.event_name='risk.withdraw_escalated' AND s.is_deleted=0
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=1,
  registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,274)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,274);

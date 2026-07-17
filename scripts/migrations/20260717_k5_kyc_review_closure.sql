-- K5 KYC review closed-loop persistence and optimistic concurrency.

CREATE TABLE IF NOT EXISTS nx_admin_risk_param (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  section_key VARCHAR(32) NOT NULL,
  param_key VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  value_text VARCHAR(128) NOT NULL,
  unit_text VARCHAR(64) DEFAULT NULL,
  sub_text VARCHAR(255) NOT NULL,
  note_text VARCHAR(1000) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_risk_param (section_key,param_key),
  KEY idx_admin_risk_param_section (section_key,is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_admin_risk_kyc_review_source (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  ticket_id VARCHAR(64) NOT NULL,
  source_domain VARCHAR(16) NOT NULL,
  source_no VARCHAR(128) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_risk_kyc_review_source (ticket_id,source_domain,source_no),
  KEY idx_admin_risk_kyc_review_source_time (source_domain,created_at,is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_risk_param' AND COLUMN_NAME='version')=0,
  'ALTER TABLE nx_admin_risk_param ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER sort_order','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_admin_risk_kyc_review_ticket (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  ticket_id VARCHAR(64) NOT NULL,
  ticket_type VARCHAR(64) NOT NULL,
  user_no VARCHAR(64) NOT NULL,
  amount_text VARCHAR(64) NOT NULL,
  amount_usdt DECIMAL(20,8) DEFAULT NULL,
  cumulative_text VARCHAR(64) NOT NULL,
  kyc_text VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  sla_pct DECIMAL(6,4) NOT NULL DEFAULT 0,
  sla_text VARCHAR(64) NOT NULL,
  info_json TEXT DEFAULT NULL,
  history_json TEXT DEFAULT NULL,
  decision_reason VARCHAR(1000) DEFAULT NULL,
  reviewed_by VARCHAR(64) DEFAULT NULL,
  reviewed_at DATETIME DEFAULT NULL,
  due_at DATETIME DEFAULT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  open_user_key VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN status IN ('triggered','in-review') AND is_deleted=0 THEN user_no ELSE NULL END) STORED,
  UNIQUE KEY uk_admin_risk_kyc_ticket (ticket_id),
  UNIQUE KEY uk_admin_risk_kyc_open_user (open_user_key),
  KEY idx_admin_risk_kyc_status (status,is_deleted),
  KEY idx_admin_risk_kyc_user (user_no,is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_risk_kyc_review_ticket' AND COLUMN_NAME='amount_usdt')=0,
  'ALTER TABLE nx_admin_risk_kyc_review_ticket ADD COLUMN amount_usdt DECIMAL(20,8) DEFAULT NULL AFTER amount_text','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_risk_kyc_review_ticket' AND COLUMN_NAME='due_at')=0,
  'ALTER TABLE nx_admin_risk_kyc_review_ticket ADD COLUMN due_at DATETIME DEFAULT NULL AFTER reviewed_at','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_risk_kyc_review_ticket' AND COLUMN_NAME='version')=0,
  'ALTER TABLE nx_admin_risk_kyc_review_ticket ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER due_at','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE nx_admin_risk_kyc_review_ticket t
JOIN nx_admin_risk_kyc_review_ticket newer
  ON newer.user_no=t.user_no AND newer.id>t.id
 AND newer.status IN ('triggered','in-review') AND newer.is_deleted=0
   SET t.status='rejected',t.is_deleted=1,t.decision_reason='MIGRATION_MERGED_DUPLICATE',t.updated_at=NOW()
 WHERE t.status IN ('triggered','in-review') AND t.is_deleted=0;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_risk_kyc_review_ticket' AND INDEX_NAME='uk_admin_risk_kyc_open_user')>0,
  'ALTER TABLE nx_admin_risk_kyc_review_ticket DROP INDEX uk_admin_risk_kyc_open_user','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_risk_kyc_review_ticket' AND COLUMN_NAME='open_user_key')>0,
  'ALTER TABLE nx_admin_risk_kyc_review_ticket DROP COLUMN open_user_key','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
ALTER TABLE nx_admin_risk_kyc_review_ticket
  ADD COLUMN open_user_key VARCHAR(64) GENERATED ALWAYS AS
    (CASE WHEN status IN ('triggered','in-review') AND is_deleted=0 THEN user_no ELSE NULL END) STORED,
  ADD UNIQUE KEY uk_admin_risk_kyc_open_user (open_user_key);

-- Backfill the original D2/G2 source embedded in legacy ticket info_json.
INSERT IGNORE INTO nx_admin_risk_kyc_review_source(ticket_id,source_domain,source_no,is_deleted)
SELECT t.ticket_id,
       MAX(CASE WHEN j.item_key='sourceDomain' THEN j.item_value END),
       MAX(CASE WHEN j.item_key='sourceNo' THEN j.item_value END),0
  FROM nx_admin_risk_kyc_review_ticket t
  JOIN JSON_TABLE(
        CASE WHEN JSON_VALID(t.info_json) THEN t.info_json ELSE JSON_ARRAY() END,
        '$[*]' COLUMNS(item_key VARCHAR(64) PATH '$[0]',item_value VARCHAR(128) PATH '$[1]')
       ) j
 WHERE t.is_deleted=0
 GROUP BY t.ticket_id
HAVING MAX(CASE WHEN j.item_key='sourceDomain' THEN j.item_value END) IN ('D2','G2')
   AND MAX(CASE WHEN j.item_key='sourceNo' THEN j.item_value END) IS NOT NULL;

-- Normalize legacy history tuples from [time,event,operator] to [time,event,tone].
-- Legal tuples are retained byte-for-byte at the JSON item level, making reruns idempotent.
DROP TEMPORARY TABLE IF EXISTS tmp_k5_history_normalized;
CREATE TEMPORARY TABLE tmp_k5_history_normalized (
  id BIGINT PRIMARY KEY,
  normalized_history JSON NOT NULL
);
INSERT INTO tmp_k5_history_normalized(id,normalized_history)
WITH RECURSIVE k5_history_rebuild AS (
  SELECT id,history_json,0 AS item_index,JSON_ARRAY() AS normalized_history
    FROM nx_admin_risk_kyc_review_ticket
   WHERE JSON_VALID(history_json) AND JSON_TYPE(history_json)='ARRAY'
  UNION ALL
  SELECT id,history_json,item_index+1,
         JSON_ARRAY_APPEND(normalized_history,'$',
           CASE
             WHEN JSON_TYPE(JSON_EXTRACT(history_json,CONCAT('$[',item_index,']')))='ARRAY'
              AND JSON_LENGTH(JSON_EXTRACT(history_json,CONCAT('$[',item_index,']')))=3
              AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(history_json,CONCAT('$[',item_index,'][2]'))),'') IN ('','warn','bad')
             THEN JSON_EXTRACT(history_json,CONCAT('$[',item_index,']'))
             ELSE JSON_ARRAY(
               COALESCE(JSON_UNQUOTE(JSON_EXTRACT(history_json,CONCAT('$[',item_index,'][0]'))),''),
               CONCAT(
                 COALESCE(JSON_UNQUOTE(JSON_EXTRACT(history_json,CONCAT('$[',item_index,'][1]'))),''),
                 CASE WHEN COALESCE(JSON_UNQUOTE(JSON_EXTRACT(history_json,CONCAT('$[',item_index,'][2]'))),'')=''
                      THEN '' ELSE CONCAT('·操作人:',JSON_UNQUOTE(JSON_EXTRACT(history_json,CONCAT('$[',item_index,'][2]')))) END),
               CASE
                 WHEN COALESCE(JSON_UNQUOTE(JSON_EXTRACT(history_json,CONCAT('$[',item_index,'][1]'))),'') LIKE '%驳回%'
                   OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(history_json,CONCAT('$[',item_index,'][1]'))),'') LIKE '%拒绝%' THEN 'bad'
                 WHEN COALESCE(JSON_UNQUOTE(JSON_EXTRACT(history_json,CONCAT('$[',item_index,'][1]'))),'') LIKE '%并入%'
                   OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(history_json,CONCAT('$[',item_index,'][1]'))),'') LIKE '%追加触发%' THEN 'warn'
                 ELSE ''
               END)
           END)
    FROM k5_history_rebuild
   WHERE item_index<JSON_LENGTH(history_json)
)
SELECT id,normalized_history
  FROM k5_history_rebuild
 WHERE item_index=JSON_LENGTH(history_json);
UPDATE nx_admin_risk_kyc_review_ticket t
JOIN tmp_k5_history_normalized h ON h.id=t.id
   SET t.history_json=CAST(h.normalized_history AS CHAR),t.updated_at=NOW()
 WHERE NOT (t.history_json <=> CAST(h.normalized_history AS CHAR));
DROP TEMPORARY TABLE IF EXISTS tmp_k5_history_normalized;

CREATE TABLE IF NOT EXISTS nx_admin_risk_kyc_alert (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_key VARCHAR(128) DEFAULT NULL,
  tone VARCHAR(32) NOT NULL,
  title VARCHAR(128) NOT NULL,
  body VARCHAR(1000) NOT NULL,
  time_text VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_risk_kyc_alert_event (event_key),
  KEY idx_admin_risk_kyc_alert_tone (tone,is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_risk_kyc_alert' AND COLUMN_NAME='event_key')=0,
  'ALTER TABLE nx_admin_risk_kyc_alert ADD COLUMN event_key VARCHAR(128) DEFAULT NULL AFTER id','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_risk_kyc_alert' AND INDEX_NAME='uk_admin_risk_kyc_alert_event')=0,
  'ALTER TABLE nx_admin_risk_kyc_alert ADD UNIQUE KEY uk_admin_risk_kyc_alert_event (event_key)','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nx_admin_risk_kyc_alert_subscription (
  operator_name VARCHAR(64) PRIMARY KEY,
  alert_types_json TEXT NOT NULL,
  channels_json TEXT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_admin_risk_param(section_key,param_key,name,value_text,unit_text,sub_text,note_text,sort_order,version,is_deleted)
VALUES
 ('k5','reviewTriggerScore','风险分复审线','>= 85','分','K4 有效风险分达到该值时进入 KYC 复审','可调范围 70-100',0,0,0),
 ('k5','largeWithdrawReviewUsdt','大额提现复审线','>= $1,000','USDT','单笔提现达到该值时生成复审工单','可调范围 100-50000',1,0,0),
 ('k5','cumulativeKycThresholdUsdt','累计交易 KYC 线','$100','USDT','累计交易达到该值时检查 KYC','可调范围 50-1000',2,0,0),
 ('k5','reviewSlaDays','复审 SLA','7','天','复审工单截止时间','可调范围 1-15',3,0,0)
ON DUPLICATE KEY UPDATE name=VALUES(name),unit_text=VALUES(unit_text),sub_text=VALUES(sub_text),
 note_text=VALUES(note_text),sort_order=VALUES(sort_order),is_deleted=0,updated_at=NOW();

UPDATE nx_admin_risk_param SET is_deleted=1,updated_at=NOW()
 WHERE section_key='k5' AND param_key='largeExchangeReviewUsdt';
UPDATE nx_admin_risk_kyc_review_ticket SET status='in-review'
 WHERE status='triggered' AND is_deleted=0;
SET @k5_sla_days = COALESCE((SELECT CAST(value_text AS UNSIGNED) FROM nx_admin_risk_param
 WHERE section_key='k5' AND param_key='reviewSlaDays' AND is_deleted=0 LIMIT 1),7);
WITH RECURSIVE business_calendar AS (
  SELECT id,created_at,created_at AS candidate,0 AS working_days
    FROM nx_admin_risk_kyc_review_ticket
   WHERE due_at IS NULL AND status='in-review' AND is_deleted=0
  UNION ALL
  SELECT id,created_at,DATE_ADD(candidate,INTERVAL 1 DAY),
         working_days + CASE WHEN WEEKDAY(DATE_ADD(candidate,INTERVAL 1 DAY)) < 5 THEN 1 ELSE 0 END
    FROM business_calendar
   WHERE working_days < @k5_sla_days
)
UPDATE nx_admin_risk_kyc_review_ticket t
JOIN (SELECT id,MIN(candidate) AS due_at FROM business_calendar
       WHERE working_days=@k5_sla_days AND WEEKDAY(candidate)<5 GROUP BY id) d ON d.id=t.id
   SET t.due_at=d.due_at,t.updated_at=NOW()
 WHERE t.due_at IS NULL AND t.status='in-review' AND t.is_deleted=0;

-- SUPPORT gets K5 read-only access and only the K parent/K5 menu nodes.
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id AND r.role_code='SUPPORT'
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code LIKE 'risk_k5_%' AND p.permission_code<>'risk_k5_read';
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code='risk_k5_read'
WHERE r.role_code='SUPPORT' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_menu(role_id,menu_id)
SELECT r.id,m.id FROM nx_admin_role r JOIN nx_admin_menu m ON m.menu_code IN ('K','K5')
WHERE r.role_code='SUPPORT' AND m.is_deleted=0;

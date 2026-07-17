-- K4 risk-scoring closure: versioned model lifecycle, optimistic score writes,
-- canonical six-dimension explainability, and one active override per user.
-- Replay-safe for existing local and shared MySQL 8 deployments.

CREATE TABLE IF NOT EXISTS nx_admin_risk_score_model (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  model_version BIGINT NOT NULL,
  row_version BIGINT NOT NULL DEFAULT 0,
  state VARCHAR(16) NOT NULL,
  weights_json VARCHAR(1000) NOT NULL,
  input_sources_json VARCHAR(1000) NOT NULL,
  score_mapping_json MEDIUMTEXT NOT NULL,
  band_low_max INT NOT NULL,
  band_high_min INT NOT NULL,
  auto_escalate_score INT NOT NULL,
  reason VARCHAR(200) NOT NULL,
  created_by VARCHAR(64) NOT NULL,
  published_by VARCHAR(64),
  published_at DATETIME,
  archived_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_risk_score_model_version (model_version),
  KEY idx_admin_risk_score_model_state (state,is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @k4_has_model_mapping = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_risk_score_model'
     AND COLUMN_NAME = 'score_mapping_json'
);
SET @k4_sql = IF(@k4_has_model_mapping = 0,
  'ALTER TABLE nx_admin_risk_score_model ADD COLUMN score_mapping_json MEDIUMTEXT NULL AFTER input_sources_json',
  'SELECT 1');
PREPARE k4_stmt FROM @k4_sql; EXECUTE k4_stmt; DEALLOCATE PREPARE k4_stmt;

SET @k4_default_mappings = '{"multiAccount.mediumMin":2,"multiAccount.highMin":4,"multiAccount.mediumScore":40,"multiAccount.highScore":80,"multiAccount.fraudScore":100,"arbitrage.singleScore":30,"arbitrage.repeatMin":2,"arbitrage.repeatScore":70,"arbitrage.severeScore":100,"kyc.reviewScore":40,"kyc.pendingScore":60,"kyc.rejectedScore":90,"kyc.sanctionedScore":100,"withdraw.baselineMultiplierPct":200,"withdraw.baselineScore":50,"withdraw.highFrequency24h":5,"withdraw.largeAmountUsd":5000,"withdraw.highScore":90,"account.matureDays":180,"account.newDays":7,"account.middleScore":30,"account.newLargeScore":70,"anomaly.lowScore":40,"anomaly.tamperScore":100}';
UPDATE nx_admin_risk_score_model
   SET score_mapping_json=@k4_default_mappings
 WHERE score_mapping_json IS NULL OR score_mapping_json='';

SET @k4_has_score_row_version = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_risk_score_user'
     AND COLUMN_NAME = 'row_version'
);
SET @k4_sql = IF(@k4_has_score_row_version = 0,
  'ALTER TABLE nx_admin_risk_score_user ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0 AFTER model_version',
  'SELECT 1');
PREPARE k4_stmt FROM @k4_sql; EXECUTE k4_stmt; DEALLOCATE PREPARE k4_stmt;

SET @k4_has_score_as_of = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_risk_score_user'
     AND COLUMN_NAME = 'as_of'
);
SET @k4_sql = IF(@k4_has_score_as_of = 0,
  'ALTER TABLE nx_admin_risk_score_user ADD COLUMN as_of DATETIME NULL AFTER row_version',
  'SELECT 1');
PREPARE k4_stmt FROM @k4_sql; EXECUTE k4_stmt; DEALLOCATE PREPARE k4_stmt;

SET @k4_has_contribution_dim_key = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_risk_score_contribution'
     AND COLUMN_NAME = 'dim_key'
);
SET @k4_sql = IF(@k4_has_contribution_dim_key = 0,
  'ALTER TABLE nx_admin_risk_score_contribution ADD COLUMN dim_key VARCHAR(64) NULL AFTER user_no',
  'SELECT 1');
PREPARE k4_stmt FROM @k4_sql; EXECUTE k4_stmt; DEALLOCATE PREPARE k4_stmt;

SET @k4_has_contribution_hit = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_risk_score_contribution'
     AND COLUMN_NAME = 'hit'
);
SET @k4_sql = IF(@k4_has_contribution_hit = 0,
  'ALTER TABLE nx_admin_risk_score_contribution ADD COLUMN hit TINYINT NOT NULL DEFAULT 0 AFTER name',
  'SELECT 1');
PREPARE k4_stmt FROM @k4_sql; EXECUTE k4_stmt; DEALLOCATE PREPARE k4_stmt;

SET @k4_has_contribution_sub_score = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_risk_score_contribution'
     AND COLUMN_NAME = 'sub_score'
);
SET @k4_sql = IF(@k4_has_contribution_sub_score = 0,
  'ALTER TABLE nx_admin_risk_score_contribution ADD COLUMN sub_score INT NOT NULL DEFAULT 0 AFTER evidence',
  'SELECT 1');
PREPARE k4_stmt FROM @k4_sql; EXECUTE k4_stmt; DEALLOCATE PREPARE k4_stmt;

SET @k4_has_contribution_weight = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_risk_score_contribution'
     AND COLUMN_NAME = 'weight_pct'
);
SET @k4_sql = IF(@k4_has_contribution_weight = 0,
  'ALTER TABLE nx_admin_risk_score_contribution ADD COLUMN weight_pct INT NOT NULL DEFAULT 0 AFTER sub_score',
  'SELECT 1');
PREPARE k4_stmt FROM @k4_sql; EXECUTE k4_stmt; DEALLOCATE PREPARE k4_stmt;

SET @k4_has_contribution_model_version = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_risk_score_contribution'
     AND COLUMN_NAME = 'model_version'
);
SET @k4_sql = IF(@k4_has_contribution_model_version = 0,
  'ALTER TABLE nx_admin_risk_score_contribution ADD COLUMN model_version BIGINT NULL AFTER user_no',
  'SELECT 1');
PREPARE k4_stmt FROM @k4_sql; EXECUTE k4_stmt; DEALLOCATE PREPARE k4_stmt;

CREATE TABLE IF NOT EXISTS nx_admin_risk_score_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_no VARCHAR(64) NOT NULL,
  model_version BIGINT NOT NULL,
  model_score INT NOT NULL,
  effective_score INT NOT NULL,
  score_state VARCHAR(32) NOT NULL,
  contributions_json MEDIUMTEXT NOT NULL,
  reason VARCHAR(200) NOT NULL,
  operator VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_admin_risk_score_history_user (user_no,model_version,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @k4_has_active_user_key = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_risk_score_override'
     AND COLUMN_NAME = 'active_user_key'
);
SET @k4_sql = IF(@k4_has_active_user_key = 0,
  'ALTER TABLE nx_admin_risk_score_override ADD COLUMN active_user_key VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN active = 1 AND is_deleted = 0 THEN user_no ELSE NULL END) STORED',
  'SELECT 1');
PREPARE k4_stmt FROM @k4_sql; EXECUTE k4_stmt; DEALLOCATE PREPARE k4_stmt;

UPDATE nx_admin_risk_score_override o
  JOIN (SELECT user_no, MAX(id) keep_id
          FROM nx_admin_risk_score_override
         WHERE active = 1 AND is_deleted = 0
         GROUP BY user_no HAVING COUNT(*) > 1) d ON d.user_no = o.user_no
   SET o.active = 0, o.updated_at = NOW()
 WHERE o.active = 1 AND o.is_deleted = 0 AND o.id <> d.keep_id;

SET @k4_has_active_override_index = (
  SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_risk_score_override'
     AND INDEX_NAME = 'uk_admin_risk_score_override_active'
);
SET @k4_sql = IF(@k4_has_active_override_index = 0,
  'CREATE UNIQUE INDEX uk_admin_risk_score_override_active ON nx_admin_risk_score_override(active_user_key)',
  'SELECT 1');
PREPARE k4_stmt FROM @k4_sql; EXECUTE k4_stmt; DEALLOCATE PREPARE k4_stmt;

INSERT INTO nx_admin_risk_score_model (
  model_version,row_version,state,weights_json,input_sources_json,score_mapping_json,
  band_low_max,band_high_min,auto_escalate_score,reason,created_by,published_by,published_at
)
SELECT 1,0,'active',
  '{"multiAccount":25,"arbitrage":20,"kycStatus":20,"withdrawVelocity":15,"accountAge":10,"anomalyBehavior":10}',
  '{"multiAccount":true,"arbitrage":true,"kycStatus":true,"withdrawVelocity":true,"accountAge":true,"anomalyBehavior":true}',
  @k4_default_mappings,
  40,70,85,'initial canonical K4 model','system','system',NOW()
WHERE NOT EXISTS (SELECT 1 FROM nx_admin_risk_score_model WHERE is_deleted = 0);

UPDATE nx_admin_risk_score_contribution
   SET model_version=COALESCE((SELECT MAX(model_version) FROM nx_admin_risk_score_model WHERE state='active' AND is_deleted=0),1)
 WHERE model_version IS NULL;

-- There is no persisted RISK lead/member hierarchy yet. Fail closed: only SUPER_ADMIN
-- may draft a model or override an authoritative score; ordinary RISK keeps read/recompute.
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id AND r.role_code='RISK'
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code IN ('risk_k4_write','risk_k4_user_override');

UPDATE nx_admin_permission
   SET permission_name=CASE permission_code
         WHEN 'risk_k4_user_override' THEN '单用户人工覆盖评分(非高敏·强制理由·影响提现路由)'
         ELSE '重算回模型分(直接留痕·丢弃覆盖值·还原模型)'
       END,
       perm_type='WRITE',amplifies=0,status=1,is_deleted=0
 WHERE permission_code IN ('risk_k4_user_override','risk_k4_user_recompute');

INSERT INTO nx_admin_risk_score_dimension (dim_key,name,source_label,weight_pct,sort_order,is_deleted)
VALUES
  ('multiAccount','多账户','K1 多账户事实',25,0,0),
  ('arbitrage','套利与刷量','K2 套利事实',20,1,0),
  ('kycStatus','KYC 状态','C4 KYC 状态',20,2,0),
  ('withdrawVelocity','提现速度','24 小时提现事实',15,3,0),
  ('accountAge','账户年龄','用户注册时间',10,4,0),
  ('anomalyBehavior','异常行为','风险信号与篡改事实',10,5,0)
ON DUPLICATE KEY UPDATE
  name=VALUES(name),source_label=VALUES(source_label),weight_pct=VALUES(weight_pct),
  sort_order=VALUES(sort_order),is_deleted=0,updated_at=NOW();

UPDATE nx_admin_risk_score_dimension
   SET is_deleted=1,updated_at=NOW()
 WHERE dim_key NOT IN ('multiAccount','arbitrage','kycStatus','withdrawVelocity','accountAge','anomalyBehavior')
   AND is_deleted=0;

INSERT INTO nx_admin_risk_score_config (config_key,value_text,is_deleted)
VALUES ('inputSource','全部启用',0),('bandLowMax','40',0),('bandHighMin','70',0),('autoEscalateScore','85',0)
ON DUPLICATE KEY UPDATE value_text=VALUES(value_text),is_deleted=0,updated_at=NOW();

UPDATE nx_admin_risk_score_override o
  LEFT JOIN nx_user u
    ON CONCAT('U',LPAD(u.id,8,'0'))=o.user_no AND u.is_deleted=0
   SET o.active=0,o.updated_at=NOW()
 WHERE o.active=1 AND o.is_deleted=0 AND u.id IS NULL;

UPDATE nx_admin_risk_score_contribution c
  LEFT JOIN nx_user u
    ON CONCAT('U',LPAD(u.id,8,'0'))=c.user_no AND u.is_deleted=0
   SET c.is_deleted=1
 WHERE c.is_deleted=0 AND u.id IS NULL;

UPDATE nx_admin_risk_score_user s
  LEFT JOIN nx_user u
    ON CONCAT('U',LPAD(u.id,8,'0'))=s.user_no AND u.is_deleted=0
   SET s.is_deleted=1,s.updated_at=NOW()
 WHERE s.is_deleted=0 AND u.id IS NULL;

INSERT INTO nx_admin_risk_score_user
  (user_no,model_score,model_version,row_version,as_of,updated_text,is_deleted)
SELECT CONCAT('U',LPAD(u.id,8,'0')),0,'pending',0,NOW(),'待首次评分',0
  FROM nx_user u
 WHERE u.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0,updated_at=NOW();

-- K1 multi-account graph closure: real sparse edges, optimistic concurrency and canonical parameters.
SET @k1_has_edges = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_risk_multi_account_cluster' AND COLUMN_NAME = 'edges_json'
);
SET @k1_sql = IF(@k1_has_edges = 0,
  'ALTER TABLE nx_admin_risk_multi_account_cluster ADD COLUMN edges_json MEDIUMTEXT NULL AFTER nodes_json',
  'SELECT 1');
PREPARE k1_stmt FROM @k1_sql;
EXECUTE k1_stmt;
DEALLOCATE PREPARE k1_stmt;

SET @k1_has_fingerprint = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_risk_multi_account_cluster' AND COLUMN_NAME = 'projection_fingerprint'
);
SET @k1_sql = IF(@k1_has_fingerprint = 0,
  'ALTER TABLE nx_admin_risk_multi_account_cluster ADD COLUMN projection_fingerprint VARCHAR(64) NULL AFTER edges_json',
  'SELECT 1');
PREPARE k1_stmt FROM @k1_sql;
EXECUTE k1_stmt;
DEALLOCATE PREPARE k1_stmt;

SET @k1_has_threshold_hit = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_risk_multi_account_cluster' AND COLUMN_NAME = 'threshold_hit'
);
SET @k1_sql = IF(@k1_has_threshold_hit = 0,
  'ALTER TABLE nx_admin_risk_multi_account_cluster ADD COLUMN threshold_hit TINYINT NOT NULL DEFAULT 0 AFTER projection_fingerprint',
  'SELECT 1');
PREPARE k1_stmt FROM @k1_sql;
EXECUTE k1_stmt;
DEALLOCATE PREPARE k1_stmt;

SET @k1_has_version = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_risk_multi_account_cluster' AND COLUMN_NAME = 'version'
);
SET @k1_sql = IF(@k1_has_version = 0,
  'ALTER TABLE nx_admin_risk_multi_account_cluster ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER review_note',
  'SELECT 1');
PREPARE k1_stmt FROM @k1_sql;
EXECUTE k1_stmt;
DEALLOCATE PREPARE k1_stmt;

ALTER TABLE nx_admin_risk_multi_account_cluster
  MODIFY COLUMN gifts_json MEDIUMTEXT NULL,
  MODIFY COLUMN nodes_json MEDIUMTEXT NULL,
  MODIFY COLUMN edges_json MEDIUMTEXT NULL;

INSERT INTO nx_admin_risk_param
  (section_key,param_key,name,value_text,unit_text,sub_text,note_text,sort_order,is_deleted)
VALUES
  ('k1','maxSignupPerIp24h','同 IP 24h 最大注册数','3','个','范围 1-10','超过阈值参与多账户聚类',10,0),
  ('k1','maxAccountsPerDevice','同设备最大账户数','2','个','范围 1-5','设备指纹关联阈值',20,0),
  ('k1','maxAccountsPerPaymentInstrument','同支付工具最大账户数','2','个','范围 1-5','支付工具关联阈值',30,0),
  ('k1','linkWeight','关联权重','设备 0.50 · 支付 0.40 · IP 0.10','','三项总和必须为 1','只计算建议强度，不自动冻结',40,0),
  ('k1','clusterFreezeSuggestThreshold','冻结建议阈值','0.7','','范围 0-1','达到阈值只给出冻结建议',50,0)
ON DUPLICATE KEY UPDATE
  name=VALUES(name),unit_text=VALUES(unit_text),sub_text=VALUES(sub_text),
  note_text=VALUES(note_text),sort_order=VALUES(sort_order),is_deleted=0,updated_at=NOW();

UPDATE nx_admin_risk_param
   SET is_deleted=1,updated_at=NOW()
 WHERE section_key='k1'
   AND param_key IN ('sameDeviceThreshold','ipOverlapThreshold','clusterStrengthThreshold','autoFreezeHighCluster');

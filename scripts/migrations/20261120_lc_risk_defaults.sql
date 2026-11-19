SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- Older bootstrap schemas left the authoritative K4 model table to application startup.
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
  published_by VARCHAR(64) NULL,
  published_at DATETIME NULL,
  archived_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_risk_score_model_version (model_version),
  KEY idx_admin_risk_score_model_state (state,is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

START TRANSACTION;

INSERT INTO nx_emergency_control_setting
  (setting_key, setting_value, value_type, group_code, remark, operator, is_deleted)
VALUES
  ('emergency.tamper.alert.threshold','10','NUMBER','admin_tamper','J3 alerts per 24 hours','migration',0),
  ('emergency.tamper.alert.feedK4','true','BOOLEAN','admin_tamper','J3 sends tamper signals to K4','migration',0)
ON DUPLICATE KEY UPDATE setting_key = nx_emergency_control_setting.setting_key;

INSERT INTO nx_admin_risk_param
  (section_key, param_key, name, value_text, unit_text, sub_text, note_text, sort_order, is_deleted)
VALUES
  ('k1','maxSignupPerIp24h','同 IP 24h 最大注册数','10','个','范围 1-10','超过阈值参与多账户聚类',10,0),
  ('k1','maxAccountsPerDevice','同设备最大账户数','2','个','范围 1-5','设备指纹关联阈值',20,0),
  ('k1','maxAccountsPerPaymentInstrument','同支付工具最大账户数','2','个','范围 1-5','支付工具关联阈值',30,0),
  ('k1','linkWeight','关联权重','设备 0.50 · 支付 0.40 · IP 0.10','','三项总和必须为 1','只计算建议强度，不自动冻结',40,0),
  ('k1','clusterFreezeSuggestThreshold','冻结建议阈值','0.7','','范围 0-1','达到阈值只给出冻结建议',50,0)
ON DUPLICATE KEY UPDATE param_key = nx_admin_risk_param.param_key;

-- The release start/freeze keys already come from 20260807_nexion_hard_blockers.
-- The application projects weights and bands from this authoritative version on startup.
INSERT INTO nx_admin_risk_score_model
  (model_version, row_version, state, weights_json, input_sources_json, score_mapping_json,
   band_low_max, band_high_min, auto_escalate_score, reason, created_by, published_by, published_at)
SELECT 1, 0, 'active',
  '{"multiAccount":30,"arbitrage":25,"withdrawVelocity":20,"accountAge":10,"anomalyBehavior":15}',
  '{"multiAccount":true,"arbitrage":true,"withdrawVelocity":true,"accountAge":true,"anomalyBehavior":true}',
  '{"multiAccount.mediumMin":2,"multiAccount.highMin":4,"multiAccount.mediumScore":40,"multiAccount.highScore":80,"multiAccount.fraudScore":100,"arbitrage.singleScore":30,"arbitrage.repeatMin":2,"arbitrage.repeatScore":70,"arbitrage.severeScore":100,"withdraw.baselineMultiplierPct":200,"withdraw.baselineScore":50,"withdraw.highFrequency24h":5,"withdraw.largeAmountUsd":5000,"withdraw.highScore":90,"account.matureDays":180,"account.newDays":7,"account.middleScore":30,"account.newLargeScore":70,"anomaly.lowScore":40,"anomaly.tamperScore":100}',
  40, 70, 85, 'initial canonical K4 model', 'system', 'system', NOW()
WHERE NOT EXISTS (SELECT 1 FROM nx_admin_risk_score_model);

COMMIT;

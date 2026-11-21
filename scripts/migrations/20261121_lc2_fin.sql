-- Launch defaults for platform, finance and withdrawal risk.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- Keep in sync with RiskOpsMapper.createWithdrawRuleTable.
CREATE TABLE IF NOT EXISTS nx_admin_risk_withdraw_rule (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  rule_id VARCHAR(64) NOT NULL,
  dimension VARCHAR(64) NOT NULL,
  condition_text VARCHAR(1000) NOT NULL,
  action VARCHAR(32) NOT NULL,
  state VARCHAR(32) NOT NULL DEFAULT 'draft',
  built_in TINYINT NOT NULL DEFAULT 0,
  priority INT NOT NULL DEFAULT 50,
  version BIGINT NOT NULL DEFAULT 0,
  created_by VARCHAR(64) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_risk_withdraw_rule (rule_id),
  KEY idx_admin_risk_withdraw_rule_state (state,is_deleted),
  KEY idx_admin_risk_withdraw_rule_dimension (dimension,is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

START TRANSACTION;

INSERT INTO nx_schema_migration_state (migration_key, phase)
VALUES ('20261121_lc2_fin', 'PENDING')
ON DUPLICATE KEY UPDATE migration_key = migration_key;

SELECT phase = 'PENDING' INTO @lc2_fin_pending
  FROM nx_schema_migration_state
 WHERE migration_key = '20261121_lc2_fin' FOR UPDATE;

UPDATE nx_config_item cfg
JOIN (
  SELECT 'auth.session.idle_ttl_days' AS config_key, '7' AS old_value, '30' AS new_value
  UNION ALL SELECT 'platform.global_rate_limit_per_minute', '6000', '60000'
  UNION ALL SELECT 'withdrawal.strong_review_threshold_usdt', '1000', '500'
  UNION ALL SELECT 'market.genesis.ops.showcase_enabled', 'true', 'false'
) defaults ON defaults.config_key = cfg.config_key
   SET cfg.config_value = defaults.new_value
 WHERE @lc2_fin_pending AND cfg.status = 1 AND cfg.is_deleted = 0
   AND BINARY cfg.config_value = BINARY defaults.old_value;

-- D5 publishes the canonical amount and its mirror at the same aggregate version.
-- The Bash install's D5 post step runs after the startup migration list.
INSERT INTO nx_config_item (config_key, config_value, value_type, config_group, visibility, remark)
SELECT 'withdrawal.d5.version', '1', 'NUMBER', 'wallet', 'ADMIN', 'D5 aggregate config version'
 WHERE @lc2_fin_pending
ON DUPLICATE KEY UPDATE config_key = nx_config_item.config_key;

UPDATE nx_config_item canonical
JOIN nx_config_item mirror ON mirror.config_key = 'wallet.withdrawal.nex_fee_offset_rate'
JOIN nx_config_item revision ON revision.config_key = 'withdrawal.d5.version'
   SET canonical.config_value = '0.12',
       mirror.config_value = '0.12',
       revision.config_value = CAST(revision.config_value AS UNSIGNED) + 1
 WHERE @lc2_fin_pending
   AND canonical.config_key = 'withdrawal.nex_fee_offset_rate'
   AND canonical.status = 1 AND canonical.is_deleted = 0
   AND mirror.status = 1 AND mirror.is_deleted = 0
   AND revision.status = 1 AND revision.is_deleted = 0
   AND BINARY canonical.config_value = '0.40' AND BINARY mirror.config_value = '0.40'
   AND NOT EXISTS (
     SELECT 1 FROM nx_audit_object_lock
      WHERE target_domain = 'D' AND target_type = 'withdrawal_limits'
        AND target_id = 'current' AND is_deleted = 0
   );

INSERT INTO nx_config_item (config_key, config_value, value_type, config_group, visibility, remark)
SELECT defaults.config_key, defaults.config_value, defaults.value_type, defaults.config_group, 'ADMIN', defaults.remark
  FROM (
    SELECT 'platform.experience.operating_languages' AS config_key, '["en","vi"]' AS config_value, 'JSON' AS value_type,
           'platform_app_experience' AS config_group, 'A3 operating region languages' AS remark, '' AS exchange_target
    UNION ALL SELECT 'wallet.exchange.user_daily_cap_usdt', '500', 'NUMBER', 'wallet', 'G2 user daily exchange cap', 'userDailyCap'
    UNION ALL SELECT 'wallet.exchange.queue_mode', 'REJECT', 'STRING', 'wallet', 'G2 over-cap queue strategy', 'queueMode'
  ) defaults
 WHERE @lc2_fin_pending AND NOT EXISTS (
   SELECT 1 FROM nx_audit_object_lock
    WHERE target_domain = 'G' AND target_type = 'exchange_param'
      AND target_id = defaults.exchange_target AND is_deleted = 0
 )
ON DUPLICATE KEY UPDATE config_key = nx_config_item.config_key;

-- A3 requires all three share rows to project a saved channel list. The editor
-- accepts an empty share base and an unavailable installer with empty metadata.
SET @lc2_share_text = 'Join UVEL with my invitation: {link}';
INSERT INTO nx_config_item (config_key, config_value, value_type, config_group, visibility, remark)
SELECT defaults.config_key, defaults.config_value, defaults.value_type, 'platform_app_experience', 'ADMIN', defaults.remark
  FROM (
    SELECT 'platform.experience.share.base_url' AS config_key, '' AS config_value, 'STRING' AS value_type,
           'A3 App experience base URL' AS remark
    UNION ALL SELECT 'platform.experience.app_download',
      '{"officialUrl":"","iosUrl":"","androidUrl":"","apkUrl":"","version":"","releaseNotes":{"zh":"","en":""},"source":"unavailable"}',
      'JSON', 'A3 App installer metadata'
    UNION ALL SELECT 'platform.experience.share.channels', CAST(JSON_ARRAY(
      JSON_OBJECT('key','zalo','intentType','scheme','textTemplate',@lc2_share_text,'androidPackage','com.zing.zalo','iosScheme','zalo://','enabled',TRUE),
      JSON_OBJECT('key','telegram','intentType','web','textTemplate',@lc2_share_text,'urlTemplate','https://t.me/share/url?url={link}&text={text}','enabled',TRUE),
      JSON_OBJECT('key','whatsapp','intentType','web','textTemplate',@lc2_share_text,'urlTemplate','https://wa.me/?text={text}','enabled',TRUE),
      JSON_OBJECT('key','messenger','intentType','scheme','textTemplate',@lc2_share_text,'androidPackage','com.facebook.orca','iosScheme','fb-messenger://','enabled',TRUE),
      JSON_OBJECT('key','sms','intentType','web','textTemplate',@lc2_share_text,'urlTemplate','sms:?body={text}','enabled',TRUE),
      JSON_OBJECT('key','x','intentType','web','textTemplate',@lc2_share_text,'urlTemplate','https://twitter.com/intent/tweet?text={text}','enabled',TRUE),
      JSON_OBJECT('key','copy','intentType','copy','enabled',TRUE),
      JSON_OBJECT('key','poster','intentType','poster','enabled',TRUE),
      JSON_OBJECT('key','system','intentType','system','enabled',TRUE)
    ) AS CHAR), 'JSON', 'A3 App share channels'
  ) defaults
 WHERE @lc2_fin_pending
ON DUPLICATE KEY UPDATE config_key = nx_config_item.config_key;

-- An existing canonical or legacy switch (including a deleted row) owns its
-- state. Never insert an enabled canonical switch ahead of such a decision.
INSERT INTO nx_emergency_control_setting (setting_key, setting_value, value_type, group_code, remark, operator)
SELECT CONCAT('killswitch.', gates.gate_key), 'enabled', 'STRING', 'admin_killswitch',
       'J1 launch kill switch default', 'migration:lc2'
  FROM (SELECT 'withdraw' AS gate_key UNION ALL SELECT 'trial' UNION ALL SELECT 'exchange') gates
 WHERE @lc2_fin_pending
   AND NOT EXISTS (
     SELECT 1 FROM nx_emergency_control_setting existing
      WHERE existing.setting_key IN (CONCAT('killswitch.', gates.gate_key), CONCAT('emergency.killswitch.', gates.gate_key))
   )
   AND NOT EXISTS (
     SELECT 1 FROM nx_audit_object_lock
      WHERE target_domain = 'J' AND target_type = 'kill_switch'
        AND target_id = gates.gate_key AND is_deleted = 0
   );

INSERT INTO nx_emergency_control_setting (setting_key, setting_value, value_type, group_code, remark, operator)
SELECT 'emergency.autorule.maturityGap', '10000', 'STRING', 'admin_emergency_autorule',
       'J1 auto trigger rule threshold', 'migration:lc2'
 WHERE @lc2_fin_pending
ON DUPLICATE KEY UPDATE setting_key = nx_emergency_control_setting.setting_key;

-- The PC create command saves a non-built-in draft at revision 0. Enabling
-- the draft yields active revision 1. Keep any operator-owned rule dimension.
INSERT INTO nx_admin_risk_withdraw_rule
  (rule_id, dimension, condition_text, action, state, built_in, priority, version, created_by)
SELECT rules.rule_id, rules.dimension, rules.condition_text, rules.action, 'active', 0, 50, 1, 'migration:lc2'
  FROM (
    SELECT 'WR-CF0001' AS rule_id, '金额' AS dimension, '单笔 >= $500' AS condition_text, 'manual' AS action,
           'largeAmountUsdt' AS alias_one, 'amount' AS alias_two
    UNION ALL SELECT 'WR-CF0002', '速度', '24h > 3 笔 或 > $5,000', 'manual', 'velocity24h', 'velocity'
    UNION ALL SELECT 'WR-CF0003', '新账户', '注册 < 7 天', 'delay', 'newAccountProtectDays', 'accountAge'
    UNION ALL SELECT 'WR-CF0004', '地址信誉', 'addressReputationSource=internal; addressReputationLowThreshold=0.4', 'freeze',
      'addressReputationSource', 'addressReputation'
  ) rules
 WHERE @lc2_fin_pending
   AND NOT EXISTS (
     SELECT 1 FROM nx_admin_risk_withdraw_rule existing
      WHERE existing.rule_id = rules.rule_id OR existing.dimension IN (rules.dimension, rules.alias_one, rules.alias_two)
   )
   AND NOT EXISTS (
     SELECT 1 FROM nx_audit_object_lock
      WHERE target_domain = 'K' AND target_type = 'rule' AND is_deleted = 0
        AND target_id IN (CONCAT(rules.dimension, ':', rules.condition_text),
                          CONCAT(rules.alias_one, ':', rules.condition_text),
                          CONCAT(rules.alias_two, ':', rules.condition_text))
   )
 ORDER BY rules.rule_id;

-- Payment quotes bind the policy version. Replace the original unconfigured
-- document as a whole and leave any operator revision and its quotes intact.
UPDATE nx_purchase_payment_policy
   SET policy_json = '[
     {"paymentMethod":"HDPAY_BANK","configured":true,"channelFeeRatePct":"0","channelFeeFixed":"0","networkConfirmFeeUsdt":"0","minTopupPrincipal":"10"},
     {"paymentMethod":"CREGIS_USDT_BSC","configured":true,"channelFeeRatePct":"0","channelFeeFixed":"0","networkConfirmFeeUsdt":"1","minTopupPrincipal":"1"},
     {"paymentMethod":"CREGIS_USDT_ERC20","configured":true,"channelFeeRatePct":"0","channelFeeFixed":"0","networkConfirmFeeUsdt":"5","minTopupPrincipal":"1"}
   ]',
       version = version + 1, updated_at = NOW(6)
 WHERE @lc2_fin_pending AND id = 1 AND version = 1
   AND policy_json = CAST('[
     {"paymentMethod":"HDPAY_BANK","configured":false,"channelFeeRatePct":null,"channelFeeFixed":null,"networkConfirmFeeUsdt":null,"minTopupPrincipal":"10"},
     {"paymentMethod":"CREGIS_USDT_BSC","configured":false,"channelFeeRatePct":null,"channelFeeFixed":null,"networkConfirmFeeUsdt":null,"minTopupPrincipal":"1"}
   ]' AS JSON);

-- New projects get their first policy from the configured project ID at app
-- startup. Do not invalidate any existing maker/checker case or revision.
UPDATE nx_cregis_exception_refund_policy policy
   SET policy.mode = 'PLATFORM_PAYS', policy.version = 1
 WHERE @lc2_fin_pending AND policy.project_id > 0
   AND policy.mode = 'UNCONFIGURED' AND policy.fee_usdt = 0 AND policy.version = 0
   AND NOT EXISTS (
     SELECT 1 FROM nx_cregis_exception_refund_policy_case proposal WHERE proposal.project_id = policy.project_id
   );

UPDATE nx_schema_migration_state SET phase = 'FINAL'
 WHERE migration_key = '20261121_lc2_fin' AND phase = 'PENDING';

COMMIT;

-- Launch defaults for H2, H5, H8, F1, F2 and M5.
SET NAMES utf8mb4;
START TRANSACTION;

UPDATE nx_growth_trial_policy
   SET current_value = CASE policy_key
     WHEN 'discountCapUSD' THEN '20'
     WHEN 'earlyPurchaseDiscountUSD' THEN '5'
     WHEN 'shadowDailyUSD' THEN '1.76'
     WHEN 'shadowDailyNEX' THEN '6.27'
   END
 WHERE is_deleted = 0 AND (
       (policy_key = 'discountCapUSD' AND current_value = '50')
    OR (policy_key = 'earlyPurchaseDiscountUSD' AND current_value = '10')
    OR (policy_key = 'shadowDailyUSD' AND current_value = '38.52')
    OR (policy_key = 'shadowDailyNEX' AND current_value = '65')
    OR (policy_key = 'shadowDailyUSD' AND current_value = '1'
        AND description = '由 E1 目标商品日收益同步')
    OR (policy_key = 'shadowDailyNEX' AND current_value = '1'
        AND description = '由 E1 目标商品日收益同步')
 );

-- The stored fallback is for the default target before E1 has a price.
UPDATE nx_growth_trial_policy price
JOIN nx_growth_trial_policy target
  ON target.policy_key = 'trialProductId' AND target.is_deleted = 0
 AND target.current_value = 'stellarbox-s1'
LEFT JOIN nx_product product
  ON product.product_no = target.current_value AND product.is_deleted = 0
   SET price.current_value = '119'
 WHERE price.policy_key = 'trialPriceUSD' AND price.is_deleted = 0
   AND price.current_value = '1299'
   AND (product.id IS NULL OR product.price_usdt = 119);

UPDATE nx_streak_milestone milestone
LEFT JOIN nx_audit_object_lock pending
  ON pending.target_domain = 'H' AND pending.target_type = 'h5_streak_milestone'
 AND pending.target_id = CAST(milestone.id AS CHAR) AND pending.is_deleted = 0
   SET milestone.reward_type = 'UVT',
       milestone.reward_amount = CASE milestone.milestone_day WHEN 21 THEN 30 WHEN 30 THEN 50 END,
       milestone.reward_name = CASE milestone.milestone_day WHEN 21 THEN '+30 UVT' WHEN 30 THEN '+50 UVT' END
 WHERE milestone.is_deleted = 0 AND milestone.status = 1 AND pending.id IS NULL
   AND ((milestone.milestone_day = 21 AND UPPER(milestone.reward_type) IN ('NEX', 'UVT') AND milestone.reward_amount = 100
         AND BINARY milestone.reward_name IN ('+100 UVT', '+100 NEX'))
     OR (milestone.milestone_day = 30 AND milestone.reward_type = 'SPIN' AND milestone.reward_amount = 1
         AND BINARY milestone.reward_name = BINARY 'Lucky Spin 票 ×1'));

UPDATE nx_streak_power_up power_up
LEFT JOIN nx_audit_object_lock pending
  ON pending.target_domain = 'H' AND pending.target_type = 'h5_power_up'
 AND pending.target_id = CAST(power_up.id AS CHAR) AND pending.is_deleted = 0
   SET power_up.status = 0, power_up.is_deleted = 1
 WHERE power_up.power_up_code = 'GENESIS_ALLOWLIST' AND power_up.status = 1 AND power_up.is_deleted = 0
   AND power_up.unlock_streak_days = 60
   AND BINARY power_up.effect_value = BINARY '连续签到 60 天解锁 Genesis 资格入口'
   AND pending.id IS NULL;

-- The legacy H8 gate is an untouched bundle only while no reward policy has
-- been written. Use a new eligibility start so the disabled cohort stays closed.
SET @lc2_h8_seed = (
  EXISTS (SELECT 1 FROM nx_config_item WHERE config_key = 'K.rewards.referral.enabled'
           AND config_value = 'false' AND status = 1 AND is_deleted = 0)
  AND EXISTS (SELECT 1 FROM nx_config_item WHERE config_key = 'K.rewards.referral.effectiveAt'
           AND config_value = '1970-01-01T00:00:00Z' AND status = 1 AND is_deleted = 0)
  AND EXISTS (SELECT 1 FROM nx_config_item WHERE config_key = 'K.rewards.referral.version'
           AND config_value = '1' AND status = 1 AND is_deleted = 0)
  AND NOT EXISTS (SELECT 1 FROM nx_config_item WHERE config_key IN (
    'K.rewards.referral.priorPolicy',
    'K.rewards.welcomeGift.usdtAmount', 'K.rewards.welcomeGift.nexAmount',
    'K.rewards.welcomeGift.lockMode', 'K.rewards.inviterReward.nexAmount'
  ))
  AND NOT EXISTS (SELECT 1 FROM nx_audit_object_lock WHERE target_domain = 'H'
    AND target_type = 'h8_reward_policy' AND target_id = 'current' AND is_deleted = 0)
);

UPDATE nx_config_item
   SET config_value = CASE config_key
         WHEN 'K.rewards.referral.enabled' THEN 'true'
         WHEN 'K.rewards.referral.effectiveAt' THEN DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ')
         WHEN 'K.rewards.referral.version' THEN '2'
       END
 WHERE @lc2_h8_seed AND config_key IN (
   'K.rewards.referral.enabled', 'K.rewards.referral.effectiveAt', 'K.rewards.referral.version'
 );

UPDATE nx_config_item
   SET remark = 'H8 launch referral reward policy'
 WHERE @lc2_h8_seed AND config_key IN ('K.rewards.referral.enabled', 'K.rewards.referral.effectiveAt');

INSERT INTO nx_config_item (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
SELECT seed.config_key, seed.config_value, seed.value_type, 'GROWTH_REFERRAL', 'ADMIN',
       'H8 launch referral reward policy', 1, 0
  FROM (
    SELECT 'K.rewards.welcomeGift.usdtAmount' AS config_key, '0' AS config_value, 'DECIMAL' AS value_type
    UNION ALL SELECT 'K.rewards.welcomeGift.nexAmount', '20', 'DECIMAL'
    UNION ALL SELECT 'K.rewards.inviterReward.nexAmount', '20', 'DECIMAL'
    UNION ALL SELECT 'K.rewards.welcomeGift.lockMode', 'risk_bucket', 'STRING'
  ) seed
 WHERE @lc2_h8_seed;

-- F1 bootstrap uses the same all-nine-ranks requirement as A2.
SET @lc2_f1_seed = (
  NOT EXISTS (SELECT 1 FROM nx_v_rank_config)
  AND NOT EXISTS (SELECT 1 FROM nx_v_rank_reward_rule)
  AND NOT EXISTS (SELECT 1 FROM nx_audit_object_lock WHERE target_domain = 'F'
    AND target_type = 'vrank_bootstrap' AND target_id = 'current' AND is_deleted = 0)
);

INSERT INTO nx_v_rank_config (
  rank_code, title_cn, title_en, self_compute_points, direct_refs, team_compute_points,
  required_downline_rank, required_downline_count, unilevel_depth, peer_bonus_rate,
  leadership_votes, sort_order, status, is_deleted
)
SELECT seed.rank_code, seed.rank_code, seed.rank_code, IF(seed.sort_order = 0, 0, 1200),
       seed.direct_refs, seed.team_points, seed.branch_rank, seed.branch_count,
       IF(seed.sort_order < 2, 'L1-L3', 'L1-L7'), 0, seed.votes, seed.sort_order, 1, 0
  FROM (
    SELECT 'V0' AS rank_code, 0 AS direct_refs, 0 AS team_points, CAST(NULL AS CHAR(2)) AS branch_rank,
           0 AS branch_count, 0 AS votes, 0 AS sort_order
    UNION ALL SELECT 'V1', 0, 0, NULL, 0, 0, 1
    UNION ALL SELECT 'V2', 2, 3000, NULL, 0, 0, 2
    UNION ALL SELECT 'V3', 3, 10000, 'V1', 2, 1, 3
    UNION ALL SELECT 'V4', 4, 30000, 'V2', 2, 2, 4
    UNION ALL SELECT 'V5', 5, 100000, 'V3', 2, 4, 5
    UNION ALL SELECT 'V6', 6, 300000, 'V4', 3, 6, 6
    UNION ALL SELECT 'V7', 8, 1000000, 'V5', 3, 10, 7
    UNION ALL SELECT 'V8', 10, 3000000, 'V6', 3, 15, 8
  ) seed
 WHERE @lc2_f1_seed
 ORDER BY seed.sort_order;

INSERT INTO nx_config_item (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
SELECT 'team.ui.F.vrank.permanent', 'off', 'TEXT', 'team', 'ADMIN', 'F1 launch default', 1, 0
 WHERE NOT EXISTS (SELECT 1 FROM nx_config_item WHERE config_key = 'team.ui.F.vrank.permanent')
   AND NOT EXISTS (SELECT 1 FROM nx_audit_object_lock WHERE target_domain = 'F'
     AND target_type = 'ui_config' AND target_id = 'F.vrank.permanent' AND is_deleted = 0);

-- Preset the inactive F2 policy and advance its existing configuration revision.
-- A pending F2 proposal must keep the revision and values it was submitted against.
SET @lc2_f2_seed = (
  NOT EXISTS (SELECT 1 FROM nx_config_item WHERE config_key = 'team.seven-layer.cutover-at'
    AND status = 1 AND is_deleted = 0)
  AND NOT EXISTS (SELECT 1 FROM nx_audit_object_lock WHERE target_domain = 'F' AND is_deleted = 0
    AND (target_type IN ('unilevel_rule', 'seven_layer_activation', 'direct_referral_policy')
      OR (target_type = 'ui_config'
          AND (target_id LIKE 'F.unilevel.%' OR target_id LIKE 'F.influence.%'
            OR target_id LIKE 'F.promo.%' OR target_id = 'F.cooldown'))))
);

INSERT INTO nx_commission_rule (commission_type, layer_no, usdt_rate, nex_per_usd, status, is_deleted)
SELECT 'unilevel', seed.layer_no, seed.usdt_rate, 0, 1, 0
  FROM (
    SELECT 1 AS layer_no, 0.10 AS usdt_rate
    UNION ALL SELECT 2, 0.05
    UNION ALL SELECT 3, 0.03
    UNION ALL SELECT 4, 0.02
    UNION ALL SELECT 5, 0.01
    UNION ALL SELECT 6, 0.005
    UNION ALL SELECT 7, 0.005
  ) seed
 WHERE @lc2_f2_seed AND NOT EXISTS (
   SELECT 1 FROM nx_commission_rule current_rule
    WHERE current_rule.commission_type = 'unilevel' AND current_rule.layer_no = seed.layer_no
 );
SET @lc2_f2_rules_inserted = ROW_COUNT();

INSERT INTO nx_config_item (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
SELECT seed.config_key, seed.config_value, 'TEXT', 'team', 'ADMIN', 'F2 launch default', 1, 0
  FROM (
    SELECT 'team.ui.F.unilevel.depthGate' AS config_key, 'L4' AS config_value
    UNION ALL SELECT 'team.ui.F.unilevel.depthGateRank', 'V2'
    UNION ALL SELECT 'team.ui.F.influence.clampMin', '1'
    UNION ALL SELECT 'team.ui.F.influence.clampMax', '1'
    UNION ALL SELECT 'team.ui.F.unilevel.mergeExitMaxPct', '25%'
    UNION ALL SELECT 'commission/cooling-days', '30'
    UNION ALL SELECT 'team.ui.F.promo.weekMultiplier', '1'
  ) seed
 WHERE @lc2_f2_seed
   AND NOT EXISTS (SELECT 1 FROM nx_config_item current_config WHERE current_config.config_key = seed.config_key);
SET @lc2_f2_params_inserted = ROW_COUNT();

UPDATE nx_config_item
   SET config_value = CAST(config_value AS UNSIGNED) + 1
 WHERE config_key = 'team.seven-layer.revision' AND status = 1 AND is_deleted = 0
   AND @lc2_f2_rules_inserted + @lc2_f2_params_inserted > 0;

-- A version above 1 is operator-owned, including explicitly cleared values.
UPDATE nx_support_rules
   SET dormant_days = COALESCE(dormant_days, 30),
       maintenance_days = COALESCE(maintenance_days, 7),
       activity_window_days = COALESCE(activity_window_days, 30),
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP(6)
 WHERE id = 1 AND version = 1
   AND (dormant_days IS NULL OR maintenance_days IS NULL OR activity_window_days IS NULL);

COMMIT;

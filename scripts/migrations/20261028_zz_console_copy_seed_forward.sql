-- Refresh only unchanged legacy display text after the historical startup seeds.
-- Business settings and operator-customized names/descriptions are not changed.
UPDATE nx_growth_trial_policy SET description='只影响新开始的试用', updated_at=NOW()
 WHERE BINARY policy_key IN ('trialDays','graceDays','extensionDays','autoChargeAtEnd','shadowDailyUSD','shadowDailyNEX')
   AND BINARY description=BINARY '新建试用锁定' AND is_deleted=0;
UPDATE nx_growth_trial_policy SET description='用户购买时按最新设置计算', updated_at=NOW()
 WHERE BINARY policy_key IN ('discountRate','discountCapUSD')
   AND BINARY description=BINARY '购买时由服务端重算' AND is_deleted=0;
UPDATE nx_growth_trial_policy SET description='只由系统判定，不对外显示', updated_at=NOW()
 WHERE BINARY policy_key=BINARY 'chargeFailRate' AND BINARY description=BINARY '仅服务端裁决' AND is_deleted=0;
UPDATE nx_growth_trial_policy SET description='从「商品管理与上架条件」中在售的实物商品里选择', updated_at=NOW()
 WHERE BINARY policy_key=BINARY 'trialProductId' AND BINARY description=BINARY '从 E1 在售实物 SKU 选择' AND is_deleted=0;
UPDATE nx_growth_trial_policy SET description='自动跟随所选商品的售价', updated_at=NOW()
 WHERE BINARY policy_key=BINARY 'trialPriceUSD' AND BINARY description=BINARY '由 E1 目标商品售价同步' AND is_deleted=0;
UPDATE nx_growth_trial_policy SET description='每次申请试用时检查', updated_at=NOW()
 WHERE BINARY policy_key=BINARY 'cooldownDays' AND BINARY description=BINARY '实时资格校验' AND is_deleted=0;
UPDATE nx_growth_trial_policy SET description='由「运营阶段设置」决定，这里只能查看', updated_at=NOW()
 WHERE BINARY policy_key=BINARY 'phaseOpen' AND BINARY description=BINARY '由 H1 当前阶段派发，只读' AND is_deleted=0;
UPDATE nx_growth_trial_policy SET policy_name='试用每日收益 USDT', updated_at=NOW()
 WHERE BINARY policy_key=BINARY 'shadowDailyUSD' AND BINARY policy_name=BINARY '每日影子收益 USDT' AND is_deleted=0;
UPDATE nx_growth_trial_policy SET policy_name='试用每日收益 UVT', updated_at=NOW()
 WHERE BINARY policy_key=BINARY 'shadowDailyNEX' AND BINARY policy_name=BINARY '每日影子收益 NEX' AND is_deleted=0;

UPDATE nx_growth_checkin_rule SET rule_name='幸运奖励最高倍率', updated_at=NOW()
 WHERE BINARY rule_key=BINARY 'luckyMultiplierMax' AND BINARY rule_name=BINARY 'Lucky最高倍率' AND is_deleted=0;
UPDATE nx_growth_checkin_rule SET description='随机奖励倍率的上限', updated_at=NOW()
 WHERE BINARY rule_key=BINARY 'luckyMultiplierMax' AND BINARY description=BINARY '服务端RNG倍率上限' AND is_deleted=0;
UPDATE nx_growth_checkin_rule SET description='抽中这一档的概率（百分比）', updated_at=NOW()
 WHERE BINARY rule_key IN ('p15','p2') AND BINARY description=BINARY '服务端RNG概率百分比' AND is_deleted=0;
UPDATE nx_growth_checkin_rule SET rule_name='断签补救卡张数', updated_at=NOW()
 WHERE BINARY rule_key=BINARY 'revive' AND BINARY rule_name=BINARY 'Streak Saver张数' AND is_deleted=0;

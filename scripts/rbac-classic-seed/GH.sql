-- 域 G+H · 金融+增长 · 54 权限点
-- 收敛自 docs/superpowers/specs/rbac-classic/GH.md（12 READ + 12 WRITE + 30 HIGH）
-- 幂等 INSERT：重复执行只更新不报错。不含 role_permission（后续统一处理）。

INSERT INTO nx_admin_permission (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted) VALUES
  -- ===== G 域 · 金融产品（finprod，30 点）=====

  -- G1 Staking 池配置 /finance-products/staking（6 点）
  ('finprod_g1_read',                'G1 Staking 页面读',           'API', '/finance-products/staking',    'READ',  0, 1, 0),
  ('finprod_g1_write',               'G1 Staking 常规写(停售/恢复开售)', 'API', '/finance-products/staking',    'WRITE', 0, 1, 0),
  ('finprod_g1_apy_write',           'G1 调 APY(升息放大流出·过B1)',  'API', '/finance-products/staking',    'HIGH',  1, 1, 0),
  ('finprod_g1_penalty_write',       'G1 调提前赎回罚款(降罚放大流出)', 'API', '/finance-products/staking',    'HIGH',  1, 1, 0),
  ('finprod_g1_min_write',           'G1 调最小额(降额放大负债)',     'API', '/finance-products/staking',    'HIGH',  1, 1, 0),
  ('finprod_g1_kill_toggle',         'G1 单档熔断/解除(止血·同步J1/B5)', 'API', '/finance-products/staking',    'HIGH',  1, 1, 0),

  -- G2 兑换风控 /finance-products/exchange（8 点）
  ('finprod_g2_read',                'G2 兑换风控页面读',            'API', '/finance-products/exchange',   'READ',  0, 1, 0),
  ('finprod_g2_write',               'G2 兑换常规写(最低费/队列策略/KYC复审)', 'API', '/finance-products/exchange',   'WRITE', 0, 1, 0),
  ('finprod_g2_cap_user_write',      'G2 单用户日额度(放宽放大流出·过B1)', 'API', '/finance-products/exchange',   'HIGH',  1, 1, 0),
  ('finprod_g2_cap_platform_write',  'G2 平台日额度(放宽放大流出·过B1)', 'API', '/finance-products/exchange',   'HIGH',  1, 1, 0),
  ('finprod_g2_cap_per_tx_write',    'G2 单笔额度(放宽放大流出·过B1)', 'API', '/finance-products/exchange',   'HIGH',  1, 1, 0),
  ('finprod_g2_fee_rate_write',      'G2 兑换费率(降费放大流出)',     'API', '/finance-products/exchange',   'HIGH',  1, 1, 0),
  ('finprod_g2_swap_toggle',         'G2 swap 全局熔断/恢复(止血·同步J1)', 'API', '/finance-products/exchange',   'HIGH',  1, 1, 0),
  ('finprod_g2_queue_cancel',        'G2 强制取消排队单(不可逆·退回余额)', 'API', '/finance-products/exchange',   'HIGH',  1, 1, 0),

  -- G3 NEX 行情引擎 /finance-products/market（6 点）
  ('finprod_g3_read',                'G3 NEX 行情页面读',            'API', '/finance-products/market',     'READ',  0, 1, 0),
  ('finprod_g3_write',               'G3 行情常规写(波动/排程/pin/loop/override非价/推进)', 'API', '/finance-products/market',     'WRITE', 0, 1, 0),
  ('finprod_g3_curve_target_price_write', 'G3 周曲线目标价(升价放大NEX负债·过B1)', 'API', '/finance-products/market',     'HIGH',  1, 1, 0),
  ('finprod_g3_curve_pump_prob_write',   'G3 周曲线上行概率(升概率放大流出)', 'API', '/finance-products/market',     'HIGH',  1, 1, 0),
  ('finprod_g3_override_price_write',    'G3 现价直写应急(过B1·60s全网生效)', 'API', '/finance-products/market',     'HIGH',  1, 1, 0),
  ('finprod_g3_engine_pause_toggle',     'G3 暂停/恢复行情引擎(止血·冻结现价)', 'API', '/finance-products/market',     'HIGH',  1, 1, 0),

  -- G4 Genesis 经济 /finance-products/genesis（6 点）
  ('finprod_g4_read',                'G4 Genesis 经济页面读',        'API', '/finance-products/genesis',    'READ',  0, 1, 0),
  ('finprod_g4_write',               'G4 Genesis 常规写(总量/基数口径/重跑批次)', 'API', '/finance-products/genesis',    'WRITE', 0, 1, 0),
  ('finprod_g4_price_write',         'G4 一级单价(改价放大Genesis负债·过B1)', 'API', '/finance-products/genesis',    'HIGH',  1, 1, 0),
  ('finprod_g4_dividend_rate_write', 'G4 每日分红率(升率放大分红负债·过B1)', 'API', '/finance-products/genesis',    'HIGH',  1, 1, 0),
  ('finprod_g4_royalty_write',       'G4 二级版税(改版税影响二级流转)', 'API', '/finance-products/genesis',    'HIGH',  1, 1, 0),
  ('finprod_g4_airdrop_pct_write',   'G4 空投比例(放大Genesis派发)',   'API', '/finance-products/genesis',    'HIGH',  1, 1, 0),
  ('finprod_g4_emission_curve_write','G4 释放曲线(改变长期释放负债)',   'API', '/finance-products/genesis',    'HIGH',  1, 1, 0),
  ('finprod_g4_airdrop_lock_days_write','G4 空投锁定期(改变流通节奏)', 'API', '/finance-products/genesis',    'HIGH',  1, 1, 0),
  ('finprod_g4_market_toggle',       'G4 一二级市场熔断/恢复(止血·同步J1)', 'API', '/finance-products/genesis',    'HIGH',  1, 1, 0),

  -- G7 复投激励 /finance-products/repurchase（4 点）
  ('finprod_g7_read',                'G7 复投激励页面读',            'API', '/finance-products/repurchase', 'READ',  0, 1, 0),
  ('finprod_g7_write',               'G7 复投常规写(抽奖券/早赎罚/preset)', 'API', '/finance-products/repurchase', 'WRITE', 0, 1, 0),
  ('finprod_g7_apy_write',           'G7 复投年化APY(升息放大流出·过B1)', 'API', '/finance-products/repurchase', 'HIGH',  1, 1, 0),
  ('finprod_g7_nurture_write',       'G7 培育奖倍率(升倍率放大奖励负债·过B1)', 'API', '/finance-products/repurchase', 'HIGH',  1, 1, 0),

  -- ===== H 域 · 增长与运营节奏（growth，18 点）=====

  -- H1 Phase 调度器 /growth/phase（4 点）
  ('growth_h1_read',                 'H1 Phase 调度器页面读',        'API', '/growth/phase',                'READ',  0, 1, 0),
  ('growth_h1_write',                'H1 Phase 常规写(总时长/位置/8旋钮/沙盒)', 'API', '/growth/phase',                'WRITE', 0, 1, 0),
  ('growth_h1_control_pin_write',    'H1 Phase 手动钉住(影响全局节奏)', 'API', '/growth/phase',                'HIGH',  1, 1, 0),
  ('growth_h1_override_revoke',      'H1 撤销 cohort override(影响全局节奏)', 'API', '/growth/phase',                'HIGH',  1, 1, 0),

  -- H2 免费试用引擎 /growth/trial（4 点）
  ('growth_h2_read',                 'H2 免费试用页面读',            'API', '/growth/trial',                'READ',  0, 1, 0),
  ('growth_h2_write',               'H2 试用常规写(6参数/auto-push急停)', 'API', '/growth/trial',                'WRITE', 0, 1, 0),
  ('growth_h2_session_cancel',       'H2 强制取消试用会话(不可逆·cancelled终态)', 'API', '/growth/trial',                'HIGH',  1, 1, 0),
  ('growth_h2_session_charge',       'H2 强制触发扣款(不可逆·重算·资金动作)', 'API', '/growth/trial',                'HIGH',  1, 1, 0),

  -- H3 任务引擎 /growth/quest（2 点）
  ('growth_h3_read',                 'H3 任务引擎页面读',            'API', '/growth/quest',                'READ',  0, 1, 0),
  ('growth_h3_write',               'H3 任务常规写(改奖励/新建任务/转化卡)', 'API', '/growth/quest',                'WRITE', 0, 1, 0),

  -- H4 活动中心 /growth/events（3 点）
  ('growth_h4_read',                 'H4 活动中心页面读',            'API', '/growth/events',               'READ',  0, 1, 0),
  ('growth_h4_write',               'H4 活动常规写(活动/转盘档位·护栏)', 'API', '/growth/events',               'WRITE', 0, 1, 0),
  ('growth_h4_wheel_pool_write',     'H4 转盘奖池/概率签名(真实出金·过B1)', 'API', '/growth/events',               'HIGH',  1, 1, 0),

  -- H5 签到 & NEX /growth/daily（3 点）
  ('growth_h5_read',                 'H5 签到&NEX页面读',            'API', '/growth/daily',                'READ',  0, 1, 0),
  ('growth_h5_write',               'H5 签到常规写(里程碑/Power-Up/收益/间隔)', 'API', '/growth/daily',                'WRITE', 0, 1, 0),
  ('growth_h5_rule_write',           'H5 签到规则Lucky概率(升概率放大NEX派发)', 'API', '/growth/daily',                'HIGH',  1, 1, 0),

  -- H7 代金券 /growth/vouchers（2 点）
  ('growth_h7_read',                 'H7 代金券页面读',              'API', '/growth/vouchers',              'READ',  0, 1, 0),
  ('growth_h7_write',               'H7 代金券常规写(新增/编辑/投放暂停/删除)', 'API', '/growth/vouchers',              'WRITE', 0, 1, 0),

  -- H8 新人礼与邀请人奖励 /growth/referral-rewards（3 点）
  ('growth_h8_read',                 'H8 邀请奖励页面读',            'API', '/growth/referral-rewards',       'READ',  0, 1, 0),
  ('growth_h8_write',                'H8 邀请奖励参数写',            'API', '/growth/referral-rewards',       'WRITE', 0, 1, 0),
  ('growth_h8_settle',               'H8 真实钱包与资金台账发奖',    'API', '/growth/referral-rewards',       'HIGH',  1, 1, 0)

ON DUPLICATE KEY UPDATE
  permission_name = VALUES(permission_name),
  resource_type   = VALUES(resource_type),
  resource_path   = VALUES(resource_path),
  perm_type       = VALUES(perm_type),
  amplifies       = VALUES(amplifies),
  status          = 1,
  is_deleted      = 0;

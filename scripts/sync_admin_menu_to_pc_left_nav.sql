USE nexion;

START TRANSACTION;

-- Keep the access-control menus intact, and make every non-permission business menu match the PC left nav.
UPDATE admin_menu
SET status = 0, updated_at = NOW()
WHERE is_deleted = 0
  AND menu_code LIKE 'MENU_%'
  AND menu_code NOT IN ('MENU_HOME', 'MENU_UMS', 'MENU_UMS_ADMIN', 'MENU_UMS_ROLE', 'MENU_UMS_MENU', 'MENU_UMS_PERMISSION', 'MENU_PLATFORM_BASE', 'MENU_PLATFORM_BASE_AUDIT', 'MENU_PLATFORM_BASE_APPROVALS', 'MENU_PLATFORM_BASE_SYSTEM', 'MENU_PLATFORM_BASE_EVENTS', 'MENU_PLATFORM_BASE_PHASE_CONFIG', 'MENU_DASHBOARD', 'MENU_DASHBOARD_COVERAGE', 'MENU_DASHBOARD_LIABILITY', 'MENU_DASHBOARD_FUNNEL', 'MENU_DASHBOARD_PHASE', 'MENU_DASHBOARD_RISK_RADAR', 'MENU_ACCOUNT', 'MENU_ACCOUNT_USERS', 'MENU_ACCOUNT_ACCOUNT_ACTIONS', 'MENU_ACCOUNT_ASSET_ADJUST', 'MENU_ACCOUNT_KYC', 'MENU_ACCOUNT_SESSIONS', 'MENU_ACCOUNT_AUTH_RISK', 'MENU_FINANCE', 'MENU_FINANCE_TOPUP', 'MENU_FINANCE_WITHDRAW', 'MENU_FINANCE_TREASURY', 'MENU_FINANCE_BILLS', 'MENU_FINANCE_WITHDRAW_CONFIG', 'MENU_DEVICE_COMMERCE', 'MENU_DEVICE_COMMERCE_SKU', 'MENU_DEVICE_COMMERCE_DEVICES', 'MENU_DEVICE_COMMERCE_GENERATION', 'MENU_DEVICE_COMMERCE_TASK_PRICING', 'MENU_DEVICE_COMMERCE_LIFECYCLE', 'MENU_DEVICE_COMMERCE_TRADEIN', 'MENU_DEVICE_COMMERCE_ORDERS', 'MENU_DISTRIBUTION_TEAM', 'MENU_DISTRIBUTION_TEAM_VRANK', 'MENU_DISTRIBUTION_TEAM_UNILEVEL', 'MENU_DISTRIBUTION_TEAM_BINARY', 'MENU_DISTRIBUTION_TEAM_LEADERSHIP', 'MENU_DISTRIBUTION_TEAM_COMMISSIONS', 'MENU_DISTRIBUTION_TEAM_QUOTA', 'MENU_DISTRIBUTION_TEAM_AGENT', 'MENU_DISTRIBUTION_TEAM_LEADERBOARD', 'MENU_FINANCIAL_PRODUCTS', 'MENU_FINANCIAL_PRODUCTS_STAKING', 'MENU_FINANCIAL_PRODUCTS_EXCHANGE', 'MENU_FINANCIAL_PRODUCTS_MARKET', 'MENU_FINANCIAL_PRODUCTS_GENESIS', 'MENU_FINANCIAL_PRODUCTS_PREMIUM', 'MENU_FINANCIAL_PRODUCTS_NEXV2', 'MENU_FINANCIAL_PRODUCTS_REINVEST', 'MENU_GROWTH', 'MENU_GROWTH_PHASE', 'MENU_GROWTH_TRIAL', 'MENU_GROWTH_QUEST', 'MENU_GROWTH_EVENTS', 'MENU_GROWTH_STREAK', 'MENU_GROWTH_MILESTONES', 'MENU_CMS', 'MENU_CMS_COPY', 'MENU_CMS_NOVA', 'MENU_CMS_NOTIFICATION', 'MENU_CMS_TRUST', 'MENU_CMS_DISCLOSURE', 'MENU_CMS_I18N', 'MENU_CMS_LEARN', 'MENU_EMERGENCY_CONTROL', 'MENU_EMERGENCY_CONTROL_KILLSWITCH', 'MENU_EMERGENCY_CONTROL_GEO', 'MENU_EMERGENCY_CONTROL_TAMPER', 'MENU_EMERGENCY_CONTROL_SOP', 'MENU_RISK_ANTI_CHEAT', 'MENU_RISK_ANTI_CHEAT_MULTI', 'MENU_RISK_ANTI_CHEAT_ARBITRAGE', 'MENU_RISK_ANTI_CHEAT_WITHDRAW_RULES', 'MENU_RISK_ANTI_CHEAT_SCORE', 'MENU_RISK_ANTI_CHEAT_KYC_REVIEW', 'MENU_BI', 'MENU_BI_KPI', 'MENU_BI_FUNNEL', 'MENU_BI_FINANCE', 'MENU_BI_OPS', 'MENU_BI_EXPORT');

INSERT INTO admin_menu
  (id, menu_code, menu_name, menu_name_zh, menu_name_en, parent_id, route_path, icon, sort_order, remark, status, is_deleted)
VALUES
  (1000, 'MENU_PLATFORM_BASE', 'Platform Base', '平台基础', 'Platform Base', NULL, '/platform-base', 'Setting', 1100, '平台基础目录，按PC左侧菜单同步。', 1, 0),
  (1001, 'MENU_PLATFORM_BASE_AUDIT', 'Audit Logs', '审计日志', 'Audit Logs', 1000, '/platform-base/audit', 'Document', 1101, '审计日志菜单，按PC左侧菜单同步。', 1, 0),
  (1002, 'MENU_PLATFORM_BASE_APPROVALS', 'Approval Tickets', '审批工单', 'Approval Tickets', 1000, '/platform-base/approvals', 'Document', 1102, '审批工单菜单，按PC左侧菜单同步。', 1, 0),
  (1003, 'MENU_PLATFORM_BASE_SYSTEM', 'System Parameters', '系统参数', 'System Parameters', 1000, '/platform-base/system', 'Document', 1103, '系统参数菜单，按PC左侧菜单同步。', 1, 0),
  (1004, 'MENU_PLATFORM_BASE_EVENTS', 'Event Tracking', '埋点事件', 'Event Tracking', 1000, '/platform-base/events', 'Document', 1104, '埋点事件菜单，按PC左侧菜单同步。', 1, 0),
  (1005, 'MENU_PLATFORM_BASE_PHASE_CONFIG', 'Generation Gates', '代际发布门', 'Generation Gates', 1000, '/platform-base/phase-config', 'Timer', 1105, '代际发布门菜单，按PC左侧菜单同步。', 1, 0),
  (1100, 'MENU_DASHBOARD', 'Overview Dashboard', '总览驾驶舱', 'Overview Dashboard', NULL, '/dashboard', 'DataBoard', 1200, '总览驾驶舱目录，按PC左侧菜单同步。', 1, 0),
  (1101, 'MENU_DASHBOARD_COVERAGE', 'Payout Coverage', '兑付覆盖率', 'Payout Coverage', 1100, '/dashboard/coverage', 'DataBoard', 1201, '兑付覆盖率菜单，按PC左侧菜单同步。', 1, 0),
  (1102, 'MENU_DASHBOARD_LIABILITY', 'Liability Waterline', '负债水位', 'Liability Waterline', 1100, '/dashboard/liability', 'DataBoard', 1202, '负债水位菜单，按PC左侧菜单同步。', 1, 0),
  (1103, 'MENU_DASHBOARD_FUNNEL', 'Conversion Funnel', '转化漏斗', 'Conversion Funnel', 1100, '/dashboard/funnel', 'DataBoard', 1203, '转化漏斗菜单，按PC左侧菜单同步。', 1, 0),
  (1104, 'MENU_DASHBOARD_PHASE', 'Phase Cadence', 'Phase节奏', 'Phase Cadence', 1100, '/dashboard/phase', 'DataBoard', 1204, 'Phase节奏菜单，按PC左侧菜单同步。', 1, 0),
  (1105, 'MENU_DASHBOARD_RISK_RADAR', 'Risk Radar', '风险雷达', 'Risk Radar', 1100, '/dashboard/risk-radar', 'DataBoard', 1205, '风险雷达菜单，按PC左侧菜单同步。', 1, 0),
  (1200, 'MENU_ACCOUNT', 'Users and Accounts', '用户与账户', 'Users and Accounts', NULL, '/account', 'User', 1300, '用户与账户目录，按PC左侧菜单同步。', 1, 0),
  (1201, 'MENU_ACCOUNT_USERS', 'User Search', '用户检索', 'User Search', 1200, '/account/users', 'Document', 1301, '用户检索菜单，按PC左侧菜单同步。', 1, 0),
  (1202, 'MENU_ACCOUNT_ACCOUNT_ACTIONS', 'Account Actions', '账户操作', 'Account Actions', 1200, '/account/account-actions', 'Document', 1302, '账户操作菜单，按PC左侧菜单同步。', 1, 0),
  (1203, 'MENU_ACCOUNT_ASSET_ADJUST', 'Asset Adjustments', '资产调整', 'Asset Adjustments', 1200, '/account/asset-adjust', 'Document', 1303, '资产调整菜单，按PC左侧菜单同步。', 1, 0),
  (1204, 'MENU_ACCOUNT_KYC', 'KYC Ledger', 'KYC台账', 'KYC Ledger', 1200, '/account/kyc', 'Document', 1304, 'KYC台账菜单，按PC左侧菜单同步。', 1, 0),
  (1205, 'MENU_ACCOUNT_SESSIONS', 'Security Sessions', '安全会话', 'Security Sessions', 1200, '/account/sessions', 'Document', 1305, '安全会话菜单，按PC左侧菜单同步。', 1, 0),
  (1206, 'MENU_ACCOUNT_AUTH_RISK', 'Login Risk Control', '登录风控', 'Login Risk Control', 1200, '/account/auth-risk', 'Document', 1306, '登录风控菜单，按PC左侧菜单同步。', 1, 0),
  (1300, 'MENU_FINANCE', 'Funds and Finance', '资金与财务', 'Funds and Finance', NULL, '/finance', 'Wallet', 1400, '资金与财务目录，按PC左侧菜单同步。', 1, 0),
  (1301, 'MENU_FINANCE_TOPUP', 'Deposit Reconciliation', '充值对账', 'Deposit Reconciliation', 1300, '/finance/topup', 'Document', 1401, '充值对账菜单，按PC左侧菜单同步。', 1, 0),
  (1302, 'MENU_FINANCE_WITHDRAW', 'Withdrawal Review', '提现审核', 'Withdrawal Review', 1300, '/finance/withdraw', 'Document', 1402, '提现审核菜单，按PC左侧菜单同步。', 1, 0),
  (1303, 'MENU_FINANCE_TREASURY', 'Reserve Pool', '储备资金池', 'Reserve Pool', 1300, '/finance/treasury', 'Document', 1403, '储备资金池菜单，按PC左侧菜单同步。', 1, 0),
  (1304, 'MENU_FINANCE_BILLS', 'Bill Ledger', '账单流水', 'Bill Ledger', 1300, '/finance/bills', 'Document', 1404, '账单流水菜单，按PC左侧菜单同步。', 1, 0),
  (1305, 'MENU_FINANCE_WITHDRAW_CONFIG', 'Withdrawal Parameters', '提现参数', 'Withdrawal Parameters', 1300, '/finance/withdraw-config', 'Document', 1405, '提现参数菜单，按PC左侧菜单同步。', 1, 0),
  (1400, 'MENU_DEVICE_COMMERCE', 'Device and Commerce', '设备与商城', 'Device and Commerce', NULL, '/device-commerce', 'Goods', 1500, '设备与商城目录，按PC左侧菜单同步。', 1, 0),
  (1401, 'MENU_DEVICE_COMMERCE_SKU', 'SKU Catalog', 'SKU目录', 'SKU Catalog', 1400, '/device-commerce/sku', 'Document', 1501, 'SKU目录菜单，按PC左侧菜单同步。', 1, 0),
  (1407, 'MENU_DEVICE_COMMERCE_DEVICES', 'User Devices', '用户设备', 'User Devices', 1400, '/device-commerce/devices', 'Cpu', 1502, '用户购买设备实例菜单，按PC左侧菜单同步。', 1, 0),
  (1402, 'MENU_DEVICE_COMMERCE_GENERATION', 'Generation Release', '代际发布', 'Generation Release', 1400, '/device-commerce/generation', 'Document', 1503, '代际发布菜单，按PC左侧菜单同步。', 1, 0),
  (1403, 'MENU_DEVICE_COMMERCE_TASK_PRICING', 'Task Pricing', '任务定价', 'Task Pricing', 1400, '/device-commerce/task-pricing', 'Document', 1504, '任务定价菜单，按PC左侧菜单同步。', 1, 0),
  (1404, 'MENU_DEVICE_COMMERCE_LIFECYCLE', 'Device Decay', '设备衰减', 'Device Decay', 1400, '/device-commerce/lifecycle', 'Document', 1505, '设备衰减菜单，按PC左侧菜单同步。', 1, 0),
  (1405, 'MENU_DEVICE_COMMERCE_TRADEIN', 'Trade-in Config', 'Trade-in配置', 'Trade-in Config', 1400, '/device-commerce/tradein', 'Document', 1506, 'Trade-in配置菜单，按PC左侧菜单同步。', 1, 0),
  (1406, 'MENU_DEVICE_COMMERCE_ORDERS', 'Order Fulfillment', '订单履约', 'Order Fulfillment', 1400, '/device-commerce/orders', 'Document', 1507, '订单履约菜单，按PC左侧菜单同步。', 1, 0),
  (1500, 'MENU_DISTRIBUTION_TEAM', 'Distribution and Team', '分销与团队', 'Distribution and Team', NULL, '/distribution-team', 'Connection', 1600, '分销与团队目录，按PC左侧菜单同步。', 1, 0),
  (1501, 'MENU_DISTRIBUTION_TEAM_VRANK', 'V-Rank Tiers', 'V-Rank阶梯', 'V-Rank Tiers', 1500, '/distribution-team/vrank', 'Document', 1601, 'V-Rank阶梯菜单，按PC左侧菜单同步。', 1, 0),
  (1502, 'MENU_DISTRIBUTION_TEAM_UNILEVEL', 'Unilevel Rates', 'Unilevel费率', 'Unilevel Rates', 1500, '/distribution-team/unilevel', 'Document', 1602, 'Unilevel费率菜单，按PC左侧菜单同步。', 1, 0),
  (1503, 'MENU_DISTRIBUTION_TEAM_BINARY', 'Binary Settlement', '双轨结算', 'Binary Settlement', 1500, '/distribution-team/binary', 'Document', 1603, '双轨结算菜单，按PC左侧菜单同步。', 1, 0),
  (1504, 'MENU_DISTRIBUTION_TEAM_LEADERSHIP', 'Leadership Pool', '领导池', 'Leadership Pool', 1500, '/distribution-team/leadership', 'Document', 1604, '领导池菜单，按PC左侧菜单同步。', 1, 0),
  (1505, 'MENU_DISTRIBUTION_TEAM_COMMISSIONS', 'Commission Ledger', '佣金流水', 'Commission Ledger', 1500, '/distribution-team/commissions', 'Document', 1605, '佣金流水菜单，按PC左侧菜单同步。', 1, 0),
  (1506, 'MENU_DISTRIBUTION_TEAM_QUOTA', 'Hardware Quota', '硬件配额', 'Hardware Quota', 1500, '/distribution-team/quota', 'Document', 1606, '硬件配额菜单，按PC左侧菜单同步。', 1, 0),
  (1507, 'MENU_DISTRIBUTION_TEAM_AGENT', 'Regional Ambassadors', '区域大使', 'Regional Ambassadors', 1500, '/distribution-team/agent', 'Document', 1607, '区域大使菜单，按PC左侧菜单同步。', 1, 0),
  (1508, 'MENU_DISTRIBUTION_TEAM_LEADERBOARD', 'Leaderboard', '排行榜', 'Leaderboard', 1500, '/distribution-team/leaderboard', 'Document', 1608, '排行榜菜单，按PC左侧菜单同步。', 1, 0),
  (1600, 'MENU_FINANCIAL_PRODUCTS', 'Financial Products', '金融产品', 'Financial Products', NULL, '/financial-products', 'Money', 1700, '金融产品目录，按PC左侧菜单同步。', 1, 0),
  (1601, 'MENU_FINANCIAL_PRODUCTS_STAKING', 'Staking Pools', 'Staking池', 'Staking Pools', 1600, '/financial-products/staking', 'Document', 1701, 'Staking池菜单，按PC左侧菜单同步。', 1, 0),
  (1602, 'MENU_FINANCIAL_PRODUCTS_EXCHANGE', 'NEX Exchange', 'NEX兑换', 'NEX Exchange', 1600, '/financial-products/exchange', 'Document', 1702, 'NEX兑换菜单，按PC左侧菜单同步。', 1, 0),
  (1603, 'MENU_FINANCIAL_PRODUCTS_MARKET', 'NEX Market', 'NEX行情', 'NEX Market', 1600, '/financial-products/market', 'Document', 1703, 'NEX行情菜单，按PC左侧菜单同步。', 1, 0),
  (1604, 'MENU_FINANCIAL_PRODUCTS_GENESIS', 'Genesis Nodes', 'Genesis节点', 'Genesis Nodes', 1600, '/financial-products/genesis', 'Document', 1704, 'Genesis节点菜单，按PC左侧菜单同步。', 1, 0),
  (1605, 'MENU_FINANCIAL_PRODUCTS_PREMIUM', 'Premium Subscription', 'Premium订阅', 'Premium Subscription', 1600, '/financial-products/premium', 'Document', 1705, 'Premium订阅菜单，按PC左侧菜单同步。', 1, 0),
  (1606, 'MENU_FINANCIAL_PRODUCTS_NEXV2', 'NEX v2 Lock', 'NEX v2锁仓', 'NEX v2 Lock', 1600, '/financial-products/nexv2', 'Document', 1706, 'NEX v2锁仓菜单，按PC左侧菜单同步。', 1, 0),
  (1607, 'MENU_FINANCIAL_PRODUCTS_REINVEST', 'Reinvest Config', '复投配置', 'Reinvest Config', 1600, '/financial-products/reinvest', 'Document', 1707, '复投配置菜单，按PC左侧菜单同步。', 1, 0),
  (1700, 'MENU_GROWTH', 'Growth and Cadence', '增长与节奏', 'Growth and Cadence', NULL, '/growth', 'TrendCharts', 1800, '增长与节奏目录，按PC左侧菜单同步。', 1, 0),
  (1701, 'MENU_GROWTH_PHASE', 'Phase Scheduling', 'Phase调度', 'Phase Scheduling', 1700, '/growth/phase', 'Document', 1801, 'Phase调度菜单，按PC左侧菜单同步。', 1, 0),
  (1702, 'MENU_GROWTH_TRIAL', 'Trial', 'Trial试用', 'Trial', 1700, '/growth/trial', 'Document', 1802, 'Trial试用菜单，按PC左侧菜单同步。', 1, 0),
  (1703, 'MENU_GROWTH_QUEST', 'Quest Tasks', 'Quest任务', 'Quest Tasks', 1700, '/growth/quest', 'Document', 1803, 'Quest任务菜单，按PC左侧菜单同步。', 1, 0),
  (1704, 'MENU_GROWTH_EVENTS', 'Event Center', '活动中心', 'Event Center', 1700, '/growth/events', 'Document', 1804, '活动中心菜单，按PC左侧菜单同步。', 1, 0),
  (1705, 'MENU_GROWTH_STREAK', 'Check-in and Streak', '签到与Streak', 'Check-in and Streak', 1700, '/growth/streak', 'Document', 1805, '签到与Streak菜单，按PC左侧菜单同步。', 1, 0),
  (1706, 'MENU_GROWTH_MILESTONES', 'Milestones', '里程碑', 'Milestones', 1700, '/growth/milestones', 'Document', 1806, '里程碑菜单，按PC左侧菜单同步。', 1, 0),
  (1800, 'MENU_CMS', 'Content and Compliance CMS', '内容与合规CMS', 'Content and Compliance CMS', NULL, '/cms', 'Document', 1900, '内容与合规CMS目录，按PC左侧菜单同步。', 1, 0),
  (1801, 'MENU_CMS_COPY', 'Conversion Copy', '转化文案', 'Conversion Copy', 1800, '/cms/copy', 'Document', 1901, '转化文案菜单，按PC左侧菜单同步。', 1, 0),
  (1802, 'MENU_CMS_NOVA', 'Nova Push', 'Nova推送', 'Nova Push', 1800, '/cms/nova', 'Document', 1902, 'Nova推送菜单，按PC左侧菜单同步。', 1, 0),
  (1803, 'MENU_CMS_NOTIFICATION', 'Notification Campaigns', '通知Campaign', 'Notification Campaigns', 1800, '/cms/notification', 'Document', 1903, '通知Campaign菜单，按PC左侧菜单同步。', 1, 0),
  (1804, 'MENU_CMS_TRUST', 'Trust Center', '信任中心', 'Trust Center', 1800, '/cms/trust', 'Document', 1904, '信任中心菜单，按PC左侧菜单同步。', 1, 0),
  (1805, 'MENU_CMS_DISCLOSURE', 'Risk Disclosure', '风险披露', 'Risk Disclosure', 1800, '/cms/disclosure', 'Document', 1905, '风险披露菜单，按PC左侧菜单同步。', 1, 0),
  (1806, 'MENU_CMS_I18N', 'i18n Copy', 'i18n文案', 'i18n Copy', 1800, '/cms/i18n', 'Document', 1906, 'i18n文案菜单，按PC左侧菜单同步。', 1, 0),
  (1807, 'MENU_CMS_LEARN', 'Tutorial Center', '教程中心', 'Tutorial Center', 1800, '/cms/learn', 'Document', 1907, '教程中心菜单，按PC左侧菜单同步。', 1, 0),
  (1900, 'MENU_EMERGENCY_CONTROL', 'Emergency and Compliance Control', '紧急与合规控制', 'Emergency and Compliance Control', NULL, '/emergency-control', 'Warning', 2000, '紧急与合规控制目录，按PC左侧菜单同步。', 1, 0),
  (1901, 'MENU_EMERGENCY_CONTROL_KILLSWITCH', 'Kill-Switch Matrix', 'Kill-Switch矩阵', 'Kill-Switch Matrix', 1900, '/emergency-control/killswitch', 'Document', 2001, 'Kill-Switch矩阵菜单，按PC左侧菜单同步。', 1, 0),
  (1902, 'MENU_EMERGENCY_CONTROL_GEO', 'Geo-block', 'Geo-block', 'Geo-block', 1900, '/emergency-control/geo', 'Document', 2002, 'Geo-block菜单，按PC左侧菜单同步。', 1, 0),
  (1903, 'MENU_EMERGENCY_CONTROL_TAMPER', 'Tamper Monitoring', '篡改监控', 'Tamper Monitoring', 1900, '/emergency-control/tamper', 'Document', 2003, '篡改监控菜单，按PC左侧菜单同步。', 1, 0),
  (1904, 'MENU_EMERGENCY_CONTROL_SOP', 'Emergency SOP', '应急SOP', 'Emergency SOP', 1900, '/emergency-control/sop', 'Document', 2004, '应急SOP菜单，按PC左侧菜单同步。', 1, 0),
  (2000, 'MENU_RISK_ANTI_CHEAT', 'Risk and Anti-Cheat', '风控与反作弊', 'Risk and Anti-Cheat', NULL, '/risk-anti-cheat', 'Aim', 2100, '风控与反作弊目录，按PC左侧菜单同步。', 1, 0),
  (2001, 'MENU_RISK_ANTI_CHEAT_MULTI', 'Multi-account Clusters', '多账号簇', 'Multi-account Clusters', 2000, '/risk-anti-cheat/multi', 'Document', 2101, '多账号簇菜单，按PC左侧菜单同步。', 1, 0),
  (2002, 'MENU_RISK_ANTI_CHEAT_ARBITRAGE', 'Arbitrage Abuse', '套利刷量', 'Arbitrage Abuse', 2000, '/risk-anti-cheat/arbitrage', 'Document', 2102, '套利刷量菜单，按PC左侧菜单同步。', 1, 0),
  (2003, 'MENU_RISK_ANTI_CHEAT_WITHDRAW_RULES', 'Withdrawal Risk Rules', '提现风控规则', 'Withdrawal Risk Rules', 2000, '/risk-anti-cheat/withdraw-rules', 'Document', 2103, '提现风控规则菜单，按PC左侧菜单同步。', 1, 0),
  (2004, 'MENU_RISK_ANTI_CHEAT_SCORE', 'Risk Scoring Model', '风险评分模型', 'Risk Scoring Model', 2000, '/risk-anti-cheat/score', 'Document', 2104, '风险评分模型菜单，按PC左侧菜单同步。', 1, 0),
  (2005, 'MENU_RISK_ANTI_CHEAT_KYC_REVIEW', 'KYC Review', 'KYC复审', 'KYC Review', 2000, '/risk-anti-cheat/kyc-review', 'Document', 2105, 'KYC复审菜单，按PC左侧菜单同步。', 1, 0),
  (2100, 'MENU_BI', 'Data and BI', '数据与BI', 'Data and BI', NULL, '/bi', 'DataAnalysis', 2200, '数据与BI目录，按PC左侧菜单同步。', 1, 0),
  (2101, 'MENU_BI_KPI', 'KPI Dashboard', 'KPI看板', 'KPI Dashboard', 2100, '/bi/kpi', 'DataBoard', 2201, 'KPI看板菜单，按PC左侧菜单同步。', 1, 0),
  (2102, 'MENU_BI_FUNNEL', 'Funnel Cohort', '漏斗Cohort', 'Funnel Cohort', 2100, '/bi/funnel', 'DataBoard', 2202, '漏斗Cohort菜单，按PC左侧菜单同步。', 1, 0),
  (2103, 'MENU_BI_FINANCE', 'Finance Reports', '财务报表', 'Finance Reports', 2100, '/bi/finance', 'Document', 2203, '财务报表菜单，按PC左侧菜单同步。', 1, 0),
  (2104, 'MENU_BI_OPS', 'Ops Reports', '运营报表', 'Ops Reports', 2100, '/bi/ops', 'Document', 2204, '运营报表菜单，按PC左侧菜单同步。', 1, 0),
  (2105, 'MENU_BI_EXPORT', 'Export Center', '导出中心', 'Export Center', 2100, '/bi/export', 'Document', 2205, '导出中心菜单，按PC左侧菜单同步。', 1, 0)
ON DUPLICATE KEY UPDATE
  menu_name = VALUES(menu_name),
  menu_name_zh = VALUES(menu_name_zh),
  menu_name_en = VALUES(menu_name_en),
  parent_id = VALUES(parent_id),
  route_path = VALUES(route_path),
  icon = VALUES(icon),
  sort_order = VALUES(sort_order),
  remark = VALUES(remark),
  status = 1,
  is_deleted = 0,
  updated_at = NOW();

-- Preserve super-admin access to the complete visible menu tree.
INSERT INTO admin_role_menu (role_id, menu_id, created_at, updated_at, is_deleted)
SELECT r.id, m.id, NOW(), NOW(), 0
FROM admin_role r
JOIN admin_menu m ON m.is_deleted = 0 AND m.status = 1
WHERE r.role_code = 'SUPER_ADMIN' AND r.is_deleted = 0
ON DUPLICATE KEY UPDATE is_deleted = 0, updated_at = NOW();

COMMIT;

SELECT id, menu_code, menu_name_zh, route_path, parent_id, sort_order, status
FROM admin_menu
WHERE is_deleted = 0 AND status = 1 AND menu_code LIKE 'MENU_%'
ORDER BY sort_order, id;

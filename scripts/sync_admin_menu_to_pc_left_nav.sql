USE nexion;

START TRANSACTION;

-- Keep the access-control menus intact, and make every non-permission business menu match the PC left nav from nexion-ops-console/lib/nav/console-nav.ts.
UPDATE nx_admin_menu
SET status = 0, updated_at = NOW()
WHERE is_deleted = 0
  AND menu_code LIKE 'MENU_%'
  AND menu_code NOT IN ('MENU_HOME', 'MENU_UMS', 'MENU_UMS_ADMIN', 'MENU_UMS_ROLE', 'MENU_UMS_MENU', 'MENU_UMS_PERMISSION', 'MENU_PLATFORM', 'MENU_PLATFORM_A1', 'MENU_PLATFORM_A2', 'MENU_PLATFORM_A3', 'MENU_PLATFORM_A4', 'MENU_PLATFORM_A5', 'MENU_OVERVIEW', 'MENU_OVERVIEW_B1', 'MENU_OVERVIEW_B2', 'MENU_OVERVIEW_B3', 'MENU_OVERVIEW_B4', 'MENU_OVERVIEW_B5', 'MENU_USERS', 'MENU_USERS_C1', 'MENU_USERS_C2', 'MENU_USERS_C3', 'MENU_USERS_C4', 'MENU_USERS_C5', 'MENU_USERS_C6', 'MENU_FINANCE', 'MENU_FINANCE_D1', 'MENU_FINANCE_D2', 'MENU_FINANCE_D3', 'MENU_FINANCE_D4', 'MENU_FINANCE_D5', 'MENU_DEVICES', 'MENU_DEVICES_E1', 'MENU_DEVICES_E2', 'MENU_DEVICES_E3', 'MENU_DEVICES_E4', 'MENU_DEVICES_E5', 'MENU_NETWORK', 'MENU_NETWORK_F1', 'MENU_NETWORK_F2', 'MENU_NETWORK_F3', 'MENU_NETWORK_F4', 'MENU_NETWORK_F5', 'MENU_FINANCE_PRODUCTS', 'MENU_FINANCE_PRODUCTS_G1', 'MENU_FINANCE_PRODUCTS_G2', 'MENU_FINANCE_PRODUCTS_G3', 'MENU_FINANCE_PRODUCTS_G4', 'MENU_FINANCE_PRODUCTS_G7', 'MENU_GROWTH', 'MENU_GROWTH_H1', 'MENU_GROWTH_H2', 'MENU_GROWTH_H3', 'MENU_GROWTH_H4', 'MENU_GROWTH_H5', 'MENU_GROWTH_H6', 'MENU_CONTENT', 'MENU_CONTENT_I1', 'MENU_CONTENT_I2', 'MENU_CONTENT_I3', 'MENU_CONTENT_I4', 'MENU_CONTENT_I5', 'MENU_CONTENT_I6', 'MENU_CONTENT_I7', 'MENU_EMERGENCY', 'MENU_EMERGENCY_J1', 'MENU_EMERGENCY_J2', 'MENU_EMERGENCY_J3', 'MENU_EMERGENCY_J4', 'MENU_RISK', 'MENU_RISK_K1', 'MENU_RISK_K2', 'MENU_RISK_K3', 'MENU_RISK_K4', 'MENU_RISK_K5', 'MENU_RISK_K6', 'MENU_ANALYTICS', 'MENU_ANALYTICS_L1', 'MENU_ANALYTICS_L2', 'MENU_ANALYTICS_L3', 'MENU_ANALYTICS_L4', 'MENU_ANALYTICS_L5', 'MENU_SERVICE', 'MENU_SERVICE_M1', 'MENU_SERVICE_M2', 'MENU_SERVICE_M3', 'MENU_SERVICE_M4', 'MENU_SERVICE_M5');

INSERT INTO nx_admin_menu
  (id, menu_code, menu_name, menu_name_zh, menu_name_en, parent_id, route_path, icon, sort_order, remark, status, is_deleted)
VALUES
  (1000, 'MENU_PLATFORM', '平台基础', '平台基础', 'platform', NULL, '/platform', 'ShieldCheck', 1000, '平台基础目录,按PC左侧菜单同步。', 1, 0),
  (1001, 'MENU_PLATFORM_A1', '运营账号 & RBAC', '运营账号 & RBAC', 'A1', 1000, '/platform/rbac', 'Document', 1001, 'A1 运营账号 & RBAC,按PC左侧菜单同步。', 1, 0),
  (1002, 'MENU_PLATFORM_A2', '审计 & 操作确认', '审计 & 操作确认', 'A2', 1000, '/platform/audit', 'Document', 1002, 'A2 审计 & 操作确认,按PC左侧菜单同步。', 1, 0),
  (1003, 'MENU_PLATFORM_A3', '系统配置', '系统配置', 'A3', 1000, '/platform/config', 'Document', 1003, 'A3 系统配置,按PC左侧菜单同步。', 1, 0),
  (1004, 'MENU_PLATFORM_A4', '埋点事件体系', '埋点事件体系', 'A4', 1000, '/platform/events', 'Document', 1004, 'A4 埋点事件体系,按PC左侧菜单同步。', 1, 0),
  (1005, 'MENU_PLATFORM_A5', '平台参数寄存器', '平台参数寄存器', 'A5', 1000, '/platform/params-registry', 'Document', 1005, 'A5 平台参数寄存器,按PC左侧菜单同步。', 1, 0),
  (1100, 'MENU_OVERVIEW', '总览驾驶舱', '总览驾驶舱', 'overview', NULL, '/overview', 'Gauge', 1100, '总览驾驶舱目录,按PC左侧菜单同步。', 1, 0),
  (1101, 'MENU_OVERVIEW_B1', '双账本总览', '双账本总览', 'B1', 1100, '/overview/dual-ledger', 'Document', 1101, 'B1 双账本总览,按PC左侧菜单同步。', 1, 0),
  (1102, 'MENU_OVERVIEW_B2', '资金池水位', '资金池水位', 'B2', 1100, '/overview/liquidity', 'Document', 1102, 'B2 资金池水位,按PC左侧菜单同步。', 1, 0),
  (1103, 'MENU_OVERVIEW_B3', '转化漏斗', '转化漏斗', 'B3', 1100, '/overview/funnel', 'Document', 1103, 'B3 转化漏斗,按PC左侧菜单同步。', 1, 0),
  (1104, 'MENU_OVERVIEW_B4', '节奏状态', '节奏状态', 'B4', 1100, '/overview/rhythm', 'Document', 1104, 'B4 节奏状态,按PC左侧菜单同步。', 1, 0),
  (1105, 'MENU_OVERVIEW_B5', '风险雷达', '风险雷达', 'B5', 1100, '/overview/risk-radar', 'Document', 1105, 'B5 风险雷达,按PC左侧菜单同步。', 1, 0),
  (1200, 'MENU_USERS', '用户与账户', '用户与账户', 'users', NULL, '/users', 'Users', 1200, '用户与账户目录,按PC左侧菜单同步。', 1, 0),
  (1201, 'MENU_USERS_C1', '检索 & 画像', '检索 & 画像', 'C1', 1200, '/users/search', 'Document', 1201, 'C1 检索 & 画像,按PC左侧菜单同步。', 1, 0),
  (1202, 'MENU_USERS_C2', '账户操作', '账户操作', 'C2', 1200, '/users/actions', 'Document', 1202, 'C2 账户操作,按PC左侧菜单同步。', 1, 0),
  (1203, 'MENU_USERS_C3', '余额 & 资产调整', '余额 & 资产调整', 'C3', 1200, '/users/assets', 'Document', 1203, 'C3 余额 & 资产调整,按PC左侧菜单同步。', 1, 0),
  (1204, 'MENU_USERS_C4', 'KYC 合规台账', 'KYC 合规台账', 'C4', 1200, '/users/kyc', 'Document', 1204, 'C4 KYC 合规台账,按PC左侧菜单同步。', 1, 0),
  (1205, 'MENU_USERS_C5', '安全 & 会话', '安全 & 会话', 'C5', 1200, '/users/security', 'Document', 1205, 'C5 安全 & 会话,按PC左侧菜单同步。', 1, 0),
  (1206, 'MENU_USERS_C6', '注册/登录风控', '注册/登录风控', 'C6', 1200, '/users/reg-risk', 'Document', 1206, 'C6 注册/登录风控,按PC左侧菜单同步。', 1, 0),
  (1300, 'MENU_FINANCE', '资金与财务', '资金与财务', 'finance', NULL, '/finance', 'Wallet', 1300, '资金与财务目录,按PC左侧菜单同步。', 1, 0),
  (1301, 'MENU_FINANCE_D1', '充值对账中心', '充值对账中心', 'D1', 1300, '/finance/recon', 'Document', 1301, 'D1 充值对账中心,按PC左侧菜单同步。', 1, 0),
  (1302, 'MENU_FINANCE_D2', '提现审核队列', '提现审核队列', 'D2', 1300, '/finance/withdrawals', 'Document', 1302, 'D2 提现审核队列,按PC左侧菜单同步。', 1, 0),
  (1303, 'MENU_FINANCE_D3', '资金池水位仪表盘', '资金池水位仪表盘', 'D3', 1300, '/finance/pool', 'Document', 1303, 'D3 资金池水位仪表盘,按PC左侧菜单同步。', 1, 0),
  (1304, 'MENU_FINANCE_D4', '账本/账单审计', '账本/账单审计', 'D4', 1300, '/finance/ledger', 'Document', 1304, 'D4 账本/账单审计,按PC左侧菜单同步。', 1, 0),
  (1305, 'MENU_FINANCE_D5', '提现参数配置', '提现参数配置', 'D5', 1300, '/finance/params', 'Document', 1305, 'D5 提现参数配置,按PC左侧菜单同步。', 1, 0),
  (1400, 'MENU_DEVICES', '设备与商城', '设备与商城', 'devices', NULL, '/devices', 'Server', 1400, '设备与商城目录,按PC左侧菜单同步。', 1, 0),
  (1401, 'MENU_DEVICES_E1', '商品目录 & 上架门', '商品目录 & 上架门', 'E1', 1400, '/devices/pricing', 'Document', 1401, 'E1 商品目录 & 上架门,按PC左侧菜单同步。', 1, 0),
  (1402, 'MENU_DEVICES_E2', '收益 & 任务引擎', '收益 & 任务引擎', 'E2', 1400, '/devices/tasks', 'Document', 1402, 'E2 收益 & 任务引擎,按PC左侧菜单同步。', 1, 0),
  (1403, 'MENU_DEVICES_E3', '生命周期 & Trade-in', '生命周期 & Trade-in', 'E3', 1400, '/devices/trade-in', 'Document', 1403, 'E3 生命周期 & Trade-in,按PC左侧菜单同步。', 1, 0),
  (1404, 'MENU_DEVICES_E4', '订单状态机', '订单状态机', 'E4', 1400, '/devices/orders', 'Document', 1404, 'E4 订单状态机,按PC左侧菜单同步。', 1, 0),
  (1405, 'MENU_DEVICES_E5', '设备运维', '设备运维', 'E5', 1400, '/devices/ops', 'Document', 1405, 'E5 设备运维,按PC左侧菜单同步。', 1, 0),
  (1500, 'MENU_NETWORK', '分销与团队', '分销与团队', 'network', NULL, '/network', 'Network', 1500, '分销与团队目录,按PC左侧菜单同步。', 1, 0),
  (1501, 'MENU_NETWORK_F1', 'V-Rank 晋升', 'V-Rank 晋升', 'F1', 1500, '/network/v-rank', 'Document', 1501, 'F1 V-Rank 晋升,按PC左侧菜单同步。', 1, 0),
  (1502, 'MENU_NETWORK_F2', '网络版税费率', '网络版税费率', 'F2', 1500, '/network/royalty', 'Document', 1502, 'F2 网络版税费率,按PC左侧菜单同步。', 1, 0),
  (1503, 'MENU_NETWORK_F3', '双轨结算引擎', '双轨结算引擎', 'F3', 1500, '/network/binary', 'Document', 1503, 'F3 双轨结算引擎,按PC左侧菜单同步。', 1, 0),
  (1504, 'MENU_NETWORK_F4', '池 / 配额 / 大使 / 榜', '池 / 配额 / 大使 / 榜', 'F4', 1500, '/network/leadership-pool', 'Document', 1504, 'F4 池 / 配额 / 大使 / 榜,按PC左侧菜单同步。', 1, 0),
  (1505, 'MENU_NETWORK_F5', '佣金事件审计', '佣金事件审计', 'F5', 1500, '/network/commissions', 'Document', 1505, 'F5 佣金事件审计,按PC左侧菜单同步。', 1, 0),
  (1600, 'MENU_FINANCE_PRODUCTS', '金融产品', '金融产品', 'finance-products', NULL, '/finance-products', 'Landmark', 1600, '金融产品目录,按PC左侧菜单同步。', 1, 0),
  (1601, 'MENU_FINANCE_PRODUCTS_G1', 'Staking 池配置', 'Staking 池配置', 'G1', 1600, '/finance-products/staking', 'Document', 1601, 'G1 Staking 池配置,按PC左侧菜单同步。', 1, 0),
  (1602, 'MENU_FINANCE_PRODUCTS_G2', '兑换风控', '兑换风控', 'G2', 1600, '/finance-products/exchange', 'Document', 1602, 'G2 兑换风控,按PC左侧菜单同步。', 1, 0),
  (1603, 'MENU_FINANCE_PRODUCTS_G3', 'NEX 行情引擎', 'NEX 行情引擎', 'G3', 1600, '/finance-products/market', 'Document', 1603, 'G3 NEX 行情引擎,按PC左侧菜单同步。', 1, 0),
  (1604, 'MENU_FINANCE_PRODUCTS_G4', 'Genesis 经济', 'Genesis 经济', 'G4', 1600, '/finance-products/genesis', 'Document', 1604, 'G4 Genesis 经济,按PC左侧菜单同步。', 1, 0),
  (1605, 'MENU_FINANCE_PRODUCTS_G7', '复投激励', '复投激励', 'G7', 1600, '/finance-products/repurchase', 'Document', 1605, 'G7 复投激励,按PC左侧菜单同步。', 1, 0),
  (1700, 'MENU_GROWTH', '增长与运营节奏', '增长与运营节奏', 'growth', NULL, '/growth', 'TrendingUp', 1700, '增长与运营节奏目录,按PC左侧菜单同步。', 1, 0),
  (1701, 'MENU_GROWTH_H1', 'Phase 调度器', 'Phase 调度器', 'H1', 1700, '/growth/phase', 'Document', 1701, 'H1 Phase 调度器,按PC左侧菜单同步。', 1, 0),
  (1702, 'MENU_GROWTH_H2', '免费试用引擎', '免费试用引擎', 'H2', 1700, '/growth/trial', 'Document', 1702, 'H2 免费试用引擎,按PC左侧菜单同步。', 1, 0),
  (1703, 'MENU_GROWTH_H3', 'Quest 引擎', 'Quest 引擎', 'H3', 1700, '/growth/quest', 'Document', 1703, 'H3 Quest 引擎,按PC左侧菜单同步。', 1, 0),
  (1704, 'MENU_GROWTH_H4', '活动中心 CMS', '活动中心 CMS', 'H4', 1700, '/growth/events', 'Document', 1704, 'H4 活动中心 CMS,按PC左侧菜单同步。', 1, 0),
  (1705, 'MENU_GROWTH_H5', '签到 & NEX', '签到 & NEX', 'H5', 1700, '/growth/daily', 'Document', 1705, 'H5 签到 & NEX,按PC左侧菜单同步。', 1, 0),
  (1706, 'MENU_GROWTH_H6', '里程碑庆祝', '里程碑庆祝', 'H6', 1700, '/growth/milestones', 'Document', 1706, 'H6 里程碑庆祝,按PC左侧菜单同步。', 1, 0),
  (1800, 'MENU_CONTENT', '内容与合规 CMS', '内容与合规 CMS', 'content', NULL, '/content', 'Megaphone', 1800, '内容与合规 CMS目录,按PC左侧菜单同步。', 1, 0),
  (1801, 'MENU_CONTENT_I1', '转化文案 A/B', '转化文案 A/B', 'I1', 1800, '/content/copy-ab', 'Document', 1801, 'I1 转化文案 A/B,按PC左侧菜单同步。', 1, 0),
  (1802, 'MENU_CONTENT_I2', 'Nova 推送运营', 'Nova 推送运营', 'I2', 1800, '/content/nova', 'Document', 1802, 'I2 Nova 推送运营,按PC左侧菜单同步。', 1, 0),
  (1803, 'MENU_CONTENT_I3', '通知 Campaign', '通知 Campaign', 'I3', 1800, '/content/notifications', 'Document', 1803, 'I3 通知 Campaign,按PC左侧菜单同步。', 1, 0),
  (1804, 'MENU_CONTENT_I4', '信任中心 CMS', '信任中心 CMS', 'I4', 1800, '/content/trust', 'Document', 1804, 'I4 信任中心 CMS,按PC左侧菜单同步。', 1, 0),
  (1805, 'MENU_CONTENT_I5', '风险披露版本', '风险披露版本', 'I5', 1800, '/content/disclosures', 'Document', 1805, 'I5 风险披露版本,按PC左侧菜单同步。', 1, 0),
  (1806, 'MENU_CONTENT_I6', 'i18n 文案管理', 'i18n 文案管理', 'I6', 1800, '/content/i18n', 'Document', 1806, 'I6 i18n 文案管理,按PC左侧菜单同步。', 1, 0),
  (1807, 'MENU_CONTENT_I7', '教程中心', '教程中心', 'I7', 1800, '/content/learn', 'Document', 1807, 'I7 教程中心,按PC左侧菜单同步。', 1, 0),
  (1900, 'MENU_EMERGENCY', '紧急与合规控制', '紧急与合规控制', 'emergency', NULL, '/emergency', 'Siren', 1900, '紧急与合规控制目录,按PC左侧菜单同步。', 1, 0),
  (1901, 'MENU_EMERGENCY_J1', 'Kill-Switch 矩阵', 'Kill-Switch 矩阵', 'J1', 1900, '/emergency/kill-switch', 'Document', 1901, 'J1 Kill-Switch 矩阵,按PC左侧菜单同步。', 1, 0),
  (1902, 'MENU_EMERGENCY_J2', 'Geo-block', 'Geo-block', 'J2', 1900, '/emergency/geo-block', 'Document', 1902, 'J2 Geo-block,按PC左侧菜单同步。', 1, 0),
  (1903, 'MENU_EMERGENCY_J3', '篡改防御监控', '篡改防御监控', 'J3', 1900, '/emergency/tamper', 'Document', 1903, 'J3 篡改防御监控,按PC左侧菜单同步。', 1, 0),
  (1904, 'MENU_EMERGENCY_J4', '监管点名应急 SOP', '监管点名应急 SOP', 'J4', 1900, '/emergency/sop', 'Document', 1904, 'J4 监管点名应急 SOP,按PC左侧菜单同步。', 1, 0),
  (2000, 'MENU_RISK', '风控与反作弊', '风控与反作弊', 'risk', NULL, '/risk', 'Radar', 2000, '风控与反作弊目录,按PC左侧菜单同步。', 1, 0),
  (2001, 'MENU_RISK_K1', '反多账户引擎', '反多账户引擎', 'K1', 2000, '/risk/multi-account', 'Document', 2001, 'K1 反多账户引擎,按PC左侧菜单同步。', 1, 0),
  (2002, 'MENU_RISK_K2', '套利 & 刷量检测', '套利 & 刷量检测', 'K2', 2000, '/risk/abuse', 'Document', 2002, 'K2 套利 & 刷量检测,按PC左侧菜单同步。', 1, 0),
  (2003, 'MENU_RISK_K3', '提现风控规则引擎', '提现风控规则引擎', 'K3', 2000, '/risk/withdrawal-rules', 'Document', 2003, 'K3 提现风控规则引擎,按PC左侧菜单同步。', 1, 0),
  (2004, 'MENU_RISK_K4', '风险评分模型', '风险评分模型', 'K4', 2000, '/risk/scoring', 'Document', 2004, 'K4 风险评分模型,按PC左侧菜单同步。', 1, 0),
  (2005, 'MENU_RISK_K5', '大额 KYC 复审 & 告警', '大额 KYC 复审 & 告警', 'K5', 2000, '/risk/kyc-review', 'Document', 2005, 'K5 大额 KYC 复审 & 告警,按PC左侧菜单同步。', 1, 0),
  (2006, 'MENU_RISK_K6', 'Janus C2 控制台', 'Janus C2 控制台', 'K6', 2000, '/risk/janus-c2', 'Document', 2006, 'K6 Janus C2 控制台,按PC左侧菜单同步。', 1, 0),
  (2100, 'MENU_ANALYTICS', '数据与分析 BI', '数据与分析 BI', 'analytics', NULL, '/analytics', 'BarChart3', 2100, '数据与分析 BI目录,按PC左侧菜单同步。', 1, 0),
  (2101, 'MENU_ANALYTICS_L1', 'KPI 看板', 'KPI 看板', 'L1', 2100, '/analytics/kpi', 'Document', 2101, 'L1 KPI 看板,按PC左侧菜单同步。', 1, 0),
  (2102, 'MENU_ANALYTICS_L2', '漏斗/cohort/留存', '漏斗/cohort/留存', 'L2', 2100, '/analytics/funnel-cohort', 'Document', 2102, 'L2 漏斗/cohort/留存,按PC左侧菜单同步。', 1, 0),
  (2103, 'MENU_ANALYTICS_L3', '财务报表', '财务报表', 'L3', 2100, '/analytics/financial', 'Document', 2103, 'L3 财务报表,按PC左侧菜单同步。', 1, 0),
  (2104, 'MENU_ANALYTICS_L4', '设备/任务/网络报表', '设备/任务/网络报表', 'L4', 2100, '/analytics/operations', 'Document', 2104, 'L4 设备/任务/网络报表,按PC左侧菜单同步。', 1, 0),
  (2105, 'MENU_ANALYTICS_L5', '导出 & 监管报告', '导出 & 监管报告', 'L5', 2100, '/analytics/export', 'Document', 2105, 'L5 导出 & 监管报告,按PC左侧菜单同步。', 1, 0),
  (2200, 'MENU_SERVICE', '客服中心', '客服中心', 'service', NULL, '/service', 'Headset', 2200, '客服中心目录,按PC左侧菜单同步。', 1, 0),
  (2201, 'MENU_SERVICE_M1', '客服总览', '客服总览', 'M1', 2200, '/service/overview', 'Document', 2201, 'M1 客服总览,按PC左侧菜单同步。', 1, 0),
  (2202, 'MENU_SERVICE_M2', '工单台', '工单台', 'M2', 2200, '/service/tickets', 'Document', 2202, 'M2 工单台,按PC左侧菜单同步。', 1, 0),
  (2203, 'MENU_SERVICE_M3', '即时会话台', '即时会话台', 'M3', 2200, '/service/sessions', 'Document', 2203, 'M3 即时会话台,按PC左侧菜单同步。', 1, 0),
  (2204, 'MENU_SERVICE_M4', '知识库与 SLA', '知识库与 SLA', 'M4', 2200, '/service/kb-sla', 'Document', 2204, 'M4 知识库与 SLA,按PC左侧菜单同步。', 1, 0),
  (2205, 'MENU_SERVICE_M5', '话术与模板配置', '话术与模板配置', 'M5', 2200, '/service/scripts', 'Document', 2205, 'M5 话术与模板配置,按PC左侧菜单同步。', 1, 0)
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
INSERT INTO nx_admin_role_menu (role_id, menu_id, created_at, updated_at, is_deleted)
SELECT r.id, m.id, NOW(), NOW(), 0
FROM nx_admin_role r
JOIN nx_admin_menu m ON m.is_deleted = 0 AND m.status = 1
WHERE r.role_code = 'SUPER_ADMIN' AND r.is_deleted = 0
ON DUPLICATE KEY UPDATE is_deleted = 0, updated_at = NOW();

COMMIT;

SELECT id, menu_code, menu_name_zh, route_path, parent_id, sort_order, status
FROM nx_admin_menu
WHERE is_deleted = 0 AND status = 1 AND menu_code LIKE 'MENU_%'
ORDER BY sort_order, id;

-- A7-BE-02: migrate the legacy stock menu display names in nx_admin_menu to the
-- current page names already used by the login navigation and menu management
-- reads (AdminMenuDisplayNames). Permission codes, routes, sort order, status and
-- role bindings are untouched; operator-edited custom names are preserved because
-- only exact legacy stock labels are rewritten. Idempotent: a second startup has
-- no rows whose effective display name is still a legacy label.

UPDATE nx_admin_menu m
JOIN (
    SELECT '/platform/rbac' AS route, '运营账号 & RBAC' AS legacy_label, '运营账号与权限' AS label
    UNION ALL
    SELECT '/platform/audit' AS route, '审计 & 操作确认' AS legacy_label, '操作记录与审批' AS label
    UNION ALL
    SELECT '/platform/events' AS route, '埋点事件体系' AS legacy_label, '用户行为事件' AS label
    UNION ALL
    SELECT '/platform/params-registry' AS route, '平台参数寄存器' AS legacy_label, '平台参数目录' AS label
    UNION ALL
    SELECT '/platform/params-registry' AS route, '平台参数管理' AS legacy_label, '平台参数目录' AS label
    UNION ALL
    SELECT '/platform/permissions' AS route, '权限字典' AS legacy_label, '权限清单' AS label
    UNION ALL
    SELECT '/overview/dual-ledger' AS route, '双账本总览' AS legacy_label, '资金与应付款总览' AS label
    UNION ALL
    SELECT '/overview/liquidity' AS route, '资金池水位' AS legacy_label, '可用资金与应付款' AS label
    UNION ALL
    SELECT '/overview/funnel' AS route, '用户转化分析' AS legacy_label, '用户转化概览' AS label
    UNION ALL
    SELECT '/overview/funnel' AS route, '转化漏斗' AS legacy_label, '用户转化概览' AS label
    UNION ALL
    SELECT '/overview/rhythm' AS route, '节奏状态' AS legacy_label, '运营阶段状态' AS label
    UNION ALL
    SELECT '/overview/risk-radar' AS route, '风险雷达' AS legacy_label, '风险提醒' AS label
    UNION ALL
    SELECT '/users/search' AS route, '检索 & 画像' AS legacy_label, '查找用户与查看资料' AS label
    UNION ALL
    SELECT '/users/assets' AS route, '余额 & 资产调整' AS legacy_label, '余额与资产调整' AS label
    UNION ALL
    SELECT '/users/security' AS route, '安全 & 会话' AS legacy_label, '安全与登录设备' AS label
    UNION ALL
    SELECT '/users/reg-risk' AS route, '注册/登录风控' AS legacy_label, '注册与登录风险' AS label
    UNION ALL
    SELECT '/finance/recon' AS route, '充值对账中心' AS legacy_label, '充值与收款对账' AS label
    UNION ALL
    SELECT '/finance/withdrawals' AS route, '待审核提现' AS legacy_label, '提现审核' AS label
    UNION ALL
    SELECT '/finance/withdrawals' AS route, '提现审核队列' AS legacy_label, '提现审核' AS label
    UNION ALL
    SELECT '/finance/pool' AS route, '资金池水位仪表盘' AS legacy_label, '资金储备与应付款' AS label
    UNION ALL
    SELECT '/finance/ledger' AS route, '账本/账单审计' AS legacy_label, '账本与账单核对' AS label
    UNION ALL
    SELECT '/finance/params' AS route, '提现参数配置' AS legacy_label, 'USDT 提现参数' AS label
    UNION ALL
    SELECT '/finance/fx-rate' AS route, '汇率牌价' AS legacy_label, '汇率设置' AS label
    UNION ALL
    SELECT '/finance/payout-vnd' AS route, '法币提现参数' AS legacy_label, '越南盾提现参数' AS label
    UNION ALL
    SELECT '/devices/pricing' AS route, '商品目录 & 上架门' AS legacy_label, '商品管理与上架条件' AS label
    UNION ALL
    SELECT '/devices/tasks' AS route, '收益 & 任务引擎' AS legacy_label, '任务池' AS label
    UNION ALL
    SELECT '/devices/tasks' AS route, '设备收益与任务' AS legacy_label, '任务池' AS label
    UNION ALL
    SELECT '/devices/trade-in' AS route, '生命周期 & Trade-in' AS legacy_label, '设备使用与升级置换' AS label
    UNION ALL
    SELECT '/devices/orders' AS route, '订单状态机' AS legacy_label, '订单进度' AS label
    UNION ALL
    SELECT '/devices/ops' AS route, '设备运维' AS legacy_label, '设备管理' AS label
    UNION ALL
    SELECT '/devices/ops' AS route, '设备运行管理' AS legacy_label, '设备管理' AS label
    UNION ALL
    SELECT '/devices/compute-config' AS route, '算力与设备配置' AS legacy_label, '手机算力配置' AS label
    UNION ALL
    SELECT '/network/v-rank' AS route, 'V-Rank 晋升' AS legacy_label, '团队等级晋升' AS label
    UNION ALL
    SELECT '/network/royalty' AS route, '网络版税与分成' AS legacy_label, '团队奖励设置' AS label
    UNION ALL
    SELECT '/network/royalty' AS route, '网络版税费率' AS legacy_label, '团队奖励设置' AS label
    UNION ALL
    SELECT '/network/binary' AS route, '双轨结算引擎' AS legacy_label, '团队平衡奖励' AS label
    UNION ALL
    SELECT '/network/leadership-pool' AS route, '池 / 配额 / 大使 / 榜' AS legacy_label, '奖励池、名额与排行榜' AS label
    UNION ALL
    SELECT '/network/commissions' AS route, '佣金事件审计' AS legacy_label, '奖励记录核对' AS label
    UNION ALL
    SELECT '/finance-products/staking' AS route, 'Staking 池配置' AS legacy_label, '质押产品配置' AS label
    UNION ALL
    SELECT '/finance-products/exchange' AS route, '兑换风控' AS legacy_label, '兑换额度与规则' AS label
    UNION ALL
    SELECT '/finance-products/exchange' AS route, '兑换风险规则' AS legacy_label, '兑换额度与规则' AS label
    UNION ALL
    SELECT '/finance-products/market' AS route, 'NEX 行情引擎' AS legacy_label, 'UVT价格管理' AS label
    UNION ALL
    SELECT '/finance-products/market' AS route, 'UVT 行情引擎' AS legacy_label, 'UVT价格管理' AS label
    UNION ALL
    SELECT '/finance-products/genesis' AS route, 'Genesis 经济' AS legacy_label, '创世节点销售与收益' AS label
    UNION ALL
    SELECT '/finance-products/repurchase' AS route, '复投激励' AS legacy_label, '锁定计划' AS label
    UNION ALL
    SELECT '/growth/phase' AS route, 'Phase 调度器' AS legacy_label, '运营阶段设置' AS label
    UNION ALL
    SELECT '/growth/trial' AS route, '免费试用引擎' AS legacy_label, '免费试用设置' AS label
    UNION ALL
    SELECT '/growth/quest' AS route, 'Quest 引擎' AS legacy_label, '用户任务管理' AS label
    UNION ALL
    SELECT '/growth/quest' AS route, '任务引擎' AS legacy_label, '用户任务管理' AS label
    UNION ALL
    SELECT '/growth/events' AS route, '活动中心 CMS' AS legacy_label, '活动中心' AS label
    UNION ALL
    SELECT '/growth/daily' AS route, '签到 & NEX' AS legacy_label, '签到与UVT奖励' AS label
    UNION ALL
    SELECT '/growth/daily' AS route, '签到 & UVT' AS legacy_label, '签到与UVT奖励' AS label
    UNION ALL
    SELECT '/growth/public-stats' AS route, '历史估算配置' AS legacy_label, '首页与网络展示设置' AS label
    UNION ALL
    SELECT '/growth/public-stats' AS route, '对外公布数据' AS legacy_label, '首页与网络展示设置' AS label
    UNION ALL
    SELECT '/content/copy-ab' AS route, '转化文案 A/B' AS legacy_label, '页面文案对比测试' AS label
    UNION ALL
    SELECT '/content/nova' AS route, 'Nova 推送运营' AS legacy_label, '消息推送' AS label
    UNION ALL
    SELECT '/content/nova' AS route, 'Nova 消息推送' AS legacy_label, '消息推送' AS label
    UNION ALL
    SELECT '/content/notifications' AS route, '通知 Campaign' AS legacy_label, '通知活动' AS label
    UNION ALL
    SELECT '/content/trust' AS route, '信任中心 CMS' AS legacy_label, '信任中心' AS label
    UNION ALL
    SELECT '/content/disclosures' AS route, '风险披露' AS legacy_label, '法律条款与风险披露' AS label
    UNION ALL
    SELECT '/content/disclosures' AS route, '风险披露版本' AS legacy_label, '法律条款与风险披露' AS label
    UNION ALL
    SELECT '/content/i18n' AS route, 'i18n 文案' AS legacy_label, '多语言与课程' AS label
    UNION ALL
    SELECT '/content/i18n' AS route, 'i18n 文案与教程' AS legacy_label, '多语言与课程' AS label
    UNION ALL
    SELECT '/content/i18n' AS route, 'i18n 文案与课程' AS legacy_label, '多语言与课程' AS label
    UNION ALL
    SELECT '/content/i18n' AS route, '多语言文案' AS legacy_label, '多语言与课程' AS label
    UNION ALL
    SELECT '/emergency/kill-switch' AS route, 'Kill-Switch 矩阵' AS legacy_label, '紧急停用开关' AS label
    UNION ALL
    SELECT '/emergency/geo-block' AS route, 'Geo-block' AS legacy_label, '地区访问限制' AS label
    UNION ALL
    SELECT '/emergency/sop' AS route, '监管点名应急 SOP' AS legacy_label, '监管要求应急处理' AS label
    UNION ALL
    SELECT '/risk/multi-account' AS route, '反多账户引擎' AS legacy_label, '多账户风险排查' AS label
    UNION ALL
    SELECT '/risk/abuse' AS route, '套利 & 刷量检测' AS legacy_label, '异常套利与刷量排查' AS label
    UNION ALL
    SELECT '/risk/withdrawal-rules' AS route, '提现风控规则引擎' AS legacy_label, '提现风险规则' AS label
    UNION ALL
    SELECT '/risk/scoring' AS route, '风险评分模型' AS legacy_label, '风险评分规则' AS label
    UNION ALL
    SELECT '/analytics/kpi' AS route, 'KPI 看板' AS legacy_label, '关键指标' AS label
    UNION ALL
    SELECT '/analytics/funnel-cohort' AS route, '漏斗/cohort/留存' AS legacy_label, '用户转化与留存' AS label
    UNION ALL
    SELECT '/analytics/operations' AS route, '设备/任务/网络报表' AS legacy_label, '设备、任务与团队报表' AS label
    UNION ALL
    SELECT '/analytics/export' AS route, '导出 & 监管报告' AS legacy_label, '数据导出与监管报告' AS label
    UNION ALL
    SELECT '/service/overview' AS route, '客服总览' AS legacy_label, '客服工作台' AS label
    UNION ALL
    SELECT '/service/sessions' AS route, '即时会话台' AS legacy_label, '专属会话' AS label
    UNION ALL
    SELECT '/service/kb-sla' AS route, '知识库与 SLA' AS legacy_label, '知识库' AS label
    UNION ALL
    SELECT '/service/scripts' AS route, '话术与模板配置' AS legacy_label, '服务规则与话术' AS label
) names ON names.route = m.route_path
SET m.menu_name = names.label, m.menu_name_zh = names.label, m.updated_at = NOW()
WHERE m.is_deleted = 0
  AND TRIM(COALESCE(NULLIF(m.menu_name_zh, ''), NULLIF(m.menu_name, ''))) = names.legacy_label;

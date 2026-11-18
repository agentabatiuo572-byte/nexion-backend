package ffdd.opsconsole.platform.domain;

import java.util.Map;
import java.util.Set;

/** Legacy stock labels only. Explicit administrator names keep priority. */
public final class AdminMenuDisplayNames {
    private AdminMenuDisplayNames() {}
    private record Name(String current, Set<String> legacy) {}

    private static final Map<String, Name> BY_ROUTE = Map.ofEntries(
            Map.entry("/platform/rbac", new Name("运营账号与权限", Set.of("运营账号 & RBAC"))),
            Map.entry("/platform/audit", new Name("操作记录与审批", Set.of("审计 & 操作确认"))),
            Map.entry("/platform/events", new Name("用户行为事件", Set.of("埋点事件体系"))),
            Map.entry("/platform/params-registry", new Name("平台参数目录", Set.of("平台参数寄存器", "平台参数管理"))),
            Map.entry("/platform/permissions", new Name("权限清单", Set.of("权限字典"))),
            Map.entry("/overview/dual-ledger", new Name("资金与应付款总览", Set.of("双账本总览"))),
            Map.entry("/overview/liquidity", new Name("可用资金与应付款", Set.of("资金池水位"))),
            Map.entry("/overview/funnel", new Name("用户转化概览", Set.of("用户转化分析", "转化漏斗"))),
            Map.entry("/overview/rhythm", new Name("运营阶段状态", Set.of("节奏状态"))),
            Map.entry("/overview/risk-radar", new Name("风险提醒", Set.of("风险雷达"))),
            Map.entry("/users/search", new Name("查找用户与查看资料", Set.of("检索 & 画像"))),
            Map.entry("/users/assets", new Name("余额与资产调整", Set.of("余额 & 资产调整"))),
            Map.entry("/users/security", new Name("安全与登录设备", Set.of("安全 & 会话"))),
            Map.entry("/users/reg-risk", new Name("注册与登录风险", Set.of("注册/登录风控"))),
            Map.entry("/finance/recon", new Name("充值与收款对账", Set.of("充值对账中心"))),
            Map.entry("/finance/withdrawals", new Name("提现审核", Set.of("待审核提现", "提现审核队列"))),
            Map.entry("/finance/pool", new Name("资金储备与应付款", Set.of("资金池水位仪表盘"))),
            Map.entry("/finance/ledger", new Name("账本与账单核对", Set.of("账本/账单审计"))),
            Map.entry("/finance/params", new Name("USDT 提现参数", Set.of("提现参数配置"))),
            Map.entry("/finance/fx-rate", new Name("汇率设置", Set.of("汇率牌价"))),
            Map.entry("/finance/payout-vnd", new Name("越南盾提现参数", Set.of("法币提现参数"))),
            Map.entry("/devices/pricing", new Name("商品管理与上架条件", Set.of("商品目录 & 上架门"))),
            Map.entry("/devices/tasks", new Name("任务池", Set.of("收益 & 任务引擎", "设备收益与任务"))),
            Map.entry("/devices/trade-in", new Name("设备使用与升级置换", Set.of("生命周期 & Trade-in"))),
            Map.entry("/devices/orders", new Name("订单进度", Set.of("订单状态机"))),
            Map.entry("/devices/ops", new Name("设备管理", Set.of("设备运维", "设备运行管理"))),
            Map.entry("/devices/compute-config", new Name("手机算力配置", Set.of("算力与设备配置"))),
            Map.entry("/network/v-rank", new Name("团队等级晋升", Set.of("V-Rank 晋升"))),
            Map.entry("/network/royalty", new Name("团队奖励设置", Set.of("网络版税与分成", "网络版税费率"))),
            Map.entry("/network/binary", new Name("团队平衡奖励", Set.of("双轨结算引擎"))),
            Map.entry("/network/leadership-pool", new Name("奖励池、名额与排行榜", Set.of("池 / 配额 / 大使 / 榜"))),
            Map.entry("/network/commissions", new Name("奖励记录核对", Set.of("佣金事件审计"))),
            Map.entry("/finance-products/staking", new Name("质押产品配置", Set.of("Staking 池配置"))),
            Map.entry("/finance-products/exchange", new Name("兑换额度与规则", Set.of("兑换风控", "兑换风险规则"))),
            Map.entry("/finance-products/market", new Name("UVT价格管理", Set.of("NEX 行情引擎", "UVT 行情引擎"))),
            Map.entry("/finance-products/genesis", new Name("创世节点销售与收益", Set.of("Genesis 经济"))),
            Map.entry("/finance-products/repurchase", new Name("锁定计划", Set.of("复投激励"))),
            Map.entry("/growth/phase", new Name("运营阶段设置", Set.of("Phase 调度器"))),
            Map.entry("/growth/trial", new Name("免费试用设置", Set.of("免费试用引擎"))),
            Map.entry("/growth/quest", new Name("用户任务管理", Set.of("Quest 引擎", "任务引擎"))),
            Map.entry("/growth/events", new Name("活动中心", Set.of("活动中心 CMS"))),
            Map.entry("/growth/daily", new Name("签到与UVT奖励", Set.of("签到 & NEX", "签到 & UVT"))),
            Map.entry("/growth/public-stats", new Name("首页与网络展示设置", Set.of("历史估算配置", "对外公布数据"))),
            Map.entry("/content/copy-ab", new Name("页面文案对比测试", Set.of("转化文案 A/B"))),
            Map.entry("/content/nova", new Name("消息推送", Set.of("Nova 推送运营", "Nova 消息推送"))),
            Map.entry("/content/notifications", new Name("通知活动", Set.of("通知 Campaign"))),
            Map.entry("/content/trust", new Name("信任中心", Set.of("信任中心 CMS"))),
            Map.entry("/content/disclosures", new Name("法律条款与风险披露", Set.of("风险披露", "风险披露版本"))),
            Map.entry("/content/i18n", new Name("多语言与课程", Set.of("i18n 文案", "i18n 文案与教程", "i18n 文案与课程", "多语言文案"))),
            Map.entry("/emergency/kill-switch", new Name("紧急停用开关", Set.of("Kill-Switch 矩阵"))),
            Map.entry("/emergency/geo-block", new Name("地区访问限制", Set.of("Geo-block"))),
            Map.entry("/emergency/sop", new Name("监管要求应急处理", Set.of("监管点名应急 SOP"))),
            Map.entry("/risk/multi-account", new Name("多账户风险排查", Set.of("反多账户引擎"))),
            Map.entry("/risk/abuse", new Name("异常套利与刷量排查", Set.of("套利 & 刷量检测"))),
            Map.entry("/risk/withdrawal-rules", new Name("提现风险规则", Set.of("提现风控规则引擎"))),
            Map.entry("/risk/scoring", new Name("风险评分规则", Set.of("风险评分模型"))),
            Map.entry("/analytics/kpi", new Name("关键指标", Set.of("KPI 看板"))),
            Map.entry("/analytics/funnel-cohort", new Name("用户转化与留存", Set.of("漏斗/cohort/留存"))),
            Map.entry("/analytics/operations", new Name("设备、任务与团队报表", Set.of("设备/任务/网络报表"))),
            Map.entry("/analytics/export", new Name("数据导出与监管报告", Set.of("导出 & 监管报告"))),
            Map.entry("/service/overview", new Name("客服工作台", Set.of("客服总览"))),
            Map.entry("/service/sessions", new Name("专属会话", Set.of("即时会话台"))),
            Map.entry("/service/kb-sla", new Name("知识库", Set.of("知识库与 SLA"))),
            Map.entry("/service/scripts", new Name("服务规则与话术", Set.of("话术与模板配置"))));

    public static String resolveUniqueStockLabel(String stored) {
        if (stored == null) return null;
        String label = stored.trim();
        var names = BY_ROUTE.values().stream().filter(name -> name.legacy().contains(label))
                .map(Name::current).distinct().limit(2).toList();
        return names.size() == 1 ? names.get(0) : stored;
    }

    public static String resolve(String route, String stored) {
        if (route == null || stored == null) return stored;
        Name label = BY_ROUTE.get(route);
        return label != null && label.legacy().contains(stored.trim()) ? label.current() : stored;
    }
}

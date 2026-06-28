package ffdd.opsconsole.bi.infrastructure;

import ffdd.opsconsole.bi.mapper.BiReportMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BiDashboardSeeds {
    private BiDashboardSeeds() {
    }

    static Map<String, Object> dashboard(String moduleCode) {
        return switch (moduleCode) {
            case "L1" -> l1();
            case "L2" -> l2();
            case "L3" -> l3();
            case "L4" -> l4();
            case "L5" -> l5();
            case "L6" -> l6();
            default -> linked();
        };
    }

    static List<BiReportMapper.ReportSeed> reports() {
        return List.of(
                new BiReportMapper.ReportSeed("L5", "EXP-2214", "账单 CSV(8 类含 bonus/adjustment)", "BILL_CSV", "2026-05", "CSV",
                        "2026-05 · 全量用户", "金额/type/ref + 手机号(hash)", 482031L, true, "MASKED", "PENDING_CONFIRM", "财务 jchen → 超管待批"),
                new BiReportMapper.ReportSeed("L5", "EXP-2213", "团队树明细", "NETWORK_TREE", "ON_DEMAND", "CSV",
                        "全网 · 用户编码维度(含直推/团队边)", "用户编码/直推边/团队规模", 1283400L, true, "PARTIAL", "PENDING_SPLIT_CONFIRM", "增长 mliu → 超管待批(超限拆分)"),
                new BiReportMapper.ReportSeed("L5", "EXP-2212", "漏斗序列(聚合)", "FUNNEL_COHORT", "W17-W22", "CSV",
                        "W17-W22 · 5 级 CVR", "去重计数/CVR/留存率", 1240L, false, "NONE", "GENERATING", "仍需操作确认(聚合)"),
                new BiReportMapper.ReportSeed("L5", "EXP-2211", "财务报表(聚合汇总)", "FINANCE_AGG", "2026-05", "CSV",
                        "2026-05 · 四报表", "聚合金额/比率", 318L, false, "NONE", "READY", "仍需操作确认(聚合)"),
                new BiReportMapper.ReportSeed("L5", "EXP-2209", "KPI 序列", "KPI_SERIES", "W16-W22", "CSV",
                        "8 KPI × 12 周", "值/目标/环比", 96L, false, "NONE", "EXPIRED", "仍需操作确认(聚合)"),
                new BiReportMapper.ReportSeed("L5", "EXP-2208", "监管报告 · BR 专项", "REGULATORY", "BR", "PDF",
                        "BR 辖区 · 披露 v3.2-BR", "合规台账(脱敏)", 12407L, true, "MASKED", "FAILED", "风控 rkim → 超管已批"));
    }

    private static Map<String, Object> l1() {
        return linked(
                "weeks", List.of("W16", "W17", "W18", "W19", "W20", "W21", "W22"),
                "phaseSwitchIndex", 3,
                "kpiColors", List.of("#9EDC1D", "#FF6B35", "#B6A4FF", "#FFBE3D", "#29D27F", "#6FB7FF", "#DD6F5C", "#E0C36A"),
                "kpis", kpis(),
                "kpiPlain", linked(
                        "1", "注册后 90 秒内收到首笔收益的人 ÷ 新注册人数",
                        "2", "注册满 7 天还在打开 app 的人 ÷ 同批注册人数",
                        "3", "逛过商城的人 ÷ 新注册人数",
                        "4", "完成付款的人 ÷ 逛过商城的人",
                        "5", "发出过邀请的设备持有者 ÷ 设备持有者总数",
                        "6", "点开推送的次数 ÷ 推送发送次数",
                        "7", "直推用户中首单触发了佣金的 ÷ 直推总数",
                        "8", "1,000 台创世节点全部售出用了几天"),
                "kpiExt", linked(
                        "1", kpiExt("device.first_yield_received.latency_sec ≤ 90 ÷ auth.register_completed", List.of("device.first_yield_received", "auth.register_completed"), "27,491", "28,518", "+0.3", "注册后 90 秒内收到首笔算力收益的比例。低于线通常是开机引导或设备分配排队问题。", List.of(jump("跳 L2 漏斗", "/analytics/funnel-cohort"), jump("跳 L4 设备报表", "/analytics/operations"))),
                        "2", kpiExt("day7 有 app.dau ÷ register cohort", List.of("app.dau"), "15,224", "26,158", "-0.6", "最弱预警项。W20 起连续 3 周低于 60% 目标,指向扩张期拉新质量和推送节奏。", List.of(jump("跳 L2 留存矩阵", "/analytics/funnel-cohort"), jump("归因 B4 节奏", null))),
                        "3", kpiExt("store.viewed 去重用户 ÷ 注册数", List.of("store.viewed"), "9,725", "28,518", "+0.9", "浏览类行为由客户端上报,按服务端去重聚合。", List.of(jump("跳 L2 漏斗", "/analytics/funnel-cohort"))),
                        "4", kpiExt("checkout.completed ÷ store.viewed", List.of("checkout.completed", "store.viewed"), "661", "9,725", "+0.2", "健康带 5-10%。trial 子漏斗为补充维度,不并入分子分母。", List.of(jump("跳 L2 漏斗", "/analytics/funnel-cohort"), jump("I1 文案实验", null))),
                        "5", kpiExt("设备持有者 referral.invite_sent ÷ 设备持有者数", List.of("referral.invite_sent"), "17,103", "41,208", "+0.3", "严格为推广率,不是收入口径。", List.of(jump("跳 L4 网络报表", "/analytics/operations"))),
                        "6", kpiExt("nova.push_clicked ÷ nova.push_sent", List.of("nova.push_clicked", "nova.push_sent"), "212,448", "778,200", "+0.3", "推送事件按服务端触达和点击日志聚合,节奏配置在 I2。", List.of(jump("I2 推送节奏", null))),
                        "7", kpiExt("L1 被推荐人首单 commission.paid ÷ 直推数", List.of("commission.paid"), "6,213", "8,175", "+1.0", "依赖 F 域分销关系树,完整下钻在 L4 网络/团队结构报表。", List.of(jump("跳 L4 团队下钻", "/analytics/operations"), jump("F5 佣金审计", null))),
                        "8", kpiExt("genesis.purchased 累计达 1,000 张的天数", List.of("genesis.purchased"), "1,000 张", "11 天", "-1", "越小越好。财务侧下钻在 L3。", List.of(jump("跳 L3 财务报表", "/analytics/financial")))));
    }

    private static List<Map<String, Object>> kpis() {
        return List.of(
                kpi(1, "Day 0 自动接入率", 96.4, 95, "%", "gte", null, "注册→90s 内首笔 receipt", "V1", List.of(94, 95, 93, 96, 95, 96, 96.4)),
                kpi(2, "Day 7 留存", 58.2, 60, "%", "gte", null, "7 天后仍开过 app", "V1", List.of(61, 60, 59, 58, 57, 59, 58.2)),
                kpi(3, "L2→L3 转化(进 store)", 34.1, 30, "%", "gte", null, "主动浏览商城", "V1", List.of(30, 31, 33, 32, 34, 33, 34.1)),
                kpi(4, "L3→L4 转化(下单)", 6.8, 7.5, "%", "band", List.of(5, 10), "完成支付", "V1", List.of(5.2, 5.8, 6.1, 6.5, 7.0, 6.6, 6.8)),
                kpi(5, "L4→L5 转化(推广)", 41.5, 40, "%", "gte", null, "设备持有者推荐 ≥1 人", "V4", List.of(38, 39, 40, 41, 42, 41, 41.5)),
                kpi(6, "Nova push CTR", 27.3, 25, "%", "gte", null, "每条 CTA tap 率", "V4", List.of(24, 25, 26, 27, 28, 27, 27.3)),
                kpi(7, "团队佣金触发率", 76.0, 80, "%", "gte", null, "L1 直推被推荐人首单", "V2", List.of(72, 73, 74, 75, 76, 75, 76)),
                kpi(8, "Genesis 售罄速度", 11, 14, "天", "lte", null, "售罄 1,000 张", "V2", List.of(16, 15, 14, 13, 12, 12, 11)));
    }

    private static Map<String, Object> l2() {
        return linked(
                "funnel", List.of(
                        funnel("注册", "auth.register_completed", 128400, null, "L1", "#9EDC1D", null),
                        funnel("绑卡 $1 KYC", "kyc.express_verified", 97300, 75.8, "L2", "#B6E84A", null),
                        funnel("首购", "checkout.completed", 33180, 34.1, "L3→L4", "#9B89E0", ">30%"),
                        funnel("复投", "checkout.completed ×2", 8920, 26.9, "L5", "#B6A4FF", null),
                        funnel("提现", "withdraw.submitted", 21640, 65.2, "L5", "#29D27F", null)),
                "stageEvents", List.of("auth.register_completed", "kyc.express_verified", "checkout.completed", "checkout.completed ×2", "withdraw.submitted"),
                "funnelExt", List.of(
                        funnelExt("完成注册", "—(漏斗顶)", "未完成绑卡 31,100 人(24.2%)", List.of(18, 26, 34, 22, 12, 6, 4, 2), "注册后 48h 内未打开 store 是主要流失。", null, false, false),
                        funnelExt("花 $1 完成快速实名", "上级流入 128,400", "卡验证失败 8,210 · 中途放弃 21,870 · 高风险 BIN 拦截 1,020", List.of(8, 22, 30, 24, 14, 8, 5, 3), "$1 预授权失败重试成功率 64%。", null, false, false),
                        funnelExt("完成第一笔设备订单", "上级流入 97,300", "进 store 未下单 64,120(其中加购未支付 7,840)", List.of(4, 9, 16, 22, 24, 14, 8, 3), "本级下方叠加 trial 子漏斗。", ">30%", true, false),
                        funnelExt("再次下单(暂用二次下单口径)", "上级流入 33,180", "单设备持有未复投 24,260", List.of(2, 5, 9, 14, 20, 24, 16, 10), "V1 先拿第二次下单当复投。", null, false, true),
                        funnelExt("发起提现申请", "设备持有者中发起提现", "未提现持有者 11,540", List.of(6, 12, 18, 22, 18, 12, 8, 4), "提现兑付健康在 L3 兑付报表。", null, false, false)),
                "trialSteps", List.of(
                        linked("e", "trial.claim_sheet_shown", "n", 18420),
                        linked("e", "trial.started", "n", 11273, "arr", "61.2%", "arrLb", "领取率"),
                        linked("e", "trial.redeemed", "n", 2525, "arr", "22.4%", "arrLb", "trial→购买")),
                "cohorts", List.of(
                        cohort("2026-W17", 21080, 74, 62, 41),
                        cohort("2026-W18", 22104, 75, 61, 40),
                        cohort("2026-W19", 24880, 74, 59, 38),
                        cohort("2026-W20", 26420, 75, 58, 36),
                        cohort("2026-W21", 26158, 74, 58, null),
                        cohort("2026-W22", 28940, 73, null, null)),
                "curves", linked(
                        "W21", curve(List.of(0, 1, 2, 3, 5, 7, 10, 14, 21, 30), List.of(100, 74, 69, 65, 61, 58, 52, 47, 42, 38)),
                        "W18", curve(List.of(0, 1, 2, 3, 5, 7, 10, 14, 21, 30), List.of(100, 75, 71, 68, 65, 61, 56, 51, 45, 40)),
                        "W20", curve(List.of(0, 1, 2, 3, 5, 7, 10, 14, 21, 30), List.of(100, 75, 70, 66, 61, 58, 53, 48, 42, 36))),
                "crossAnalysis", crossAnalysis(),
                "day7Kpi", kpis().get(1));
    }

    private static Map<String, Object> l3() {
        Map<String, Object> revenue = linked("gmv", 4_280_000, "commission", 1_140_000, "token", 980_000, "marketFee", 312_000);
        Map<String, Object> maturity7d = linked("withdraw", 348_000, "interest", 78_000, "genesis", 142_900);
        Map<String, Object> maturity30d = linked("withdraw", 1_272_960, "interest", 308_100, "genesis", 613_445);
        return linked(
                "ledger", linked("reserveUsd", 6_340_000, "liabilitiesUsd", 5_370_000, "coverageRatio", 118.1, "redlinePct", 100, "healthyPct", 110, "coverageSeries", List.of(113.0, 114.0, 115.0, 116.0, 116.5, 117.0, 117.5, 118.1)),
                "treasury", linked("reserveTotal", 6_340_000, "liabilityTotal", 5_370_000, "coverageRatio", 118.1, "redLine", 100, "yellowLine", 110, "light", "green", "deltaPct", 5.1, "netExposure", 970_000),
                "liabilities", liabilities(),
                "revenue", revenue,
                "revenueExt", List.of(
                        rev("设备销售 GMV", "checkout.completed", 4_280_000, "+6.8%", true, "#9EDC1D"),
                        rev("团队分润佣金", "commission.paid(按计提额)", 1_140_000, "+11.4%", true, "#B6A4FF"),
                        rev("代币经济", "exchange.swapped", 980_000, "+9.2%", true, "#6FB7FF"),
                        rev("算力撮合服务费", "service_fee.accrued", 312_000, "-2.1%", false, "#FFBE3D")),
                "redemption", linked("submitted", 12840, "confirmed", 12416, "avgLatency", "7.4h", "rejected", 182, "delayed", 198, "frozen", 44, "prevRate", 96.3, "prevLabel", "2026-04"),
                "coverage12w", List.of(121, 119, 109.4, 113, 113.0, 114.0, 115.0, 116.0, 116.5, 117.0, 117.5, 118.1),
                "coverageWeeks", List.of("3/02", "3/09", "3/16", "3/23", "3/30", "4/06", "4/13", "4/20", "4/27", "5/04", "5/11", "5/18"),
                "coverageBreaches", List.of(
                        linked("i", 2, "type", "cov", "label", "3/16 覆盖率破黄线"),
                        linked("i", 5, "type", "run", "label", "4/08 挤兑比率破线")),
                "maturity7d", maturity7d,
                "maturity30d", maturity30d,
                "maturityWindow", linked("7d", maturity7d, "30d", maturity30d),
                "maturitySchedule", linked(
                        "weeks", List.of("本周", "+1 周", "+2 周", "+3 周"),
                        "data", List.of(List.of(348_000, 78_000, 142_900), List.of(313_200, 74_100, 150_045), List.of(334_080, 79_560, 157_190), List.of(292_320, 76_440, 164_210))),
                "reserveCoverDays", 77);
    }

    private static Map<String, Object> l4() {
        return linked(
                "deviceTotal", 41208,
                "deviceTiles", linked("locked", 28406, "retired", 2114, "dailyUsd", "$141.0K", "dailyNex", "38,420 NEX"),
                "deviceDistribution", List.of(
                        dist("NexionBox S1", 18204, "#B6A4FF", "legacy"),
                        dist("NexionBox Pro", 9412, "#8A95A8", "legacy"),
                        dist("NexionBox Pro v2", 8108, "#9EDC1D", "current"),
                        dist("NexionRack P1", 3254, "#B6E84A", "legacy"),
                        dist("NexionRack P2", 2230, "#29D27F", "current")),
                "decaySegments", List.of(
                        linked("m", "月 1-6 段", "r", "-4%/月", "tone", "var(--success)", "imp", "影响产出 -$3.2K/日"),
                        linked("m", "月 7-12 段", "r", "-6%/月", "tone", "var(--warning)", "imp", "影响产出 -$5.8K/日"),
                        linked("m", "月 13+ 段", "r", "-10%/月", "tone", "var(--danger)", "imp", "影响产出 -$2.1K/日"),
                        linked("m", "MIN_EFFICIENCY", "r", "地板生效 412 台", "tone", "var(--ink-2)", "imp", "已触底设备占 1.0%")),
                "taskTiles", linked("done", "1.84M", "dispatched", "3.18M", "doneN", 1_838_000, "dispatchedN", 3_180_000, "saturation", "63%", "checkin", "61,420", "tierAvg", "$0.042"),
                "taskTiers", List.of(
                        taskTier("tier-1 · 轻量验证", 710000, 94, "#9EDC1D"),
                        taskTier("tier-2 · 数据标注", 486000, 88, "#B6E84A"),
                        taskTier("tier-3 · 渲染切片", 318000, 71, "#29D27F"),
                        taskTier("tier-4 · 模型微调", 188000, 62, "#FFBE3D"),
                        taskTier("tier-5 · 批量推理", 96000, 41, "#FF6B35"),
                        taskTier("tier-6 · 专项算力", 40000, 33, "#DD6F5C")),
                "vrHistory", List.of(9120, 6820, 4410, 2980, 2010, 1340, 860, 520, 300, 170, 90, 40, 12),
                "referralDistribution", List.of(segment("直推 0(纯持有)", 24105, "#8A95A8"), segment("直推 1-3", 10240, "#B6A4FF"), segment("直推 4-10", 4820, "#9EDC1D"), segment("直推 11-50", 1742, "#FFBE3D"), segment("直推 50+", 301, "#FF6B35")),
                "commissionDistribution", List.of(pctSegment("network 网络版税", 42, "#9EDC1D"), pctSegment("binary 平衡匹配", 22, "#B6A4FF"), pctSegment("peer 平级奖", 14, "#6FB7FF"), pctSegment("cultivation 育成", 10, "#FFBE3D"), pctSegment("leadership 领导池", 8, "#29D27F"), pctSegment("genesis 版税", 4, "#DD6F5C")),
                "teamGmv", "$2.84M / 周 · 占总 GMV 43%",
                "promoKpi", kpis().get(4),
                "commissionKpi", kpis().get(6),
                "phases", phases(),
                "currentPhase", linked("code", "P3", "name", "扩张", "month", 7),
                "phaseRows", List.of(
                        phaseRow("Day7 留存", List.of("64.1%", "61.0%", "58.2%", "—", "—", "—"), List.of("", "-3.1pt", "-2.8pt", "", "", "")),
                        phaseRow("首购 CVR(L3→L4)", List.of("5.4%", "6.2%", "6.8%", "—", "—", "—"), List.of("", "+0.8pt", "+0.6pt", "", "", "")),
                        phaseRow("设备日产 / 台", List.of("$3.61", "$3.52", "$3.42", "—", "—", "—"), List.of("", "-2.5%", "-2.8%", "", "", "")),
                        phaseRow("任务承接率", List.of("71%", "64%", "57.8%", "—", "—", "—"), List.of("", "-7pt", "-6.2pt", "", "", "")),
                        phaseRow("提现量(周)", List.of("$1.9M", "$2.6M", "$2.1M", "—", "—", "—"), List.of("", "+37%", "-19%", "", "", "")),
                        phaseRow("佣金触发率(#7)", List.of("68%", "73%", "76%", "—", "—", "—"), List.of("", "+5pt", "+3pt", "", "", ""))));
    }

    private static Map<String, Object> l5() {
        return linked(
                "stats", linked("monthTotal", 47, "aggCount", 38, "sensitiveCount", 9, "decryptedQ", 1, "regulatoryQ", 3),
                "statusLabels", linked(
                        "PENDING_CONFIRM", List.of("待确认", "warn"),
                        "PENDING_SPLIT_CONFIRM", List.of("超限待超管批", "warn"),
                        "GENERATING", List.of("生成中", "cyan"),
                        "READY", List.of("可下载 · TTL 24h", "ok"),
                        "EXPIRED", List.of("链接已过期", "dim"),
                        "FAILED", List.of("失败 · 可重试", "bad"),
                        "REJECTED", List.of("已驳回", "bad")),
                "regulatoryTemplates", List.of(
                        template("kyc", "KYC 合规报告", "季度 · 辖区按需", "KYC 通过率 / 复审队列 / 大额复审台账", "上次:2026-Q1 · 已报送", "kyc"),
                        template("redemption", "资金兑付报告", "月度", "兑付率 / 覆盖率走势 / 净敞口 / 负债到期", "上次:2026-05 · 生成中", "fund"),
                        template("aml", "反洗钱 AML 报告", "季度 / 专项", "大额异动 / 去重簇 / 套利信号 / 冻结处置台账", "上次:2026-Q1 · 已报送", "shield"),
                        template("jurisdiction", "司法辖区专项", "专项 · 点名响应", "按辖区定制 · 引用当前披露版本与辖区", "上次:BR 专项 · 5/12", "geo")),
                "j4Trace", List.of(
                        linked("tone", "var(--danger)", "txt", List.of("BR 监管点名剧本执行", " · geo-block BR + 兑换/Genesis 熔断 + 披露 v3.2-BR 发布"), "ts", "5/12 14:02"),
                        linked("tone", "var(--warning)", "txt", List.of("挤兑比率破线自动熔断", " · 24h 提现申请 ÷ 储备破 B5 红线命中 R1"), "ts", "4/08 03:41"),
                        linked("tone", "var(--success)", "txt", List.of("恢复演练(季度)", " · 全剧本 dry-run · 平均步时延 4.2 分钟"), "ts", "4/29 10:00")),
                "maskRules", List.of(
                        mask("手机号", "PII", "bad", "masked", "hash", "是", "操作确认 + 强制事由"),
                        mask("卡 token", "PII", "bad", "partial", "保留后 4 位", "是", "操作确认 + 强制事由"),
                        mask("地址", "PII", "bad", "partial", "截断至行政区", "是", "操作确认 + 强制事由"),
                        mask("用户编码", "监管", "dim", "partial", "保留关联键", "—", "非明文 PII"),
                        mask("账单金额 / type", "资金", "warn", "", "保留 · 批量导出走操作确认", "—", "含 PII 同表时随表脱敏"),
                        mask("账单 ref", "资金", "warn", "partial", "业务关联键", "—", "—")),
                "exportParams", List.of(
                        param("含敏感数据导出操作确认", "强制开启", true, "强制开启", "含 PII / 资金明细 / 监管报表的批量导出,一律操作确认"),
                        param("默认脱敏策略", "默认脱敏", false, "默认脱敏(解密须强操作确认)", "手机号 hash / 卡 token 掩码后 4 位 / 地址截断"),
                        param("下载链接 TTL", "24 小时", false, "24h(范围 1-72h)", "server 签发限时链接,过期重新发起"),
                        param("账单导出范围", "8 类 BillType", false, "8 类全选(可按 type 勾选)", "覆盖 swap/topup/withdraw/earning/commission/refund/bonus/adjustment"),
                        param("单任务行数上限", "100 万行", false, "100 万行(范围 10 万-500 万)", "超限走拆分确认"),
                        param("监管报告周期", "按辖区要求", false, "月 / 季 / 年 / 专项,按需", "与披露版本和司法辖区联动")),
                "auditRows", List.of(
                        auditRow("5/29 16:40", "finance jchen", "财务明细 · 2026-05", "48,210", true, "masked", "jchen / super_w ✓", "已下载 1 次"),
                        auditRow("5/28 11:02", "growth mliu", "运营聚合 · W21", "812", false, "—", "仍需操作确认(聚合)", "已下载"),
                        auditRow("5/26 09:15", "auditor zfan", "用户名单导出(来自用户域)· KYC pending", "1,043", true, "partial", "zfan / super_w ✓", "已下载"),
                        auditRow("5/22 14:30", "risk rkim", "监管报告 · BR 专项 · 披露 v3.2-BR", "12,407", true, "masked", "rkim / risklead_h ✓", "已报送"),
                        auditRow("5/20 10:08", "growth mliu", "KPI 序列 · 8×12 周", "96", false, "—", "仍需操作确认(聚合)", "链接过期"),
                        auditRow("5/12 15:11", "auditor zfan", "解密导出 · 司法调证(事由:法院令 BR-0512)", "38", true, "decrypted", "zfan / super_w ✓", "已下载 · 高亮")),
                "scheduleOptions", List.of("每月 5 日", "每月 1 日", "每月 10 日", "每月 15 日", "每月最后一日", "每周一", "每季度首月 5 日"),
                "scheduleDefault", "每月 5 日");
    }

    private static Map<String, Object> l6() {
        List<PageSeed> seeds = l6PageSeeds();
        List<Map<String, Object>> pageTree = seeds.stream()
                .map(seed -> linked(
                        "route", seed.route(),
                        "titleZh", seed.titleZh(),
                        "level", seed.level(),
                        "parentL1", seed.parentL1(),
                        "parentL2", seed.parentL2(),
                        "tracked", seed.tracked()))
                .toList();
        List<Map<String, Object>> excludedPages = seeds.stream()
                .filter(seed -> !seed.tracked())
                .map(seed -> linked(
                        "route", seed.route(),
                        "titleZh", seed.titleZh(),
                        "level", seed.level(),
                        "parentL1", seed.parentL1(),
                        "parentL2", seed.parentL2(),
                        "tracked", false))
                .toList();
        Map<String, Object> clickHeatByRoute = new LinkedHashMap<>();
        seeds.stream()
                .filter(PageSeed::tracked)
                .forEach(seed -> clickHeatByRoute.put(seed.route(), buildPageClickHeat(seed, seeds)));
        return linked(
                "totalPages", seeds.size(),
                "trackedCount", seeds.stream().filter(PageSeed::tracked).count(),
                "pageTree", pageTree,
                "excludedPages", excludedPages,
                "windows", List.of("24h", "7d", "30d"),
                "activityByWindow", linked(
                        "24h", buildPageActivity("24h", seeds),
                        "7d", buildPageActivity("7d", seeds),
                        "30d", buildPageActivity("30d", seeds)),
                "clickHeatByRoute", clickHeatByRoute);
    }

    private static List<PageSeed> l6PageSeeds() {
        String rIndex = "pages/index/index";
        String rEarn = "pages/earn/earn";
        String rStore = "pages/store/store";
        String rTeam = "pages/team/team";
        String rMe = "pages/me/me";
        return List.of(
                p(rIndex, "首页", 1, rIndex, rIndex),
                p(rEarn, "赚取", 1, rEarn, rEarn),
                p(rStore, "商城", 1, rStore, rStore),
                p(rTeam, "团队", 1, rTeam, rTeam),
                p(rMe, "我的", 1, rMe, rMe),
                p("pages/market/market", "行情", 1, "pages/market/market", "pages/market/market"),
                p("pages/missions/missions", "任务中心", 1, "pages/missions/missions", "pages/missions/missions"),
                p("pages/events/events", "活动", 1, "pages/events/events", "pages/events/events"),
                p("pages/learn/learn", "学习", 1, "pages/learn/learn", "pages/learn/learn"),
                p("pages/genesis/genesis", "创世节点", 1, "pages/genesis/genesis", "pages/genesis/genesis"),
                p("pages/staking/staking", "质押", 1, "pages/staking/staking", "pages/staking/staking"),
                p("pages/daily/daily", "每日签到", 1, "pages/daily/daily", "pages/daily/daily"),
                p("pages/globe/globe", "全球网络", 1, "pages/globe/globe", "pages/globe/globe"),
                p("pages/developer/developer", "开发者", 1, "pages/developer/developer", "pages/developer/developer"),
                p("pages/trust/trust", "信任中心", 1, "pages/trust/trust", "pages/trust/trust"),
                p("pages/search/search", "搜索", 1, "pages/search/search", "pages/search/search"),
                p("pages/store/detail", "商品详情", 2, rStore, "pages/store/detail"),
                p("pages/store/checkout", "结算", 2, rStore, "pages/store/checkout"),
                p("pages/store/orders", "我的订单", 2, rStore, "pages/store/orders"),
                p("pages/store/order-detail", "订单详情", 3, rStore, "pages/store/orders"),
                p("pages/store/bundle", "组合套餐", 2, rStore, "pages/store/bundle"),
                p("pages/me/wallet", "钱包", 2, rMe, "pages/me/wallet"),
                p("pages/me/wallet-topup", "充值", 3, rMe, "pages/me/wallet"),
                p("pages/me/wallet-withdraw", "提现", 3, rMe, "pages/me/wallet"),
                p("pages/me/wallet-withdraw-tracking", "提现状态", 3, rMe, "pages/me/wallet"),
                p("pages/me/wallet-exchange", "兑换", 3, rMe, "pages/me/wallet"),
                p("pages/me/wallet-exchange-how", "兑换 · 玩法说明", 3, rMe, "pages/me/wallet"),
                p("pages/me/wallet-bills", "账单", 3, rMe, "pages/me/wallet"),
                p("pages/me/wallet-nex", "NEX 钱包", 3, rMe, "pages/me/wallet"),
                p("pages/me/wallet-cards", "支付卡", 3, rMe, "pages/me/wallet"),
                p("pages/me/wallet-cards-new", "添加支付卡", 3, rMe, "pages/me/wallet"),
                p("pages/me/wallet-repurchase", "复投", 3, rMe, "pages/me/wallet"),
                p("pages/me/wallet-repurchase-how", "复投 · 玩法说明", 3, rMe, "pages/me/wallet"),
                p("pages/me/devices", "我的设备", 2, rMe, "pages/me/devices"),
                p("pages/me/profile", "个人资料", 2, rMe, "pages/me/profile"),
                p("pages/me/security", "安全", 2, rMe, "pages/me/security"),
                p("pages/me/kyc", "KYC 认证", 2, rMe, "pages/me/security"),
                p("pages/me/goals", "目标", 2, rMe, "pages/me/goals"),
                p("pages/me/achievements", "成就", 2, rMe, "pages/me/achievements"),
                p("pages/me/proof", "贡献凭证", 2, rMe, "pages/me/proof"),
                p("pages/me/wrapped", "年度回顾", 2, rMe, "pages/me/wrapped"),
                p("pages/me/receipts", "收据", 2, rMe, "pages/me/receipts"),
                p("pages/me/rewards", "奖励记录", 2, rMe, "pages/me/rewards"),
                p("pages/me/help", "帮助中心", 2, rMe, "pages/me/help"),
                p("pages/me/support", "客服", 2, rMe, "pages/me/support"),
                p("pages/me/support-tickets", "工单", 3, rMe, "pages/me/support"),
                p("pages/support/messages", "客服消息", 3, rMe, "pages/me/support"),
                p("pages/support/chat", "客服会话", 3, rMe, "pages/me/support"),
                p("pages/me/notifications", "消息", 2, rMe, "pages/me/notifications"),
                p("pages/me/preferences", "偏好设置", 2, rMe, "pages/me/preferences"),
                p("pages/me/language", "语言", 2, rMe, "pages/me/preferences"),
                p("pages/me/replay-tour", "重播引导", 2, rMe, "pages/me/replay-tour"),
                p("pages/me/risk-disclosure", "风险披露", 2, rMe, "pages/me/risk-disclosure"),
                p("pages/me/trial", "试用", 2, rMe, "pages/me/trial"),
                p("pages/team/commissions", "佣金", 2, rTeam, "pages/team/commissions"),
                p("pages/team/commissions-how", "佣金 · 玩法说明", 3, rTeam, "pages/team/commissions"),
                p("pages/team/binary", "平衡匹配", 2, rTeam, "pages/team/binary"),
                p("pages/team/binary-how", "平衡匹配 · 玩法说明", 3, rTeam, "pages/team/binary"),
                p("pages/team/unilevel", "影响力网络版税", 2, rTeam, "pages/team/unilevel"),
                p("pages/team/unilevel-how", "网络版税 · 玩法说明", 3, rTeam, "pages/team/unilevel"),
                p("pages/team/agent", "区域大使", 2, rTeam, "pages/team/agent"),
                p("pages/team/rank", "V 级头衔", 2, rTeam, "pages/team/rank"),
                p("pages/team/rank-how", "V 级 · 玩法说明", 3, rTeam, "pages/team/rank"),
                p("pages/team/leaderboard", "邀请榜", 2, rTeam, "pages/team/leaderboard"),
                p("pages/team/leadership-pool", "领导池", 2, rTeam, "pages/team/leadership-pool"),
                p("pages/team/leadership-pool-how", "领导池 · 玩法说明", 3, rTeam, "pages/team/leadership-pool"),
                p("pages/team/network", "影响力网络", 2, rTeam, "pages/team/network"),
                p("pages/team/quota", "硬件配额", 2, rTeam, "pages/team/quota"),
                p("pages/team/tree", "族谱", 2, rTeam, "pages/team/tree"),
                p("pages/genesis/holder", "创世持有", 2, "pages/genesis/genesis", "pages/genesis/holder"),
                p("pages/genesis/marketplace", "二级市场", 2, "pages/genesis/genesis", "pages/genesis/marketplace"),
                p("pages/genesis/how-it-works", "创世 · 玩法说明", 3, "pages/genesis/genesis", "pages/genesis/genesis"),
                p("pages/staking/how-it-works", "质押 · 玩法说明", 3, "pages/staking/staking", "pages/staking/staking"),
                p("pages/trust/nex", "NEX 信任", 2, "pages/trust/trust", "pages/trust/nex"),
                p("pages/onboarding/intro", "引导 · 介绍", 2, rIndex, "pages/onboarding/intro"),
                p("pages/onboarding/estimator", "引导 · 收益估算", 2, rIndex, "pages/onboarding/estimator"),
                p("pages/onboarding/connect", "引导 · 设备连接", 2, rIndex, "pages/onboarding/connect"),
                p("pages/onboarding/terms", "引导 · 条款", 2, rIndex, "pages/onboarding/terms"),
                p("pages/register/register", "注册", 2, rIndex, "pages/register/register"),
                p("pages/login/login", "登录", 2, rIndex, "pages/login/login"),
                p("pages/session/kicked", "会话被踢出", 3, rMe, "pages/session/kicked", false),
                p("pages/ref/code", "邀请码", 3, rTeam, "pages/ref/code", false),
                p("pages/tx/hash", "交易详情", 3, rMe, "pages/tx/hash", false));
    }

    private static List<Map<String, Object>> buildPageActivity(String window, List<PageSeed> seeds) {
        double factor = switch (window) {
            case "24h" -> 0.15d;
            case "30d" -> 4.1d;
            default -> 1d;
        };
        return seeds.stream()
                .filter(PageSeed::tracked)
                .map(seed -> {
                    int[] band = pvBand(seed.level());
                    int pvBase = (int) Math.round(lerp(band[0], band[1], seeded(seed.route(), "pv")));
                    int pv = Math.max(1, (int) Math.round(pvBase * factor));
                    int uv = (int) Math.round(pv * lerp(0.52d, 0.74d, seeded(seed.route(), "uv")));
                    int clicks = (int) Math.round(pv * lerp(1.4d, 4.6d, seeded(seed.route(), "clk")));
                    int dwellMs = (int) Math.round(lerp(14_000d, 175_000d, seeded(seed.route(), "dwell")));
                    double bounceRate = round3(lerp(0.08d, 0.66d, seeded(seed.route(), "bounce")));
                    return linked("route", seed.route(), "pv", pv, "uv", uv, "clicks", clicks, "dwellMs", dwellMs, "bounceRate", bounceRate);
                })
                .toList();
    }

    private static Map<String, Object> buildPageClickHeat(PageSeed seed, List<PageSeed> seeds) {
        double[] raw = new double[zoneTemplates().size()];
        double sum = 0d;
        for (int index = 0; index < zoneTemplates().size(); index++) {
            ZoneSeed zone = zoneTemplates().get(index);
            raw[index] = zone.baseShare() * lerp(0.55d, 1.5d, seeded(seed.route(), "zone" + index));
            sum += raw[index];
        }
        java.util.ArrayList<Map<String, Object>> zones = new java.util.ArrayList<>();
        for (int index = 0; index < zoneTemplates().size(); index++) {
            ZoneSeed zone = zoneTemplates().get(index);
            zones.add(linked("label", zone.label(), "cx", zone.cx(), "cy", zone.cy(), "share", round3(raw[index] / sum)));
        }
        java.util.ArrayList<Map<String, Object>> points = new java.util.ArrayList<>();
        for (int zoneIndex = 0; zoneIndex < zones.size(); zoneIndex++) {
            ZoneSeed template = zoneTemplates().get(zoneIndex);
            double share = ((Number) zones.get(zoneIndex).get("share")).doubleValue();
            int count = 2 + (int) Math.round(share * 12);
            for (int index = 0; index < count; index++) {
                double xSeed = seeded(seed.route(), "px" + zoneIndex + "_" + index);
                double ySeed = seeded(seed.route(), "py" + zoneIndex + "_" + index);
                double weightSeed = seeded(seed.route(), "pw" + zoneIndex + "_" + index);
                points.add(linked(
                        "x", clamp01(template.cx() + (xSeed - 0.5d) * 2d * (template.spread() + 0.06d)),
                        "y", clamp01(template.cy() + (ySeed - 0.5d) * 2d * template.spread()),
                        "weight", round3(Math.min(1d, lerp(0.35d, 1d, weightSeed * (0.6d + share))))));
            }
        }
        return linked("route", seed.route(), "titleZh", seed.titleZh(), "zones", zones, "points", points);
    }

    private static int[] pvBand(int level) {
        return switch (level) {
            case 1 -> new int[]{7_800, 21_000};
            case 2 -> new int[]{820, 5_200};
            default -> new int[]{110, 1_300};
        };
    }

    private static List<ZoneSeed> zoneTemplates() {
        return List.of(
                new ZoneSeed("顶栏 / 返回", 0.5d, 0.07d, 0.12d, 0.05d),
                new ZoneSeed("主行动按钮", 0.5d, 0.46d, 0.34d, 0.09d),
                new ZoneSeed("内容列表 / 卡片", 0.5d, 0.68d, 0.32d, 0.14d),
                new ZoneSeed("底部导航", 0.5d, 0.95d, 0.22d, 0.04d));
    }

    private static PageSeed p(String route, String titleZh, int level, String parentL1, String parentL2) {
        return p(route, titleZh, level, parentL1, parentL2, true);
    }

    private static PageSeed p(String route, String titleZh, int level, String parentL1, String parentL2, boolean tracked) {
        return new PageSeed(route, titleZh, level, parentL1, parentL2, tracked);
    }

    private static double seeded(String route, String salt) {
        return (hash32(route + "|" + salt) % 100_000L) / 100_000d;
    }

    private static long hash32(String value) {
        long hash = 2_166_136_261L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash = (hash * 16_777_619L) & 0xffff_ffffL;
        }
        return hash;
    }

    private static double lerp(double start, double end, double ratio) {
        return start + (end - start) * ratio;
    }

    private static double round3(double value) {
        return Math.round(value * 1000d) / 1000d;
    }

    private static double clamp01(double value) {
        if (value < 0.02d) return 0.02d;
        if (value > 0.98d) return 0.98d;
        return value;
    }

    private record PageSeed(String route, String titleZh, int level, String parentL1, String parentL2, boolean tracked) {
    }

    private record ZoneSeed(String label, double cx, double cy, double baseShare, double spread) {
    }

    private static Map<String, Object> kpi(int n, String name, double value, double target, String unit, String dir,
                                           List<Integer> band, String cohort, String visibleBatch, List<? extends Number> spark) {
        Map<String, Object> response = linked("n", n, "name", name, "value", value, "target", target, "unit", unit, "dir", dir, "cohort", cohort, "vis", visibleBatch, "spark", spark);
        if (band != null) {
            response.put("band", band);
        }
        return response;
    }

    private static Map<String, Object> kpiExt(String fx, List<String> fxBold, String num, String den, String delta, String note, List<Map<String, Object>> jump) {
        return linked("fx", fx, "fxBold", fxBold, "num", num, "den", den, "delta", delta, "note", note, "jump", jump);
    }

    private static Map<String, Object> jump(String label, String href) {
        Map<String, Object> row = linked("label", label);
        if (href != null) {
            row.put("href", href);
        }
        return row;
    }

    private static Map<String, Object> funnel(String stage, String event, int users, Double cvr, String lc, String color, String target) {
        return linked("stage", stage, "ev", event, "users", users, "cvr", cvr, "lc", lc, "color", color, "target", target);
    }

    private static Map<String, Object> funnelExt(String plain, String inflow, String lost, List<Integer> dwell, String note, String target, boolean trial, boolean v1) {
        Map<String, Object> row = linked("plain", plain, "inflow", inflow, "lost", lost, "dwell", dwell, "note", note);
        if (target != null) row.put("tg", target);
        if (trial) row.put("trial", true);
        if (v1) row.put("v1", true);
        return row;
    }

    private static Map<String, Object> cohort(String week, int size, Integer day1, Integer day7, Integer day30) {
        return linked("w", week, "size", size, "d1", day1, "d7", day7, "d30", day30);
    }

    private static List<List<Integer>> curve(List<Integer> days, List<Integer> values) {
        java.util.ArrayList<List<Integer>> points = new java.util.ArrayList<>();
        for (int index = 0; index < days.size(); index++) {
            points.add(List.of(days.get(index), values.get(index)));
        }
        return points;
    }

    private static Map<String, Object> crossAnalysis() {
        return linked(
                "cvr", crossMetric(List.of(List.of("ref 自然量", 7.4, 7.1, 6.8, 6.2, 6.9), List.of("ref NX-大使", 8.2, 7.9, 7.5, 7.0, 7.7), List.of("ref TikTok", 6.1, 5.8, 3.1, 5.5, 5.1), List.of("ref Meta", 6.8, 6.4, 6.0, 5.9, 6.3)), "%", "异常信号 · P3 期 · 渠道 ", "ref TikTok × locale es", " 首购 CVR 3.1%,显著低于行均值。归因入口 →"),
                "ret", crossMetric(List.of(List.of("ref 自然量", 61, 60, 58, 56, 59), List.of("ref NX-大使", 64, 63, 61, 60, 62), List.of("ref TikTok", 54, 53, 49, 51, 52), List.of("ref Meta", 58, 57, 55, 54, 56)), "%", "异常信号 · ", "ref TikTok", " 整行 Day7 留存低于其他渠道。"),
                "trial", crossMetric(List.of(List.of("ref 自然量", 23.8, 22.9, 21.4, 20.8, 22.2), List.of("ref NX-大使", 26.1, 25.4, 24.0, 23.2, 24.7), List.of("ref TikTok", 19.0, 18.2, 14.6, 17.5, 17.3), List.of("ref Meta", 21.6, 20.8, 19.9, 19.2, 20.4)), "%", "异常信号 · trial 子漏斗同样在 ", "TikTok × es", " 显著走低。"));
    }

    private static Map<String, Object> crossMetric(List<List<? extends Object>> rows, String unit, String pre, String bold, String post) {
        return linked("rows", rows, "alert", List.of(2, 3), "unit", unit, "msg", linked("pre", pre, "bold", bold, "post", post));
    }

    private static List<Map<String, Object>> liabilities() {
        return List.of(
                linked("id", 1, "name", "可提余额", "amount", 1_180_000, "color", "var(--admin-cat-1)"),
                linked("id", 2, "name", "USDT 质押本金", "amount", 1_640_000, "color", "var(--admin-cat-2)"),
                linked("id", 3, "name", "质押应付利息", "amount", 312_000, "color", "var(--admin-cat-3)"),
                linked("id", 4, "name", "Genesis 日分红承诺", "amount", 268_000, "color", "var(--admin-cat-4)"),
                linked("id", 5, "name", "NEX v2 历史兑付", "amount", 880_000, "color", "var(--admin-cat-5)"),
                linked("id", 6, "name", "待提现队列", "amount", 430_000, "color", "var(--admin-cat-6)"),
                linked("id", 7, "name", "佣金冷却未解锁", "amount", 410_000, "color", "var(--admin-cat-7)"),
                linked("id", 8, "name", "锁仓本息 / 其他", "amount", 250_000, "color", "var(--admin-cat-8)"));
    }

    private static Map<String, Object> rev(String name, String src, int amount, String mom, boolean up, String color) {
        return linked("nm", name, "src", src, "amt", amount, "mom", mom, "up", up, "color", color);
    }

    private static Map<String, Object> dist(String name, int count, String color, String generation) {
        return linked("nm", name, "n", count, "color", color, "gen", generation);
    }

    private static Map<String, Object> taskTier(String name, int count, int acceptRate, String color) {
        return linked("nm", name, "n", count, "acc", acceptRate, "color", color);
    }

    private static Map<String, Object> segment(String name, int count, String color) {
        return linked("nm", name, "n", count, "color", color);
    }

    private static Map<String, Object> pctSegment(String name, int pct, String color) {
        return linked("nm", name, "pct", pct, "color", color);
    }

    private static Map<String, Object> phaseRow(String name, List<String> values, List<String> steps) {
        return linked("nm", name, "vals", values, "steps", steps);
    }

    private static List<Map<String, Object>> phases() {
        return List.of(linked("code", "P1", "name", "拉新"), linked("code", "P2", "name", "激活"), linked("code", "P3", "name", "扩张"), linked("code", "P4", "name", "深化"), linked("code", "P5", "name", "收紧"), linked("code", "P6", "name", "软退场"));
    }

    private static Map<String, Object> template(String key, String name, String cycle, String meta, String last, String icon) {
        return linked("key", key, "nm", name, "cy", cycle, "meta", meta, "last", last, "icon", icon);
    }

    private static Map<String, Object> mask(String field, String cat, String catTone, String rule, String ruleNote, String decrypt, String approve) {
        return linked("f", field, "cat", cat, "catTone", catTone, "rule", rule, "ruleNote", ruleNote, "dec", decrypt, "appr", approve);
    }

    private static Map<String, Object> param(String key, String value, boolean fixed, String current, String summary) {
        return linked("k", key, "v", value, "fixed", fixed, "cur", current, "s", summary);
    }

    private static Map<String, Object> auditRow(String ts, String who, String what, String rows, boolean pii, String mask, String chain, String download) {
        return linked("ts", ts, "who", who, "what", what, "rows", rows, "pii", pii, "mask", mask, "chain", chain, "dl", download);
    }

    private static Map<String, Object> linked(Object... pairs) {
        Map<String, Object> response = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            response.put((String) pairs[i], pairs[i + 1]);
        }
        return response;
    }
}

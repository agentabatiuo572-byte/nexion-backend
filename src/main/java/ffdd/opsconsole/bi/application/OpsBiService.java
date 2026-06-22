package ffdd.opsconsole.bi.application;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.bi.domain.BiReportRepository;
import ffdd.opsconsole.bi.domain.BiReportView;
import ffdd.opsconsole.bi.dto.BiReportActionRequest;
import ffdd.opsconsole.bi.dto.BiReportQueryRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import java.util.Arrays;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsBiService {
    private static final Set<String> ACTIONS = Set.of("GENERATE", "RERUN", "APPROVE", "DOWNLOAD");

    private final BiReportRepository reportRepository;
    private final AuditLogService auditLogService;

    public ApiResult<Map<String, Object>> overview() {
        Map<String, Object> response = new LinkedHashMap<>(reportRepository.overview());
        response.put("domain", "L");
        response.put("capabilities", List.of("BIReport", "ExportJob", "Funnel", "RegulatoryReport"));
        response.put("maskingRules", maskingRules());
        response.put("securityParams", List.of(
                "sensitive exports require confirm-with-reason and A2 audit",
                "download token TTL is 24 hours",
                "decrypted PII export is blocked in active admin API"));
        response.put("sources", List.of("nx_admin_fourth_batch_report", "nx_audit_log"));
        response.put("currentPhase", currentPhase());
        response.put("phases", phases());
        response.put("l1", Map.of("kpis", kpis()));
        response.put("l2", funnelDashboard());
        response.put("l3", financeDashboard());
        response.put("l4", operationsDashboard());
        return ApiResult.ok(response);
    }

    public ApiResult<PageResult<BiReportView>> reports(BiReportQueryRequest request) {
        int pageNum = normalizePageNum(request == null ? null : request.pageNum());
        int pageSize = normalizePageSize(request == null ? null : request.pageSize(), request == null ? null : request.limit());
        return ApiResult.ok(reportRepository.reports(
                request == null ? null : request.type(),
                normalizeStatuses(request == null ? null : request.status()),
                pageNum,
                pageSize));
    }

    public ApiResult<Map<String, Object>> regulatoryTemplates() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "L5");
        response.put("templates", List.of(
                template("REG-KYC-Q", "KYC AML audit package", "Quarterly", "KYC status, EDD, sanctions, rejection reasons"),
                template("REG-REDEMPTION-M", "Redemption report", "Monthly", "withdrawal rate, coverage, exposure, maturity"),
                template("REG-FRAUD-M", "Fraud abuse report", "Monthly", "device fingerprint, account graph, blocked withdrawals"),
                template("REG-GEO-Q", "Cross border data list", "Quarterly", "jurisdiction, field class, masking, download audit")));
        response.put("exportPolicy", "masked-by-default");
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> reportAction(String reportId, String action, String idempotencyKey, BiReportActionRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedAction = normalizeAction(action);
        if (Boolean.TRUE.equals(request.includeDecrypted())) {
            return ApiResult.fail(OpsErrorCode.RETIRED_FEATURE.httpStatus(), "DECRYPTED_PII_EXPORT_BLOCKED");
        }
        if (!StringUtils.hasText(reportId)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REPORT_ID_REQUIRED");
        }
        BiReportView report = reportRepository.findReport(reportId.trim()).orElse(null);
        if (report == null) {
            return ApiResult.fail(404, "BI_REPORT_NOT_FOUND");
        }
        String nextStatus = nextStatus(report, normalizedAction);
        if (nextStatus == null) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        reportRepository.updateAction(report.reportId(), normalizedAction, nextStatus, request.reason().trim());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reportId", report.reportId());
        response.put("action", normalizedAction);
        response.put("status", nextStatus);
        response.put("containsPii", report.containsPii());
        response.put("maskingPolicy", report.maskingPolicy());
        if ("DOWNLOAD".equals(normalizedAction)) {
            response.put("downloadTokenIssued", true);
            response.put("downloadTtlHours", 24);
            response.put("downloadPath", "/api/admin/bi/exports/" + report.reportId() + "/download-token");
        }
        audit("L_BI_REPORT_" + normalizedAction, report, request.operator(), Map.of(
                "reportId", report.reportId(),
                "action", normalizedAction,
                "status", nextStatus,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim(),
                "includeSensitive", Boolean.TRUE.equals(request.includeSensitive()),
                "includeDecrypted", false));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> downloadToken(String reportId) {
        if (!StringUtils.hasText(reportId)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REPORT_ID_REQUIRED");
        }
        BiReportView report = reportRepository.findReport(reportId.trim()).orElse(null);
        if (report == null) {
            return ApiResult.fail(404, "BI_REPORT_NOT_FOUND");
        }
        if (!"READY".equals(normalizeText(report.status()))) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        LocalDateTime issuedAt = LocalDateTime.now();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reportId", report.reportId());
        response.put("downloadToken", UUID.randomUUID().toString());
        response.put("issuedAt", issuedAt);
        response.put("expiresAt", issuedAt.plusHours(24));
        response.put("maskingPolicy", report.maskingPolicy());
        response.put("containsPii", report.containsPii());
        response.put("decryptedPiiBlocked", true);
        response.put("sources", List.of("nx_admin_fourth_batch_report", "nx_audit_log"));
        return ApiResult.ok(response);
    }

    private String nextStatus(BiReportView report, String action) {
        String current = normalizeText(report.status());
        boolean sensitive = Boolean.TRUE.equals(report.containsPii());
        return switch (action) {
            case "GENERATE", "RERUN" -> sensitive ? "PENDING_CONFIRM" : "GENERATING";
            case "APPROVE" -> Set.of("PENDING_CONFIRM", "PENDING_SPLIT_CONFIRM").contains(current) ? "GENERATING" : null;
            case "DOWNLOAD" -> "READY".equals(current) ? "READY" : null;
            default -> null;
        };
    }

    private List<Map<String, Object>> maskingRules() {
        return List.of(
                mask("phone", "PII", "hash", "decryption blocked in active API"),
                mask("cardToken", "PII", "last4", "strong confirm required for export"),
                mask("billAmount", "funds", "plain", "batch export requires reason"));
    }

    private Map<String, Object> mask(String field, String className, String policy, String note) {
        return Map.of("field", field, "class", className, "policy", policy, "note", note);
    }

    private Map<String, Object> template(String key, String name, String cycle, String fields) {
        return Map.of("key", key, "name", name, "cycle", cycle, "fields", fields);
    }

    private Map<String, Object> currentPhase() {
        return linked(
                "code", "P3",
                "name", "扩张期",
                "index", 2,
                "month", 7,
                "total", 12,
                "etaDays", 14,
                "focus", "重心:拉新 + 首购转化,放宽试用,谨慎放大资金流出");
    }

    private List<Map<String, Object>> phases() {
        return List.of(
                phase("P1", "拉新"),
                phase("P2", "激活"),
                phase("P3", "扩张"),
                phase("P4", "深化"),
                phase("P5", "收紧"),
                phase("P6", "软退场"));
    }

    private Map<String, Object> phase(String code, String name) {
        return Map.of("code", code, "name", name);
    }

    private List<Map<String, Object>> kpis() {
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

    private Map<String, Object> kpi(int n, String name, double value, double target, String unit, String dir,
                                   List<Integer> band, String cohort, String visibleBatch, List<? extends Number> spark) {
        Map<String, Object> response = linked(
                "n", n,
                "name", name,
                "value", value,
                "target", target,
                "unit", unit,
                "dir", dir,
                "cohort", cohort,
                "vis", visibleBatch,
                "spark", spark);
        if (band != null) {
            response.put("band", band);
        }
        return response;
    }

    private Map<String, Object> funnelDashboard() {
        return linked(
                "funnel", List.of(
                        funnel("注册", "auth.register_completed", 128400, null, "L1", "#9EDC1D", null),
                        funnel("绑卡 $1 KYC", "kyc.express_verified", 97300, 75.8, "L2", "#B6E84A", null),
                        funnel("首购", "checkout.completed", 33180, 34.1, "L3→L4", "#9B89E0", ">30%"),
                        funnel("复投", "checkout.completed ×2", 8920, 26.9, "L5", "#B6A4FF", null),
                        funnel("提现", "withdraw.submitted", 21640, 65.2, "L5", "#29D27F", null)),
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
                "crossAnalysis", crossAnalysis());
    }

    private Map<String, Object> funnel(String stage, String event, int users, Double cvr, String lc, String color, String target) {
        return linked("stage", stage, "ev", event, "users", users, "cvr", cvr, "lc", lc, "color", color, "target", target);
    }

    private Map<String, Object> cohort(String week, int size, Integer day1, Integer day7, Integer day30) {
        return linked("w", week, "size", size, "d1", day1, "d7", day7, "d30", day30);
    }

    private Map<String, Object> financeDashboard() {
        Map<String, Object> ledger = linked(
                "reserveUsd", 6_340_000,
                "liabilitiesUsd", 5_370_000,
                "coverageRatio", 118.1,
                "redlinePct", 100,
                "healthyPct", 110,
                "coverageSeries", List.of(113.0, 114.0, 115.0, 116.0, 116.5, 117.0, 117.5, 118.1));
        return linked(
                "ledger", ledger,
                "treasury", linked(
                        "reserveTotal", 6_340_000,
                        "liabilityTotal", 5_370_000,
                        "coverageRatio", 118.1,
                        "redLine", 100,
                        "yellowLine", 110,
                        "light", "green",
                        "deltaPct", 5.1,
                        "netExposure", 970_000),
                "liabilities", List.of(
                        liability(1, "可提余额", 1_180_000, "--admin-cat-1"),
                        liability(2, "USDT 质押本金", 1_640_000, "--admin-cat-2"),
                        liability(3, "质押应付利息", 312_000, "--admin-cat-3"),
                        liability(4, "Genesis 日分红承诺", 268_000, "--admin-cat-4"),
                        liability(5, "NEX v2 历史兑付", 880_000, "--admin-cat-5"),
                        liability(6, "待提现队列", 430_000, "--admin-cat-6"),
                        liability(7, "佣金冷却未解锁", 410_000, "--admin-cat-7"),
                        liability(8, "锁仓本息 / 其他", 250_000, "--admin-cat-8")),
                "revenue", linked("gmv", 4_280_000, "commission", 1_140_000, "token", 980_000, "marketFee", 312_000),
                "maturity7d", linked("withdraw", 348_000, "interest", 78_000, "genesis", 142_900),
                "maturity30d", linked("withdraw", 1_272_960, "interest", 308_100, "genesis", 613_445),
                "maturitySchedule", linked(
                        "weeks", List.of("本周", "+1 周", "+2 周", "+3 周"),
                        "data", List.of(
                                List.of(348_000, 78_000, 142_900),
                                List.of(313_200, 74_100, 150_045),
                                List.of(334_080, 79_560, 157_190),
                                List.of(292_320, 76_440, 164_210))),
                "coverageWeeks", List.of("3/30", "4/06", "4/13", "4/20", "4/27", "5/04", "5/11", "5/18"),
                "coverageBreaches", List.of(
                        linked("i", 1, "type", "run", "label", "4/08 挤兑比率破线 · bankrun_threshold_breached(B5 雷达已联动)")),
                "reserveCoverDays", 77,
                "redemption", linked(
                        "submitted", 12840,
                        "confirmed", 12416,
                        "avgLatency", "7.4h",
                        "rejected", 182,
                        "delayed", 198,
                        "frozen", 44,
                        "prevRate", 96.3,
                        "prevLabel", "2026-04"));
    }

    private Map<String, Object> liability(int id, String name, int amount, String colorVar) {
        return linked("id", id, "name", name, "amount", amount, "color", "var(" + colorVar + ")");
    }

    private Map<String, Object> operationsDashboard() {
        return linked(
                "deviceTotal", 41208,
                "deviceTiles", linked("locked", 28406, "retired", 2114, "dailyUsd", "$141.0K", "dailyNex", "38,420 NEX"),
                "taskTiles", linked("done", "1.84M", "dispatched", "3.18M", "doneN", 1_838_000, "dispatchedN", 3_180_000, "saturation", "63%", "checkin", "61,420", "tierAvg", "$0.042"),
                "teamGmv", "$2.84M / 周 · 占总 GMV 43%",
                "deviceDistribution", List.of(
                        dist("NexionBox S1", 18204, "#B6A4FF", "legacy"),
                        dist("NexionBox Pro", 9412, "#8A95A8", "legacy"),
                        dist("NexionBox Pro v2", 8108, "#9EDC1D", "current"),
                        dist("NexionRack P1", 3254, "#B6E84A", "legacy"),
                        dist("NexionRack P2", 2230, "#29D27F", "current")),
                "decaySegments", List.of(
                        linked("m", "月 1–6 段", "r", "-4%/月", "tone", "var(--success)", "imp", "影响产出 −$3.2K/日"),
                        linked("m", "月 7–12 段", "r", "-6%/月", "tone", "var(--warning)", "imp", "影响产出 −$5.8K/日"),
                        linked("m", "月 13+ 段", "r", "-10%/月", "tone", "var(--danger)", "imp", "影响产出 −$2.1K/日"),
                        linked("m", "MIN_EFFICIENCY", "r", "地板生效 412 台", "tone", "var(--ink-2)", "imp", "已触底设备占 1.0%")),
                "taskTiers", List.of(
                        taskTier("tier-1 · 轻量验证", 710000, 94, "#9EDC1D"),
                        taskTier("tier-2 · 数据标注", 486000, 88, "#B6E84A"),
                        taskTier("tier-3 · 渲染切片", 318000, 71, "#29D27F"),
                        taskTier("tier-4 · 模型微调", 188000, 62, "#FFBE3D"),
                        taskTier("tier-5 · 批量推理", 96000, 41, "#FF6B35"),
                        taskTier("tier-6 · 专项算力", 40000, 33, "#DD6F5C")),
                "vrHistory", List.of(9120, 6820, 4410, 2980, 2010, 1340, 860, 520, 300, 170, 90, 40, 12),
                "referralDistribution", List.of(
                        segment("直推 0(纯持有)", 24105, "#8A95A8"),
                        segment("直推 1–3", 10240, "#B6A4FF"),
                        segment("直推 4–10", 4820, "#9EDC1D"),
                        segment("直推 11–50", 1742, "#FFBE3D"),
                        segment("直推 50+", 301, "#FF6B35")),
                "commissionDistribution", List.of(
                        pctSegment("network 网络版税", 42, "#9EDC1D"),
                        pctSegment("binary 平衡匹配", 22, "#B6A4FF"),
                        pctSegment("peer 平级奖", 14, "#6FB7FF"),
                        pctSegment("cultivation 育成", 10, "#FFBE3D"),
                        pctSegment("leadership 领导池", 8, "#29D27F"),
                        pctSegment("genesis 版税", 4, "#DD6F5C")),
                "phaseRows", List.of(
                        phaseRow("Day7 留存", List.of("64.1%", "61.0%", "58.2%", "—", "—", "—"), List.of("", "-3.1pt", "-2.8pt", "", "", "")),
                        phaseRow("首购 CVR(L3→L4)", List.of("5.4%", "6.2%", "6.8%", "—", "—", "—"), List.of("", "+0.8pt", "+0.6pt", "", "", "")),
                        phaseRow("设备日产 / 台", List.of("$3.61", "$3.52", "$3.42", "—", "—", "—"), List.of("", "-2.5%", "-2.8%", "", "", "")),
                        phaseRow("任务承接率", List.of("71%", "64%", "57.8%", "—", "—", "—"), List.of("", "-7pt", "-6.2pt", "", "", "")),
                        phaseRow("提现量(周)", List.of("$1.9M", "$2.6M", "$2.1M", "—", "—", "—"), List.of("", "+37%", "-19%", "", "", "")),
                        phaseRow("佣金触发率(#7)", List.of("68%", "73%", "76%", "—", "—", "—"), List.of("", "+5pt", "+3pt", "", "", ""))));
    }

    private Map<String, Object> dist(String name, int n, String color, String generation) {
        return linked("nm", name, "n", n, "color", color, "gen", generation);
    }

    private List<List<Integer>> curve(List<Integer> days, List<Integer> values) {
        List<List<Integer>> points = new java.util.ArrayList<>();
        for (int index = 0; index < days.size(); index++) {
            points.add(List.of(days.get(index), values.get(index)));
        }
        return points;
    }

    private Map<String, Object> crossAnalysis() {
        return linked(
                "cvr", crossMetric(
                        List.of(
                                List.of("ref 自然量", 7.4, 7.1, 6.8, 6.2, 6.9),
                                List.of("ref NX-大使", 8.2, 7.9, 7.5, 7.0, 7.7),
                                List.of("ref TikTok", 6.1, 5.8, 3.1, 5.5, 5.1),
                                List.of("ref Meta", 6.8, 6.4, 6.0, 5.9, 6.3)),
                        "首购 CVR 3.1%,显著低于行均值 6.5%(−52%)。归因入口 →"),
                "ret", crossMetric(
                        List.of(
                                List.of("ref 自然量", 61, 60, 58, 56, 59),
                                List.of("ref NX-大使", 64, 63, 61, 60, 62),
                                List.of("ref TikTok", 54, 53, 49, 51, 52),
                                List.of("ref Meta", 58, 57, 55, 54, 56)),
                        "ref TikTok 整行 Day7 留存低于其他渠道 5–8pt——低质量买量特征,渠道质量归因跳 F 域。"),
                "trial", crossMetric(
                        List.of(
                                List.of("ref 自然量", 23.8, 22.9, 21.4, 20.8, 22.2),
                                List.of("ref NX-大使", 26.1, 25.4, 24.0, 23.2, 24.7),
                                List.of("ref TikTok", 19.0, 18.2, 14.6, 17.5, 17.3),
                                List.of("ref Meta", 21.6, 20.8, 19.9, 19.2, 20.4)),
                        "trial 子漏斗同样在 TikTok × es 显著走低——与主漏斗首购转化同向,试用规则归 H2,文案归 I 域。"));
    }

    private Map<String, Object> crossMetric(List<List<? extends Object>> rows, String message) {
        return linked("rows", rows, "alert", List.of(2, 3), "unit", "%", "message", message);
    }

    private Map<String, Object> taskTier(String name, int count, int acceptRate, String color) {
        return linked("nm", name, "n", count, "acc", acceptRate, "color", color);
    }

    private Map<String, Object> segment(String name, int count, String color) {
        return linked("nm", name, "n", count, "color", color);
    }

    private Map<String, Object> pctSegment(String name, int pct, String color) {
        return linked("nm", name, "pct", pct, "color", color);
    }

    private Map<String, Object> phaseRow(String name, List<String> values, List<String> steps) {
        return linked("nm", name, "vals", values, "steps", steps);
    }

    private Map<String, Object> linked(Object... pairs) {
        Map<String, Object> response = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            response.put((String) pairs[i], pairs[i + 1]);
        }
        return response;
    }

    private ApiResult<Map<String, Object>> requireCommand(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason) || reason.trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private String normalizeAction(String action) {
        String normalized = normalizeText(action);
        if (!ACTIONS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported L report action");
        }
        return normalized;
    }

    private int normalizePageNum(Integer pageNum) {
        int value = pageNum == null ? 1 : pageNum;
        if (value < 1) {
            return 1;
        }
        return Math.min(value, 10_000);
    }

    private int normalizePageSize(Integer pageSize, Integer legacyLimit) {
        int value = pageSize != null ? pageSize : (legacyLimit == null ? 20 : legacyLimit);
        if (value < 1) {
            return 20;
        }
        return Math.min(value, 100);
    }

    private List<String> normalizeStatuses(String status) {
        if (!StringUtils.hasText(status)) {
            return List.of();
        }
        String normalized = normalizeText(status);
        if ("ALL".equals(normalized)) {
            return List.of();
        }
        return Arrays.stream(normalized.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private void audit(String action, BiReportView report, String operator, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("BI_REPORT")
                .resourceId(report.reportId())
                .bizNo(report.reportId())
                .actorType("ADMIN")
                .actorUsername(StringUtils.hasText(operator) ? operator.trim() : "system")
                .result("SUCCESS")
                .riskLevel(Boolean.TRUE.equals(report.containsPii()) ? "HIGH" : "MEDIUM")
                .detail(detail)
                .build());
    }
}

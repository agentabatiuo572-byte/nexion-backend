package ffdd.opsconsole.overview.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.overview.mapper.OpsPhaseMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsPhaseOverviewService {
    private static final Pattern PHASE = Pattern.compile("P[1-6]");
    private static final Set<String> GRANULARITIES = Set.of("PHASE", "MONTH");
    private static final List<DialDefinition> DIALS = List.of(
            dial("newUserBonusMultiplier", "新用户加成", "倍", "H3–H6 域 V3 后生效", false),
            dial("inviteRewardMultiplier", "邀请加成", "倍", "H3–H6 域 V3 后生效", false),
            dial("reinvestMultiplier", "复投加成", "倍", "G7 域 V3 后生效", false),
            dial("withdrawPenaltyFeeRate", "提现惩罚费率", "%", "D 域 V1 已生效", true),
            dial("withdrawCooldownDays", "提现冷却", "天", "D 域 V1 已生效", true),
            dial("binaryDailyCap", "双轨日封顶", "USDT", "F 域 V2 后生效", false),
            dial("questBonusMultiplier", "任务加成", "倍", "H3 域 V3 后生效", false),
            dial("complianceHoldEnabled", "Compliance hold", "", "D 域 V1 已生效", true));

    private final OpsPhaseMapper mapper;
    private final GrowthRhythmFacade growthRhythmFacade;
    private final AuditLogService auditLogService;

    public ApiResult<Map<String, Object>> overview(String granularity, String month, String phase) {
        Filter filter = filter(granularity, month, phase);
        GrowthRhythmSnapshot current = requireSnapshot(growthRhythmFacade.snapshot());
        int selectedMonth = filter.month() == null ? current.currentMonth() : parseMonth(filter.month(), current.totalMonths());
        Distribution distribution = distribution(current.totalMonths(), filter.phase());
        Map<String, Object> response = linked(
                "available", distribution.totalUsers() > 0,
                "reason", distribution.totalUsers() > 0 ? "" : "B4_PHASE_DISTRIBUTION_EMPTY",
                "filters", linked(
                        "granularity", filter.granularity(),
                        "month", selectedMonth,
                        "phase", valueOrAll(filter.phase()),
                        "monthOptions", monthOptions(current.totalMonths()),
                        "phaseOptions", List.of("P1", "P2", "P3", "P4", "P5", "P6")),
                "rhythm", linked(
                        "currentPhase", current.currentPhase(),
                        "currentMonth", current.currentMonth(),
                        "totalMonths", current.totalMonths(),
                        "phaseProgressPct", current.phaseProgressPct()),
                "phaseDistribution", distribution.phases(),
                "monthDistribution", distribution.months(),
                "distribution", "MONTH".equals(filter.granularity()) ? distribution.months() : distribution.phases(),
                "dials", dialRows(current),
                "nextPivot", nextPivot(current),
                "monthLeverCombo", leverCombo(selectedMonth),
                "attributionLinks", List.of(
                        linked("key", "H1", "label", "H1 Phase 效果归因", "href", h1AttributionHref(filter.phase(), current.currentPhase())),
                        linked("key", "B3", "label", "B3 转化归因", "href", "/overview/funnel?phase=" + valueOrAll(filter.phase())),
                        linked("key", "B1", "label", "B1 资金归因", "href", "/overview/dual-ledger?phase=" + valueOrAll(filter.phase())),
                        linked("key", "B2", "label", "B2 到期与资金池归因", "href", "/overview/liquidity?phase=" + valueOrAll(filter.phase()))),
                "sourceStatement", "H1 GrowthRhythmFacade server-canonical；B4 不本地重算或写入 dial",
                "sources", List.of("H1 GrowthRhythmFacade", "nx_user.created_at", "A2 admin audit"),
                "asOf", OffsetDateTime.now().toString());
        audit("admin.phase_overview_viewed", "GET", "READ", linked(
                "granularity", filter.granularity(),
                "month", selectedMonth,
                "phase", valueOrAll(filter.phase()),
                "userCount", distribution.totalUsers()));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> jump(String dial, String phase) {
        String normalizedPhase = phase(phase);
        String normalizedDial = optional(dial);
        if (normalizedDial == null) {
            normalizedDial = "phaseAttribution";
        }
        String href;
        if ("phaseAttribution".equals(normalizedDial)) {
            href = h1AttributionHref(normalizedPhase, "P1");
        } else {
            String requestedDial = normalizedDial;
            if (DIALS.stream().noneMatch(item -> item.key().equals(requestedDial))) {
                throw new BizException(404, "B4_DIAL_NOT_FOUND");
            }
            href = "/growth/phase?dial=" + requestedDial
                    + "&phase=" + valueOrAll(normalizedPhase) + "&from=B4";
        }
        auditRequired("admin.phase_h1_jump_opened", "GET", "SUCCESS", linked(
                "dial", normalizedDial,
                "phase", valueOrAll(normalizedPhase),
                "href", href));
        return ApiResult.ok(linked("href", href));
    }

    public PhaseCsvFile export(String granularity, String month, String phase) {
        Filter filter = filter(granularity, month, phase);
        GrowthRhythmSnapshot current = requireSnapshot(growthRhythmFacade.snapshot());
        int selectedMonth = filter.month() == null ? current.currentMonth() : parseMonth(filter.month(), current.totalMonths());
        Distribution distribution = distribution(current.totalMonths(), filter.phase());
        if (distribution.totalUsers() < 1) {
            throw new BizException(422, "B4_PHASE_DISTRIBUTION_EMPTY");
        }
        boolean byMonth = "MONTH".equals(filter.granularity());
        StringBuilder csv = new StringBuilder("\uFEFF")
                .append(byMonth ? "month,phase,user_count,selected_month\r\n" : "phase,user_count,selected_phase\r\n");
        if (byMonth) {
            for (Map<String, Object> row : distribution.months()) {
                csv.append(csv(row.get("month"))).append(',')
                        .append(csv(row.get("phase"))).append(',')
                        .append(csv(row.get("userCount"))).append(',')
                        .append(csv(selectedMonth)).append("\r\n");
            }
        } else {
            for (Map<String, Object> row : distribution.phases()) {
                if (filter.phase() != null && !filter.phase().equals(row.get("phase"))) continue;
                csv.append(csv(row.get("phase"))).append(',')
                        .append(csv(row.get("userCount"))).append(',')
                        .append(csv(valueOrAll(filter.phase()))).append("\r\n");
            }
        }
        auditRequired("admin.report_exported", "GET", "SUCCESS", linked(
                "exportType", "B4_PHASE_DISTRIBUTION",
                "scope", linked(
                        "granularity", filter.granularity(),
                        "month", selectedMonth,
                        "phase", valueOrAll(filter.phase())),
                "rowCount", byMonth ? distribution.months().size() : distribution.phases().size(),
                "containsPii", false,
                "maskingPolicy", "AGGREGATE_ONLY",
                "format", "CSV"));
        return new PhaseCsvFile(
                "b4-phase-distribution-" + LocalDate.now() + ".csv",
                csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Distribution distribution(int totalMonths, String selectedPhase) {
        Map<Integer, Long> byMonth = new LinkedHashMap<>();
        for (int month = 1; month <= totalMonths; month++) byMonth.put(month, 0L);
        for (Map<String, Object> row : mapper.selectAccountAgeMonthBuckets()) {
            int age = exactInt(row.get("monthAge"), "B4_DISTRIBUTION_UNRELIABLE");
            long count = exactLong(row.get("userCount"), "B4_DISTRIBUTION_UNRELIABLE");
            if (age < 0 || count < 0) throw new BizException(503, "B4_DISTRIBUTION_UNRELIABLE");
            int lifecycleMonth = Math.min(totalMonths, age + 1);
            byMonth.compute(lifecycleMonth, (ignored, current) -> current == null ? count : current + count);
        }
        Map<String, Long> byPhase = new LinkedHashMap<>();
        for (int index = 1; index <= 6; index++) byPhase.put("P" + index, 0L);
        List<Map<String, Object>> months = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : byMonth.entrySet()) {
            String mappedPhase = growthRhythmFacade.phaseForMonth(entry.getKey());
            if (!PHASE.matcher(mappedPhase).matches()) {
                throw new BizException(503, "B4_H1_PHASE_MAPPING_UNAVAILABLE");
            }
            byPhase.compute(mappedPhase, (ignored, current) -> current == null ? entry.getValue() : current + entry.getValue());
            months.add(linked(
                    "month", entry.getKey(),
                    "phase", mappedPhase,
                    "userCount", entry.getValue(),
                    "inScope", selectedPhase == null || selectedPhase.equals(mappedPhase)));
        }
        List<Map<String, Object>> phases = byPhase.entrySet().stream()
                .map(entry -> linked(
                        "phase", entry.getKey(),
                        "userCount", entry.getValue(),
                        "inScope", selectedPhase == null || selectedPhase.equals(entry.getKey())))
                .toList();
        long totalUsers = byMonth.values().stream().mapToLong(Long::longValue).sum();
        return new Distribution(phases, months, totalUsers);
    }

    private List<Map<String, Object>> dialRows(GrowthRhythmSnapshot current) {
        Map<String, Object> values = current.dials();
        if (values.size() != DIALS.size()) throw new BizException(503, "B4_H1_DIALS_UNAVAILABLE");
        return DIALS.stream().map(definition -> {
            Object value = values.get(definition.key());
            if (value == null) throw new BizException(503, "B4_H1_DIALS_UNAVAILABLE");
            return linked(
                    "key", definition.key(),
                    "label", definition.label(),
                    "currentValue", value,
                    "unit", definition.unit(),
                    "source", "H1 server-canonical@" + current.currentMonth(),
                    "v1Status", definition.v1Status(),
                    "v1Active", definition.v1Active(),
                    "adjustHref", "/growth/phase?dial=" + definition.key()
                            + "&phase=" + current.currentPhase() + "&from=B4");
        }).toList();
    }

    private Map<String, Object> nextPivot(GrowthRhythmSnapshot current) {
        if (current.currentMonth() >= current.totalMonths()) {
            return linked(
                    "atMonth", null,
                    "daysLeft", null,
                    "changes", List.of(),
                    "basis", "H1 currentMonth + phaseProgressPct",
                    "message", "已到节奏最后一个月");
        }
        int nextMonth = current.currentMonth() + 1;
        GrowthRhythmSnapshot next = requireSnapshot(growthRhythmFacade.snapshotAtMonth(nextMonth));
        List<String> changes = new ArrayList<>();
        Map<String, Object> before = current.dials();
        Map<String, Object> after = next.dials();
        for (DialDefinition dial : DIALS) {
            if (!sameValue(before.get(dial.key()), after.get(dial.key()))) {
                changes.add(dial.label() + " " + display(before.get(dial.key()), dial.unit())
                        + " → " + display(after.get(dial.key()), dial.unit()));
            }
        }
        int daysLeft = Math.max(0, new BigDecimal(100 - current.phaseProgressPct())
                .multiply(new BigDecimal("0.30"))
                .setScale(0, RoundingMode.CEILING)
                .intValue());
        return linked(
                "atMonth", nextMonth,
                "daysLeft", daysLeft,
                "changes", changes,
                "basis", "H1 currentMonth + phaseProgressPct",
                "message", "距月 " + nextMonth + " 拐点");
    }

    private GrowthRhythmSnapshot requireSnapshot(GrowthRhythmSnapshot snapshot) {
        if (snapshot == null
                || snapshot.totalMonths() < 1
                || snapshot.currentMonth() < 1
                || snapshot.currentMonth() > snapshot.totalMonths()
                || !PHASE.matcher(String.valueOf(snapshot.currentPhase())).matches()
                || snapshot.dials().size() != DIALS.size()) {
            throw new BizException(503, "B4_H1_RHYTHM_UNAVAILABLE");
        }
        return snapshot;
    }

    private Filter filter(String granularity, String month, String phase) {
        String normalizedGranularity = optional(granularity);
        normalizedGranularity = normalizedGranularity == null ? "PHASE" : normalizedGranularity.toUpperCase(Locale.ROOT);
        if (!GRANULARITIES.contains(normalizedGranularity)) {
            throw new BizException(422, "B4_GRANULARITY_INVALID");
        }
        String normalizedMonth = optional(month);
        if (normalizedMonth != null && !normalizedMonth.matches("\\d{1,2}")) {
            throw new BizException(422, "B4_MONTH_INVALID");
        }
        return new Filter(normalizedGranularity, normalizedMonth, phase(phase));
    }

    private String phase(String value) {
        String normalized = optional(value);
        if (normalized == null) return null;
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!PHASE.matcher(normalized).matches()) throw new BizException(422, "B4_PHASE_INVALID");
        return normalized;
    }

    private int parseMonth(String value, int totalMonths) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > totalMonths) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException ex) {
            throw new BizException(422, "B4_MONTH_INVALID");
        }
    }

    private List<Integer> monthOptions(int totalMonths) {
        List<Integer> result = new ArrayList<>();
        for (int month = 1; month <= totalMonths; month++) result.add(month);
        return result;
    }

    private List<Map<String, Object>> leverCombo(int month) {
        return switch (Math.max(1, Math.min(12, month))) {
            case 1, 2 -> List.of(
                    lever("获客", "新用户加成 + 任务加成", "降低首日上手成本"),
                    lever("推荐", "邀请加成", "放大可信社交扩散"));
            case 3, 4 -> List.of(
                    lever("留存", "邀请加成回落", "从补贴转向产品价值"),
                    lever("设备", "Pro 使用深度", "承接早期用户升级"));
            case 5, 6 -> List.of(
                    lever("复投", "复投加成 2×", "用成长奖励抵消衰减"),
                    lever("产品", "Pro v2 上市 FOMO", "提供新增购买理由"),
                    lever("网络", "Network Royalty Pool", "建立长期成长感"));
            case 7, 8 -> List.of(
                    lever("控流出", "双轨日封顶收紧", "降低佣金峰值"),
                    lever("提现", "冷却延长", "平滑月内资金压力"));
            case 9, 10 -> List.of(
                    lever("合规", "Compliance hold", "增强高压期审查"),
                    lever("提现", "惩罚费率 25%", "降低非必要流出"));
            default -> List.of(
                    lever("软收场", "提现惩罚费率 30%", "保护兑付覆盖"),
                    lever("合规", "Compliance hold", "维持增强审查"),
                    lever("留存", "核心权益维持", "承接长期用户"));
        };
    }

    private Map<String, Object> lever(String key, String label, String purpose) {
        return linked("key", key, "label", label, "purpose", purpose);
    }

    private static String h1AttributionHref(String selectedPhase, String fallbackPhase) {
        return "/growth/phase?phase=" + (selectedPhase == null ? fallbackPhase : selectedPhase)
                + "&view=attribution&from=B4";
    }

    private void audit(String action, String method, String result, Map<String, Object> detail) {
        auditLogService.record(auditRequest(action, method, result, detail));
    }

    private void auditRequired(String action, String method, String result, Map<String, Object> detail) {
        auditLogService.recordRequired(auditRequest(action, method, result, detail));
    }

    private AuditLogWriteRequest auditRequest(
            String action, String method, String result, Map<String, Object> detail) {
        return AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("B4_PHASE_OVERVIEW")
                .resourceId("B4")
                .actorType("ADMIN")
                .actorUsername(AdminActorResolver.resolve("system"))
                .method(method)
                .path("/api/admin/phase")
                .result(result)
                .riskLevel("LOW")
                .detail(detail)
                .build();
    }

    private static String optional(String value) {
        if (!StringUtils.hasText(value) || "ALL".equalsIgnoreCase(value.trim())) return null;
        return value.trim();
    }

    private static String valueOrAll(String value) {
        return value == null ? "ALL" : value;
    }

    private static boolean sameValue(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return new BigDecimal(String.valueOf(left)).compareTo(new BigDecimal(String.valueOf(right))) == 0;
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    private static String display(Object value, String unit) {
        return String.valueOf(value) + unit;
    }

    private static int exactInt(Object value, String error) {
        long result = exactLong(value, error);
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) throw new BizException(503, error);
        return (int) result;
    }

    private static long exactLong(Object value, String error) {
        try {
            return new BigDecimal(String.valueOf(value)).longValueExact();
        } catch (RuntimeException ex) {
            throw new BizException(503, error);
        }
    }

    private static String csv(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static Map<String, Object> linked(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return result;
    }

    private static DialDefinition dial(String key, String label, String unit, String v1Status, boolean v1Active) {
        return new DialDefinition(key, label, unit, v1Status, v1Active);
    }

    private record DialDefinition(String key, String label, String unit, String v1Status, boolean v1Active) {
    }

    private record Filter(String granularity, String month, String phase) {
    }

    private record Distribution(
            List<Map<String, Object>> phases,
            List<Map<String, Object>> months,
            long totalUsers) {
    }

    public record PhaseCsvFile(String fileName, byte[] body) {
    }
}

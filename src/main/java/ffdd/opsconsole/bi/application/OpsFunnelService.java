package ffdd.opsconsole.bi.application;

import ffdd.opsconsole.bi.domain.B3FunnelAnalytics;
import ffdd.opsconsole.bi.dto.B3FunnelViewRequest;
import ffdd.opsconsole.bi.mapper.BiReportMapper;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsFunnelService {
    private static final Pattern COHORT = Pattern.compile("\\d{4}-W(?:0[1-9]|[1-4]\\d|5[0-3])");
    private static final Pattern PHASE = Pattern.compile("P[1-6]");
    private static final Pattern REF = Pattern.compile("[\\p{L}\\p{N}._:-]{1,64}");
    private static final Set<String> STAGES = Set.of("register", "kyc", "purchase", "repurchase", "withdraw");
    private static final Set<String> GRANULARITIES = Set.of("WEEK", "MONTH");
    private static final Set<String> COMPARISONS = Set.of("PREVIOUS", "YEAR_OVER_YEAR", "CUSTOM");

    private final BiReportMapper mapper;
    private final AuditLogService auditLogService;

    public ApiResult<Map<String, Object>> overview(String cohort, String phase, String ref) {
        Filter filter = filter(cohort, phase, ref);
        Map<String, Object> result = mutable(B3FunnelAnalytics.calculate(
                mapper.selectB3EventFacts(), filter.cohort(), filter.phase(), filter.ref()));
        result.put("savedViews", mapper.selectB3Views(currentAdminId()));
        result.put("crossDomainLinks", List.of(
                linked("label", "L2 完整下钻", "href", "/analytics/funnel-cohort"),
                linked("label", "H1 Phase 归因", "href", "/growth/phase"),
                linked("label", "A4 事件治理", "href", "/platform/events")));
        audit("admin.funnel_viewed", "GET", "READ", linked(
                "cohort", filter.valueOrAll(filter.cohort()),
                "phase", filter.valueOrAll(filter.phase()),
                "ref", filter.valueOrAll(filter.ref())));
        return ApiResult.ok(result);
    }

    public ApiResult<Map<String, Object>> auxMetrics(String cohort, String phase, String ref) {
        Map<String, Object> overview = overview(cohort, phase, ref).getData();
        return ApiResult.ok(linked(
                "filters", overview.get("filters"),
                "auxMetrics", overview.get("auxMetrics"),
                "sources", overview.get("sources")));
    }

    public ApiResult<Map<String, Object>> cohortTrend(
            String stage, String cohortRange, String phase, String ref) {
        Filter filter = filter(null, phase, ref);
        String normalizedStage = normalizeStage(stage);
        int pointLimit = rangePointLimit(cohortRange);
        Map<String, Object> result = mutable(B3FunnelAnalytics.cohortTrend(
                mapper.selectB3EventFacts(), normalizedStage, filter.phase(), filter.ref()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points =
                (List<Map<String, Object>>) result.getOrDefault("points", List.of());
        if (points.size() > pointLimit) {
            result.put("points", new ArrayList<>(points.subList(points.size() - pointLimit, points.size())));
        }
        result.put("cohortRange", normalizeRange(cohortRange));
        return ApiResult.ok(result);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> saveView(B3FunnelViewRequest request) {
        if (request == null) throw new BizException(422, "B3_VIEW_REQUIRED");
        String name = normalizeName(request.name());
        Filter filter = filter(request.cohort(), request.phase(), request.ref());
        String granularity = enumValue(request.granularity(), GRANULARITIES, "WEEK", "B3_GRANULARITY_INVALID");
        String comparison = enumValue(request.comparison(), COMPARISONS, "PREVIOUS", "B3_COMPARISON_INVALID");
        long adminId = currentAdminId();
        Map<String, Object> existing = mapper.findB3View(adminId, name);
        if (existing != null && !existing.isEmpty()) {
            boolean same = same(existing.get("cohort"), persist(filter.cohort()))
                    && same(existing.get("phase"), persist(filter.phase()))
                    && same(existing.get("ref"), persist(filter.ref()))
                    && same(existing.get("granularity"), granularity)
                    && same(existing.get("comparison"), comparison);
            if (!same) throw new BizException(409, "B3_VIEW_NAME_CONFLICT");
            return ApiResult.ok(linked(
                    "saved", existing,
                    "replayed", true,
                    "message", "同名且相同切片的视图已存在"));
        }
        mapper.insertB3View(
                adminId,
                name,
                persist(filter.cohort()),
                persist(filter.phase()),
                persist(filter.ref()),
                granularity,
                comparison);
        Map<String, Object> saved = mapper.findB3View(adminId, name);
        auditRequired("admin.funnel_view_saved", "POST", "SUCCESS", linked(
                "name", name,
                "cohort", persist(filter.cohort()),
                "phase", persist(filter.phase()),
                "ref", persist(filter.ref()),
                "granularity", granularity,
                "comparison", comparison));
        return ApiResult.ok(linked("saved", saved, "replayed", false));
    }

    public FunnelCsvFile export(String cohort, String phase, String ref) {
        Filter filter = filter(cohort, phase, ref);
        Map<String, Object> dashboard = B3FunnelAnalytics.calculate(
                mapper.selectB3EventFacts(), filter.cohort(), filter.phase(), filter.ref());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stages =
                (List<Map<String, Object>>) dashboard.getOrDefault("stages", List.of());
        StringBuilder csv = new StringBuilder("\uFEFFstage,event,lifecycle,distinct_users,previous_users,cvr_from_prev,cohort,phase,ref\r\n");
        for (Map<String, Object> stage : stages) {
            csv.append(csv(stage.get("stage"))).append(',')
                    .append(csv(stage.get("event"))).append(',')
                    .append(csv(stage.get("lifecycleLabel"))).append(',')
                    .append(csv(stage.get("distinctUsers"))).append(',')
                    .append(csv(stage.get("previousUsers"))).append(',')
                    .append(csv(stage.get("cvrFromPrev"))).append(',')
                    .append(csv(filter.valueOrAll(filter.cohort()))).append(',')
                    .append(csv(filter.valueOrAll(filter.phase()))).append(',')
                    .append(csv(filter.valueOrAll(filter.ref()))).append("\r\n");
        }
        auditRequired("admin.report_exported", "GET", "SUCCESS", linked(
                "exportType", "B3_FUNNEL_COHORT",
                "scope", linked(
                        "cohort", filter.valueOrAll(filter.cohort()),
                        "phase", filter.valueOrAll(filter.phase()),
                        "ref", filter.valueOrAll(filter.ref())),
                "fields", "stage,event,lifecycle,distinct_users,previous_users,cvr_from_prev,cohort,phase,ref",
                "rowCount", stages.size(),
                "containsPii", false,
                "maskingPolicy", "NONE",
                "format", "CSV"));
        return new FunnelCsvFile(
                "b3-funnel-" + LocalDateTime.now().toLocalDate() + ".csv",
                csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Filter filter(String cohort, String phase, String ref) {
        String normalizedCohort = optional(cohort);
        String normalizedPhase = optional(phase);
        String normalizedRef = optional(ref);
        if (normalizedCohort != null && !COHORT.matcher(normalizedCohort).matches()) {
            throw new BizException(422, "B3_COHORT_INVALID");
        }
        if (normalizedPhase != null) {
            normalizedPhase = normalizedPhase.toUpperCase(Locale.ROOT);
            if (!PHASE.matcher(normalizedPhase).matches()) throw new BizException(422, "B3_PHASE_INVALID");
        }
        if (normalizedRef != null && !REF.matcher(normalizedRef).matches()) {
            throw new BizException(422, "B3_REF_INVALID");
        }
        return new Filter(normalizedCohort, normalizedPhase, normalizedRef);
    }

    private String normalizeStage(String value) {
        String normalized = optional(value);
        if (normalized == null) return "purchase";
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (!STAGES.contains(normalized)) throw new BizException(404, "B3_STAGE_NOT_FOUND");
        return normalized;
    }

    private int rangePointLimit(String value) {
        return switch (normalizeRange(value)) {
            case "4w" -> 4;
            case "8w" -> 8;
            case "13w" -> 13;
            case "26w" -> 26;
            case "52w" -> 52;
            default -> throw new BizException(422, "B3_COHORT_RANGE_INVALID");
        };
    }

    private String normalizeRange(String value) {
        String normalized = optional(value);
        return normalized == null ? "13w" : normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizeName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 80
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new BizException(422, "B3_VIEW_NAME_INVALID");
        }
        return normalized;
    }

    private String enumValue(String value, Set<String> allowed, String fallback, String error) {
        String normalized = optional(value);
        if (normalized == null) return fallback;
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new BizException(422, error);
        return normalized;
    }

    private long currentAdminId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BizException(401, "ADMIN_AUTH_REQUIRED");
        }
        try {
            return Long.parseLong(String.valueOf(authentication.getPrincipal()));
        } catch (RuntimeException ex) {
            throw new BizException(401, "ADMIN_AUTH_REQUIRED");
        }
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
                .resourceType("B3_FUNNEL")
                .resourceId("B3")
                .actorType("ADMIN")
                .actorUsername(AdminActorResolver.resolve("system"))
                .method(method)
                .path("/api/admin/funnel")
                .result(result)
                .riskLevel("LOW")
                .detail(detail)
                .build();
    }

    private static String optional(String value) {
        if (!StringUtils.hasText(value) || "ALL".equalsIgnoreCase(value.trim())) return null;
        return value.trim();
    }

    private static String persist(String value) {
        return value == null ? "ALL" : value;
    }

    private static boolean same(Object left, Object right) {
        return String.valueOf(left).equals(String.valueOf(right));
    }

    private static String csv(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static Map<String, Object> mutable(Map<String, Object> source) {
        return new LinkedHashMap<>(source);
    }

    private static Map<String, Object> linked(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return result;
    }

    private record Filter(String cohort, String phase, String ref) {
        String valueOrAll(String value) {
            return value == null ? "ALL" : value;
        }
    }

    public record FunnelCsvFile(String fileName, byte[] body) {
    }
}

package ffdd.opsconsole.team.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.team.dto.TeamCommissionConfigUpdateRequest;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.util.StringUtils;

@ApplicationService
public class OpsTeamService {
    private static final Set<String> ACTIVE_KEYS = Set.of(
            "directRoyaltyPct",
            "networkRoyaltyPct",
            "binaryPairRatePct",
            "maxCombinedOutflowPct",
            "minPayoutUsdt",
            "rankWindowDays",
            "hardwareQuotaPerRank");

    private final PlatformConfigFacade configFacade;
    private final TreasuryCoverageFacade coverageFacade;
    private final AuditLogService auditLogService;

    public OpsTeamService(
            PlatformConfigFacade configFacade,
            TreasuryCoverageFacade coverageFacade,
            AuditLogService auditLogService) {
        this.configFacade = configFacade;
        this.coverageFacade = coverageFacade;
        this.auditLogService = auditLogService;
    }

    public ApiResult<Map<String, Object>> overview() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "F");
        response.put("commissionPolicy", commissionPolicy());
        response.put("rankLadder", rankLadder());
        response.put("quotaPolicy", quotaPolicy());
        response.put("payoutGuardrails", guardrails());
        response.put("sunsetExclusions", List.of("Premium rank unlock", "Points commission payout", "NEX v2 lock reward"));
        response.put("sources", List.of("nx_config_item:team.*", "B1 treasury coverage facade"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> commissions() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "F2_F3");
        response.put("commissionPolicy", commissionPolicy());
        response.put("rateTiers", rateTiers());
        response.put("payoutAssetPolicy", Map.of(
                "primary", "USDT",
                "secondary", "NEX",
                "points", "SUNSET_HISTORY_ONLY"));
        response.put("guardrails", guardrails());
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> ranks() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "F1");
        response.put("rankLadder", rankLadder());
        response.put("promotionWindowDays", configDecimal("team.rank_window_days", new BigDecimal("30")).intValue());
        response.put("quotaPolicy", quotaPolicy());
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateConfig(String idempotencyKey, TeamCommissionConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String key = normalizeKey(request.key());
        BigDecimal oldValue = currentValue(key);
        BigDecimal newValue = normalizeValue(key, request.value());
        if (loosensPayoutControl(key, oldValue, newValue) && coverageBelowRedline()) {
            return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(), OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
        String configKey = configKey(key);
        configFacade.upsertAdminValue(configKey, newValue.toPlainString(), "NUMBER", "team", "F domain team commission policy");
        audit("F_TEAM_POLICY_CHANGED", configKey, request.operator(), Map.of(
                "key", key,
                "oldValue", oldValue,
                "newValue", newValue,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = overview().getData();
        response.put("updated", Map.of("key", key, "configKey", configKey, "oldValue", oldValue, "newValue", newValue));
        return ApiResult.ok(response);
    }

    private Map<String, Object> commissionPolicy() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("directRoyaltyPct", percent(configDecimal("team.direct_royalty_pct", new BigDecimal("8"))));
        policy.put("networkRoyaltyPct", percent(configDecimal("team.network_royalty_pct", new BigDecimal("2"))));
        policy.put("binaryPairRatePct", percent(configDecimal("team.binary_pair_rate_pct", new BigDecimal("1.5"))));
        policy.put("maxCombinedOutflowPct", percent(configDecimal("team.max_combined_outflow_pct", new BigDecimal("25"))));
        policy.put("minPayoutUsdt", configDecimal("team.min_payout_usdt", new BigDecimal("20")));
        policy.put("settlementStatus", "single-process-transaction");
        return policy;
    }

    private List<Map<String, Object>> rankLadder() {
        int window = configDecimal("team.rank_window_days", new BigDecimal("30")).intValue();
        return List.of(
                rank("V1", "starter", new BigDecimal("0"), new BigDecimal("0"), window),
                rank("V3", "builder", new BigDecimal("5000"), new BigDecimal("3"), window),
                rank("V6", "scale", new BigDecimal("50000"), new BigDecimal("6"), window),
                rank("V9", "partner", new BigDecimal("250000"), new BigDecimal("9"), window));
    }

    private Map<String, Object> rank(String rank, String label, BigDecimal minTeamGmvUsdt, BigDecimal maxDepth, int windowDays) {
        return Map.of(
                "rank", rank,
                "label", label,
                "minTeamGmvUsdt", minTeamGmvUsdt,
                "commissionDepth", maxDepth,
                "windowDays", windowDays);
    }

    private Map<String, Object> quotaPolicy() {
        return Map.of(
                "hardwareQuotaPerRank", configDecimal("team.hardware_quota_per_rank", new BigDecimal("5")).intValue(),
                "quotaOwner", "F5",
                "deviceReleasePacing", "mirrors H1 but does not own H1 phase dials",
                "b1PrecheckOnLoosening", true);
    }

    private List<Map<String, Object>> rateTiers() {
        return List.of(
                tier("Starter", "30d team GMV < 5k USDT", configDecimal("team.direct_royalty_pct", new BigDecimal("8"))),
                tier("Builder", "30d team GMV 5k-50k USDT", configDecimal("team.direct_royalty_pct", new BigDecimal("8")).add(new BigDecimal("2"))),
                tier("Scale", "30d team GMV >= 50k USDT", configDecimal("team.direct_royalty_pct", new BigDecimal("8")).add(new BigDecimal("5"))));
    }

    private Map<String, Object> tier(String name, String requirement, BigDecimal ratePct) {
        return Map.of("name", name, "requirement", requirement, "ratePct", ratePct);
    }

    private List<String> guardrails() {
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        return List.of(
                "B1 coverageRatio=" + coverage.coverageRatio() + ", redline=" + coverage.redlinePct(),
                "payout-loosening writes require Idempotency-Key and confirm-with-reason",
                "team commission can request D ledger posting but cannot write wallet mapper directly");
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

    private String normalizeKey(String key) {
        String normalized = requireText(key, "TEAM_CONFIG_KEY_REQUIRED");
        String lower = normalized.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        if (lower.contains("premium") || lower.contains("points") || lower.contains("nexv2")) {
            throw new IllegalArgumentException("Sunset capability is not an active F domain key");
        }
        if (!ACTIVE_KEYS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported F team policy key");
        }
        return normalized;
    }

    private BigDecimal currentValue(String key) {
        return switch (key) {
            case "directRoyaltyPct" -> configDecimal("team.direct_royalty_pct", new BigDecimal("8"));
            case "networkRoyaltyPct" -> configDecimal("team.network_royalty_pct", new BigDecimal("2"));
            case "binaryPairRatePct" -> configDecimal("team.binary_pair_rate_pct", new BigDecimal("1.5"));
            case "maxCombinedOutflowPct" -> configDecimal("team.max_combined_outflow_pct", new BigDecimal("25"));
            case "minPayoutUsdt" -> configDecimal("team.min_payout_usdt", new BigDecimal("20"));
            case "rankWindowDays" -> configDecimal("team.rank_window_days", new BigDecimal("30"));
            case "hardwareQuotaPerRank" -> configDecimal("team.hardware_quota_per_rank", new BigDecimal("5"));
            default -> throw new IllegalArgumentException("Unsupported F team policy key");
        };
    }

    private String configKey(String key) {
        return switch (key) {
            case "directRoyaltyPct" -> "team.direct_royalty_pct";
            case "networkRoyaltyPct" -> "team.network_royalty_pct";
            case "binaryPairRatePct" -> "team.binary_pair_rate_pct";
            case "maxCombinedOutflowPct" -> "team.max_combined_outflow_pct";
            case "minPayoutUsdt" -> "team.min_payout_usdt";
            case "rankWindowDays" -> "team.rank_window_days";
            case "hardwareQuotaPerRank" -> "team.hardware_quota_per_rank";
            default -> throw new IllegalArgumentException("Unsupported F team policy key");
        };
    }

    private BigDecimal normalizeValue(String key, String raw) {
        BigDecimal value = parseDecimal(raw);
        return switch (key) {
            case "directRoyaltyPct", "networkRoyaltyPct", "binaryPairRatePct" -> bounded(value, BigDecimal.ZERO, new BigDecimal("30"));
            case "maxCombinedOutflowPct" -> bounded(value, BigDecimal.ZERO, new BigDecimal("60"));
            case "minPayoutUsdt" -> bounded(value, BigDecimal.ZERO, new BigDecimal("1000"));
            case "rankWindowDays" -> whole(value, 1, 365);
            case "hardwareQuotaPerRank" -> whole(value, 0, 100000);
            default -> throw new IllegalArgumentException("Unsupported F team policy key");
        };
    }

    private boolean loosensPayoutControl(String key, BigDecimal oldValue, BigDecimal newValue) {
        return switch (key) {
            case "directRoyaltyPct", "networkRoyaltyPct", "binaryPairRatePct", "maxCombinedOutflowPct", "hardwareQuotaPerRank" ->
                    newValue.compareTo(oldValue) > 0;
            case "minPayoutUsdt", "rankWindowDays" -> newValue.compareTo(oldValue) < 0;
            default -> false;
        };
    }

    private boolean coverageBelowRedline() {
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        return coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0;
    }

    private BigDecimal configDecimal(String key, BigDecimal fallback) {
        return configFacade.activeValue(key)
                .flatMap(value -> Optional.ofNullable(parseDecimal(value, fallback)))
                .orElse(fallback);
    }

    private BigDecimal parseDecimal(String raw) {
        return parseDecimal(raw, null);
    }

    private BigDecimal parseDecimal(String raw, BigDecimal fallback) {
        if (!StringUtils.hasText(raw)) {
            if (fallback != null) {
                return fallback;
            }
            throw new IllegalArgumentException("Numeric value is required");
        }
        try {
            return new BigDecimal(raw.trim().replace("%", "").replace(",", ""));
        } catch (NumberFormatException ex) {
            if (fallback != null) {
                return fallback;
            }
            throw new IllegalArgumentException("Numeric value is invalid", ex);
        }
    }

    private BigDecimal bounded(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException("Numeric value is out of range");
        }
        return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private BigDecimal whole(BigDecimal value, int min, int max) {
        int whole = value.setScale(0, RoundingMode.DOWN).intValue();
        if (whole < min || whole > max) {
            throw new IllegalArgumentException("Whole number is out of range");
        }
        return BigDecimal.valueOf(whole);
    }

    private BigDecimal percent(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private void audit(String action, String resourceId, String operator, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("TEAM_POLICY")
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(StringUtils.hasText(operator) ? operator.trim() : "system")
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }
}

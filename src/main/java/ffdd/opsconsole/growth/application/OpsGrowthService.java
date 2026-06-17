package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.growth.dto.GrowthConfigUpdateRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
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
public class OpsGrowthService {
    private static final String PHASE_CONFIG_KEY = "platform.phase.config";
    private static final String CHECKIN_REWARD_KEY = "growth.checkin.reward_nex";
    private static final String CHECKIN_STREAK_BONUS_KEY = "growth.checkin.streak_bonus_nex";
    private static final String CHECKIN_LUCKY_MULTIPLIER_KEY = "growth.checkin.lucky_multiplier_max";
    private static final String WITHDRAW_MIN_BALANCE_KEY = "growth.withdraw_nex_gate.min_balance_nex";
    private static final String WITHDRAW_HOLD_DAYS_KEY = "growth.withdraw_nex_gate.hold_days";
    private static final String WITHDRAW_MIN_BALANCE_MIRROR_KEY = "withdrawal.nex_gate.min_balance_nex";
    private static final String WITHDRAW_HOLD_DAYS_MIRROR_KEY = "withdrawal.nex_gate.hold_days";
    private static final Set<String> RETIRED_KEYS = Set.of(
            "withdrawPointsRatio",
            "pointsExchangeRate",
            "premiumUnlock",
            "premiumTrial",
            "nexV2Unlock",
            "nexV2LockReward");

    private final PlatformConfigFacade configFacade;
    private final TreasuryCoverageFacade coverageFacade;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public OpsGrowthService(
            PlatformConfigFacade configFacade,
            TreasuryCoverageFacade coverageFacade,
            AuditLogService auditLogService,
            ObjectMapper objectMapper) {
        this.configFacade = configFacade;
        this.coverageFacade = coverageFacade;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    public ApiResult<Map<String, Object>> phases() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "H1");
        response.put("currentPhase", configFacade.activeValue("growth.phase.current").orElse("P1"));
        response.put("dialCount", 8);
        response.put("phaseConfig", phaseConfig());
        response.put("activeDials", activeDials());
        response.put("withdrawNexGate", withdrawGate().getData());
        response.put("retiredDials", List.copyOf(RETIRED_KEYS));
        response.put("sunsetExclusions", List.of("Premium", "NEX v2", "Points"));
        response.put("sources", List.of("nx_config_item:" + PHASE_CONFIG_KEY, "nx_config_item:growth.*"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updatePhaseDial(
            String idempotencyKey,
            String dialKey,
            GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedKey = normalizePhaseDialKey(dialKey);
        if (RETIRED_KEYS.contains(normalizedKey)) {
            return retiredFeature();
        }
        String configKey = phaseDialConfigKey(normalizedKey);
        BigDecimal oldValue = configDecimal(configKey, defaultPhaseDialValue(normalizedKey));
        BigDecimal newValue = normalizePhaseDialValue(normalizedKey, request.value());
        if (amplifiesPhasePayout(normalizedKey, oldValue, newValue) && coverageBelowRedline()) {
            return coverageRedline();
        }
        configFacade.upsertAdminValue(configKey, newValue.toPlainString(), "NUMBER", "growth", "H1 phase dial");
        if ("withdrawNexMinBalance".equals(normalizedKey)) {
            configFacade.upsertAdminValue(WITHDRAW_MIN_BALANCE_KEY, newValue.toPlainString(), "NUMBER", "growth", "H1 withdraw NEX gate");
            configFacade.upsertAdminValue(WITHDRAW_MIN_BALANCE_MIRROR_KEY, newValue.toPlainString(), "NUMBER", "wallet", "D5 mirror of H1 withdraw NEX gate");
        }
        if ("withdrawNexHoldDays".equals(normalizedKey)) {
            configFacade.upsertAdminValue(WITHDRAW_HOLD_DAYS_KEY, newValue.toPlainString(), "NUMBER", "growth", "H1 withdraw NEX gate");
            configFacade.upsertAdminValue(WITHDRAW_HOLD_DAYS_MIRROR_KEY, newValue.toPlainString(), "NUMBER", "wallet", "D5 mirror of H1 withdraw NEX gate");
        }
        audit("H1_PHASE_DIAL_CHANGED", "GROWTH_PHASE_DIAL", configKey, request.operator(), Map.of(
                "key", normalizedKey,
                "oldValue", oldValue,
                "newValue", newValue,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return phases();
    }

    public ApiResult<Map<String, Object>> checkIn() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "H5");
        response.put("rewardAsset", "NEX");
        response.put("baseRewardNex", configDecimal(CHECKIN_REWARD_KEY, new BigDecimal("0.25")));
        response.put("streakBonusNex", configDecimal(CHECKIN_STREAK_BONUS_KEY, BigDecimal.ONE));
        response.put("luckyMultiplierMax", configDecimal(CHECKIN_LUCKY_MULTIPLIER_KEY, new BigDecimal("2")));
        response.put("pointsSystemStatus", "SUNSET_HISTORY_ONLY");
        response.put("disabledOutputs", List.of("Points ledger writes", "points redemption", "premium trial points"));
        response.put("sources", List.of("nx_config_item:" + CHECKIN_REWARD_KEY, "nx_config_item:" + CHECKIN_STREAK_BONUS_KEY));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateCheckIn(String idempotencyKey, GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String key = normalizeCheckInKey(request.key());
        BigDecimal oldValue = currentCheckInValue(key);
        BigDecimal newValue = normalizeCheckInValue(key, request.value());
        if (newValue.compareTo(oldValue) > 0 && coverageBelowRedline()) {
            return coverageRedline();
        }
        String configKey = checkInConfigKey(key);
        configFacade.upsertAdminValue(configKey, newValue.toPlainString(), "NUMBER", "growth", "H5 check-in NEX reward");
        audit("H5_CHECKIN_NEX_CONFIG_CHANGED", "CHECKIN_NEX_REWARD", configKey, request.operator(), Map.of(
                "key", key,
                "oldValue", oldValue,
                "newValue", newValue,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = checkIn().getData();
        response.put("updated", Map.of("key", key, "configKey", configKey, "oldValue", oldValue, "newValue", newValue));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> withdrawGate() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "D5_H1");
        response.put("asset", "NEX");
        response.put("minBalanceNex", configDecimal(WITHDRAW_MIN_BALANCE_KEY, new BigDecimal("100")));
        response.put("holdDays", configDecimal(WITHDRAW_HOLD_DAYS_KEY, new BigDecimal("7")).intValue());
        response.put("canonicalOwner", "H1");
        response.put("mirrors", List.of("D5 withdrawal parameters", "D2 withdrawal queue readonly"));
        response.put("retiredReplacement", "withdrawPointsRatio -> withdrawNexGate");
        response.put("sources", List.of("nx_config_item:" + WITHDRAW_MIN_BALANCE_KEY, "nx_config_item:" + WITHDRAW_HOLD_DAYS_KEY));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateWithdrawGate(String idempotencyKey, GrowthConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String key = normalizeWithdrawGateKey(request.key());
        BigDecimal oldValue = currentWithdrawGateValue(key);
        BigDecimal newValue = normalizeWithdrawGateValue(key, request.value());
        if (loosensWithdrawGate(key, oldValue, newValue) && coverageBelowRedline()) {
            return coverageRedline();
        }
        String configKey = withdrawGateConfigKey(key);
        String mirrorKey = withdrawGateMirrorKey(key);
        configFacade.upsertAdminValue(configKey, newValue.toPlainString(), "NUMBER", "growth", "H1 withdraw NEX gate");
        configFacade.upsertAdminValue(mirrorKey, newValue.toPlainString(), "NUMBER", "wallet", "D5 mirror of H1 withdraw NEX gate");
        audit("H1_WITHDRAW_NEX_GATE_CHANGED", "WITHDRAW_NEX_GATE", configKey, request.operator(), Map.of(
                "key", key,
                "mirrorKey", mirrorKey,
                "oldValue", oldValue,
                "newValue", newValue,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = withdrawGate().getData();
        response.put("updated", Map.of("key", key, "configKey", configKey, "mirrorKey", mirrorKey, "oldValue", oldValue, "newValue", newValue));
        return ApiResult.ok(response);
    }

    private Map<String, Object> phaseConfig() {
        Optional<String> raw = configFacade.activeValue(PHASE_CONFIG_KEY).filter(StringUtils::hasText);
        if (raw.isEmpty()) {
            Map<String, Object> defaults = new LinkedHashMap<>();
            defaults.put("calendar", "12_MONTH");
            defaults.put("activePhase", "P1");
            defaults.put("activeDialCount", 8);
            return defaults;
        }
        try {
            return objectMapper.readValue(raw.get(), new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException ex) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("calendar", "12_MONTH");
            fallback.put("activePhase", "P1");
            fallback.put("activeDialCount", 8);
            fallback.put("invalidSource", PHASE_CONFIG_KEY);
            return fallback;
        }
    }

    private Map<String, Object> activeDials() {
        Map<String, Object> dials = new LinkedHashMap<>();
        dials.put("inviteRewardMultiplier", configDecimal("growth.phase.invite_reward_multiplier", BigDecimal.ONE));
        dials.put("questRewardMultiplier", configDecimal("growth.phase.quest_reward_multiplier", BigDecimal.ONE));
        dials.put("trialOffsetCapUsdt", configDecimal("growth.phase.trial_offset_cap_usdt", new BigDecimal("50")));
        dials.put("deviceReleasePacingPct", percent(configDecimal("growth.phase.device_release_pacing_pct", new BigDecimal("0.60"))));
        dials.put("commissionTighteningPct", percent(configDecimal("growth.phase.commission_tightening_pct", new BigDecimal("0.10"))));
        dials.put("campaignRewardNex", configDecimal("growth.phase.campaign_reward_nex", new BigDecimal("10")));
        dials.put("withdrawNexMinBalance", configDecimal(WITHDRAW_MIN_BALANCE_KEY, new BigDecimal("100")));
        dials.put("withdrawNexHoldDays", configDecimal(WITHDRAW_HOLD_DAYS_KEY, new BigDecimal("7")).intValue());
        return dials;
    }

    private ApiResult<Map<String, Object>> requireCommand(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason)) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private ApiResult<Map<String, Object>> retiredFeature() {
        return ApiResult.fail(OpsErrorCode.PHASE_PARAM_READONLY.httpStatus(), OpsErrorCode.PHASE_PARAM_READONLY.name());
    }

    private ApiResult<Map<String, Object>> coverageRedline() {
        return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(), OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
    }

    private boolean coverageBelowRedline() {
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        return coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0;
    }

    private String normalizePhaseDialKey(String key) {
        String normalized = requireText(key, "Phase dial key is required");
        if (RETIRED_KEYS.contains(normalized)) {
            return normalized;
        }
        try {
            phaseDialConfigKey(normalized);
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported H1 phase dial", ex);
        }
    }

    private String phaseDialConfigKey(String key) {
        return switch (key) {
            case "inviteRewardMultiplier" -> "growth.phase.invite_reward_multiplier";
            case "questRewardMultiplier" -> "growth.phase.quest_reward_multiplier";
            case "trialOffsetCapUsdt" -> "growth.phase.trial_offset_cap_usdt";
            case "deviceReleasePacingPct" -> "growth.phase.device_release_pacing_pct";
            case "commissionTighteningPct" -> "growth.phase.commission_tightening_pct";
            case "campaignRewardNex" -> "growth.phase.campaign_reward_nex";
            case "withdrawNexMinBalance" -> WITHDRAW_MIN_BALANCE_KEY;
            case "withdrawNexHoldDays" -> WITHDRAW_HOLD_DAYS_KEY;
            default -> throw new IllegalArgumentException("Unsupported H1 phase dial");
        };
    }

    private BigDecimal defaultPhaseDialValue(String key) {
        return switch (key) {
            case "trialOffsetCapUsdt" -> new BigDecimal("50");
            case "deviceReleasePacingPct" -> new BigDecimal("0.60");
            case "commissionTighteningPct" -> new BigDecimal("0.10");
            case "campaignRewardNex" -> new BigDecimal("10");
            case "withdrawNexMinBalance" -> new BigDecimal("100");
            case "withdrawNexHoldDays" -> new BigDecimal("7");
            default -> BigDecimal.ONE;
        };
    }

    private BigDecimal normalizePhaseDialValue(String key, String raw) {
        BigDecimal value = parseDecimal(raw);
        return switch (key) {
            case "inviteRewardMultiplier", "questRewardMultiplier" -> bounded(value, new BigDecimal("0.10"), new BigDecimal("4"));
            case "trialOffsetCapUsdt" -> bounded(value, BigDecimal.ZERO, new BigDecimal("500"));
            case "deviceReleasePacingPct", "commissionTighteningPct" -> bounded(ratio(value), BigDecimal.ZERO, BigDecimal.ONE);
            case "campaignRewardNex" -> bounded(value, BigDecimal.ZERO, new BigDecimal("100000"));
            case "withdrawNexMinBalance" -> bounded(value, BigDecimal.ZERO, new BigDecimal("1000000"));
            case "withdrawNexHoldDays" -> wholeDays(value, 0, 365);
            default -> throw new IllegalArgumentException("Unsupported H1 phase dial");
        };
    }

    private boolean amplifiesPhasePayout(String key, BigDecimal oldValue, BigDecimal newValue) {
        return switch (key) {
            case "inviteRewardMultiplier", "questRewardMultiplier", "trialOffsetCapUsdt", "deviceReleasePacingPct", "campaignRewardNex" ->
                    newValue.compareTo(oldValue) > 0;
            case "commissionTighteningPct", "withdrawNexMinBalance", "withdrawNexHoldDays" -> newValue.compareTo(oldValue) < 0;
            default -> false;
        };
    }

    private String normalizeCheckInKey(String key) {
        String normalized = requireText(key, "Check-in key is required");
        if (normalized.toLowerCase(Locale.ROOT).contains("point")) {
            throw new IllegalArgumentException("Points system is sunset");
        }
        return switch (normalized) {
            case "baseRewardNex", "streakBonusNex", "luckyMultiplierMax" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported H5 check-in key");
        };
    }

    private String checkInConfigKey(String key) {
        return switch (key) {
            case "baseRewardNex" -> CHECKIN_REWARD_KEY;
            case "streakBonusNex" -> CHECKIN_STREAK_BONUS_KEY;
            case "luckyMultiplierMax" -> CHECKIN_LUCKY_MULTIPLIER_KEY;
            default -> throw new IllegalArgumentException("Unsupported H5 check-in key");
        };
    }

    private BigDecimal currentCheckInValue(String key) {
        return switch (key) {
            case "baseRewardNex" -> configDecimal(CHECKIN_REWARD_KEY, new BigDecimal("0.25"));
            case "streakBonusNex" -> configDecimal(CHECKIN_STREAK_BONUS_KEY, BigDecimal.ONE);
            case "luckyMultiplierMax" -> configDecimal(CHECKIN_LUCKY_MULTIPLIER_KEY, new BigDecimal("2"));
            default -> throw new IllegalArgumentException("Unsupported H5 check-in key");
        };
    }

    private BigDecimal normalizeCheckInValue(String key, String raw) {
        BigDecimal value = parseDecimal(raw);
        return switch (key) {
            case "baseRewardNex" -> bounded(value, BigDecimal.ZERO, new BigDecimal("100"));
            case "streakBonusNex" -> bounded(value, BigDecimal.ZERO, new BigDecimal("1000"));
            case "luckyMultiplierMax" -> bounded(value, BigDecimal.ONE, new BigDecimal("10"));
            default -> throw new IllegalArgumentException("Unsupported H5 check-in key");
        };
    }

    private String normalizeWithdrawGateKey(String key) {
        String normalized = requireText(key, "Withdraw gate key is required");
        if (normalized.toLowerCase(Locale.ROOT).contains("point")) {
            return "withdrawPointsRatio";
        }
        if ("withdrawPointsRatio".equals(normalized)) {
            throw new IllegalArgumentException("Points withdrawal gate is sunset");
        }
        return switch (normalized) {
            case "minBalanceNex", "holdDays" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported H1 withdraw gate key");
        };
    }

    private String withdrawGateConfigKey(String key) {
        return switch (key) {
            case "minBalanceNex" -> WITHDRAW_MIN_BALANCE_KEY;
            case "holdDays" -> WITHDRAW_HOLD_DAYS_KEY;
            default -> throw new IllegalArgumentException("Unsupported H1 withdraw gate key");
        };
    }

    private String withdrawGateMirrorKey(String key) {
        return switch (key) {
            case "minBalanceNex" -> WITHDRAW_MIN_BALANCE_MIRROR_KEY;
            case "holdDays" -> WITHDRAW_HOLD_DAYS_MIRROR_KEY;
            default -> throw new IllegalArgumentException("Unsupported H1 withdraw gate key");
        };
    }

    private BigDecimal currentWithdrawGateValue(String key) {
        return switch (key) {
            case "minBalanceNex" -> configDecimal(WITHDRAW_MIN_BALANCE_KEY, new BigDecimal("100"));
            case "holdDays" -> configDecimal(WITHDRAW_HOLD_DAYS_KEY, new BigDecimal("7"));
            default -> throw new IllegalArgumentException("Unsupported H1 withdraw gate key");
        };
    }

    private BigDecimal normalizeWithdrawGateValue(String key, String raw) {
        BigDecimal value = parseDecimal(raw);
        return switch (key) {
            case "minBalanceNex" -> bounded(value, BigDecimal.ZERO, new BigDecimal("1000000"));
            case "holdDays" -> wholeDays(value, 0, 365);
            default -> throw new IllegalArgumentException("Unsupported H1 withdraw gate key");
        };
    }

    private boolean loosensWithdrawGate(String key, BigDecimal oldValue, BigDecimal newValue) {
        return switch (key) {
            case "minBalanceNex", "holdDays" -> newValue.compareTo(oldValue) < 0;
            default -> false;
        };
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

    private BigDecimal ratio(BigDecimal raw) {
        return raw.compareTo(BigDecimal.ONE) > 0
                ? raw.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                : raw;
    }

    private BigDecimal bounded(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException("Numeric value is out of range");
        }
        return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private BigDecimal wholeDays(BigDecimal value, int min, int max) {
        int days = value.setScale(0, RoundingMode.DOWN).intValue();
        if (days < min || days > max) {
            throw new IllegalArgumentException("Days value is out of range");
        }
        return BigDecimal.valueOf(days);
    }

    private BigDecimal percent(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private void audit(String action, String resourceType, String resourceId, String operator, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
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

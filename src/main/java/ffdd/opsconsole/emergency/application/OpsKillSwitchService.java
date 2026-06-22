package ffdd.opsconsole.emergency.application;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.emergency.dto.EmergencyDisableRequest;
import ffdd.opsconsole.emergency.dto.KillSwitchToggleRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsKillSwitchService {
    private static final List<String> ACTIVE_GATES = List.of("withdraw", "staking", "genesis", "exchange", "trial");
    private static final Set<String> RETIRED_GATES = Set.of("premium", "nexv2", "nex-v2", "points");

    private final PlatformConfigFacade configFacade;
    private final TreasuryCoverageFacade coverageFacade;
    private final AuditLogService auditLogService;

    public ApiResult<Map<String, Object>> matrix() {
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "J1");
        response.put("activeGateCount", ACTIVE_GATES.size());
        response.put("activeGates", ACTIVE_GATES.stream().map(this::gateView).toList());
        response.put("retiredGates", retiredGates());
        response.put("coverage", Map.of(
                "coverageRatio", coverage.coverageRatio(),
                "redlinePct", coverage.redlinePct(),
                "recoveryAllowed", coverage.coverageRatio().compareTo(coverage.redlinePct()) >= 0));
        response.put("executionModel", "single confirm-with-reason plus broadcast and A2 audit");
        response.put("sources", List.of("nx_config_item:killswitch.*", "B1 treasury coverage facade"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> toggle(String key, String idempotencyKey, KillSwitchToggleRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedKey = normalizeGate(key);
        if (isRetired(normalizedKey)) {
            return retiredFeature();
        }
        boolean enable = parseEnabled(request.enabled());
        boolean before = gateEnabled(normalizedKey);
        if (enable && !before && coverageBelowRedline()) {
            return coverageRedline();
        }
        writeGate(normalizedKey, enable);
        audit("J1_KILLSWITCH_TOGGLED", normalizedKey, request.operator(), Map.of(
                "switchKey", normalizedKey,
                "before", before,
                "after", enable,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim(),
                "broadcast", true));
        Map<String, Object> response = matrix().getData();
        response.put("updated", Map.of("key", normalizedKey, "before", before, "after", enable));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> emergencyDisable(String idempotencyKey, EmergencyDisableRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request.keys() == null || request.keys().isEmpty()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "KILL_SWITCH_KEYS_REQUIRED");
        }
        List<Map<String, Object>> changed = new ArrayList<>();
        for (String rawKey : request.keys()) {
            String key = normalizeGate(rawKey);
            if (isRetired(key)) {
                return retiredFeature();
            }
            boolean before = gateEnabled(key);
            writeGate(key, false);
            changed.add(Map.of("key", key, "before", before, "after", false));
        }
        audit("J1_EMERGENCY_KILLSWITCH_TRIGGERED", "batch", request.operator(), Map.of(
                "changed", changed,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim(),
                "broadcast", true));
        Map<String, Object> response = matrix().getData();
        response.put("updated", changed);
        return ApiResult.ok(response);
    }

    private Map<String, Object> gateView(String key) {
        boolean enabled = gateEnabled(key);
        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("key", key);
        gate.put("enabled", enabled);
        gate.put("status", enabled ? "enabled" : "disabled");
        gate.put("configKey", configKey(key));
        gate.put("recoveryPrecheck", "B1_COVERAGE_REDLINE");
        gate.put("ownerDomain", "J1");
        return gate;
    }

    private List<Map<String, Object>> retiredGates() {
        List<Map<String, Object>> retired = new ArrayList<>();
        retired.add(retired("premium", "Premium subscription is sunset"));
        retired.add(retired("nexv2", "NEX v2 vault is sunset; only historical maturity remains"));
        retired.add(retired("points", "Points system is sunset and replaced by NEX reward rules"));
        return retired;
    }

    private Map<String, Object> retired(String key, String reason) {
        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("key", key);
        gate.put("status", "SUNSET_HISTORY_ONLY");
        gate.put("toggleAllowed", false);
        gate.put("reason", reason);
        return gate;
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

    private String normalizeGate(String key) {
        String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        if (isRetired(normalized)) {
            return normalized;
        }
        if (!ACTIVE_GATES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported J1 kill switch key");
        }
        return normalized;
    }

    private boolean isRetired(String key) {
        return RETIRED_GATES.contains(key);
    }

    private ApiResult<Map<String, Object>> retiredFeature() {
        return ApiResult.fail(OpsErrorCode.RETIRED_FEATURE.httpStatus(), OpsErrorCode.RETIRED_FEATURE.name());
    }

    private ApiResult<Map<String, Object>> coverageRedline() {
        return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(), OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
    }

    private boolean coverageBelowRedline() {
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        return coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0;
    }

    private boolean parseEnabled(String enabled) {
        String normalized = enabled == null ? "" : enabled.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true", "enabled", "enable", "on", "1" -> true;
            case "false", "disabled", "disable", "off", "0" -> false;
            default -> throw new IllegalArgumentException("enabled must be true/false or enabled/disabled");
        };
    }

    private boolean gateEnabled(String key) {
        Optional<String> value = configFacade.activeValue(configKey(key))
                .or(() -> configFacade.activeValue(legacyConfigKey(key)));
        return value.map(raw -> {
                    String normalized = raw.trim().toLowerCase(Locale.ROOT);
                    return "enabled".equals(normalized) || "enable".equals(normalized) || "on".equals(normalized) || "true".equals(normalized) || "1".equals(normalized);
                })
                .orElse(true);
    }

    private void writeGate(String key, boolean enabled) {
        configFacade.upsertAdminValue(configKey(key), enabled ? "enabled" : "disabled", "STRING", "admin_killswitch", "J1 active kill switch");
    }

    private String configKey(String key) {
        return "killswitch." + key;
    }

    private String legacyConfigKey(String key) {
        return "emergency.killswitch." + key;
    }

    private void audit(String action, String switchKey, String operator, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("KILL_SWITCH")
                .resourceId(switchKey)
                .bizNo(switchKey)
                .actorType("ADMIN")
                .actorUsername(StringUtils.hasText(operator) ? operator.trim() : "system")
                .result("SUCCESS")
                .riskLevel("CRITICAL")
                .detail(detail)
                .build());
    }
}

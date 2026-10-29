package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.platform.domain.PlatformConfigItem;
import ffdd.opsconsole.platform.domain.PlatformConfigRepository;
import ffdd.opsconsole.platform.dto.PlatformConfigOverview;
import ffdd.opsconsole.platform.dto.PlatformConfigResponse;
import ffdd.opsconsole.platform.dto.PlatformConfigUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsPlatformConfigService {
    private static final String GROUP_FLAG = "admin_feature_flag";
    private static final String GROUP_PARAM = "admin_platform_param";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final Map<String, FeatureDefinition> FEATURE_DEFINITIONS = Map.of(
            "ops.maintenanceBanner",
            new FeatureDefinition(
                    "ops.maintenanceBanner",
                    "维护公告横幅",
                    "后台全局维护提示；开启后所有已登录运营人员会在页面顶部看到提示。",
                    "全体后台运营人员",
                    "平台运维 / 超管",
                    "PLATFORM",
                    "ops-console-shell",
                    List.of("on", "off"),
                    List.of("SUPER_ADMIN")));
    private static final Map<String, ParamDefinition> PARAM_DEFINITIONS = Map.of(
            "platform.global_rate_limit_per_minute",
            new ParamDefinition("platform.global_rate_limit_per_minute", "全球限流上限",
                    "全平台 API 每分钟允许的请求总数", "次/分钟", 100, 1_000_000,
                    "PlatformGlobalRateLimitFilter", List.of("SUPER_ADMIN")),
            "withdrawal.strong_review_threshold_usdt",
            new ParamDefinition("withdrawal.strong_review_threshold_usdt", "提现强审阈值",
                    "达到该 USDT 金额的提现必须进入人工强审", "USDT", 20, 10_000_000,
                    "D2 AppWithdrawalService + B1/B5 withdrawal projection", List.of("SUPER_ADMIN")));

    private final PlatformConfigRepository configRepository;
    private final AuditLogService auditLogService;
    private final AdminIdempotencyService idempotencyService;
    private final AdminOperatorRoleResolver roleResolver;
    private final PlatformEmergencyStateProvider emergencyStateProvider;
    private final PlatformSystemHealthProvider healthProvider;

    public ApiResult<PlatformConfigOverview> overview() {
        String role = normalizeRole(roleResolver.resolveCode());
        List<Map<String, Object>> flags = FEATURE_DEFINITIONS.values().stream()
                .map(definition -> configRepository.findActiveByKey(configKey(definition.key()))
                        .map(item -> featureView(definition, item, role))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        List<Map<String, Object>> params = PARAM_DEFINITIONS.values().stream()
                .map(definition -> configRepository.findActiveByKey(definition.key())
                        .map(item -> paramView(definition, item, role))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        List<Map<String, Object>> gates = emergencyStateProvider.currentKillSwitches();
        List<Map<String, Object>> health = healthProvider.currentHealth();
        long upGates = gates.stream().filter(gate -> Boolean.TRUE.equals(gate.get("up"))).count();
        return ApiResult.ok(new PlatformConfigOverview(
                flags,
                params,
                gates,
                health,
                Map.of(
                        "flagCount", flags.size(),
                        "flagOnCount", flags.stream()
                                .filter(flag -> "on".equalsIgnoreCase(String.valueOf(flag.get("status"))))
                                .count(),
                        "killGates", gates.size(),
                        "killGatesUp", upGates)));
    }

    public ApiResult<Map<String, Object>> runtimeFlags() {
        String key = configKey("ops.maintenanceBanner");
        PlatformConfigItem item = configRepository.findActiveByKey(key).orElse(null);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("maintenanceBanner", item != null && "on".equalsIgnoreCase(item.configValue()));
        response.put("configured", item != null);
        response.put("value", item == null ? "" : item.configValue());
        response.put("source", "nx_config_item:" + key);
        response.put("observedAt", java.time.LocalDateTime.now().format(ISO));
        return ApiResult.ok(response);
    }

    @Transactional
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ApiResult<PlatformConfigResponse> update(String idempotencyKey, PlatformConfigUpdateRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED, OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null) {
            return fail(OpsErrorCode.VALIDATION_FAILED, "A3_REQUEST_REQUIRED");
        }
        if ("param".equals(normalize(request.kind()))) {
            return updateParam(idempotencyKey, request);
        }
        if (!"flag".equals(normalize(request.kind()))) {
            return fail(OpsErrorCode.VALIDATION_FAILED,
                    "gate".equals(normalize(request.kind()))
                            ? "J1_KILLSWITCH_MOVED_TO_EMERGENCY_CONTROL_SETTING"
                            : "A3_KIND_INVALID");
        }
        FeatureDefinition definition = FEATURE_DEFINITIONS.get(trim(request.flagKey()));
        if (definition == null) {
            return fail(OpsErrorCode.VALIDATION_FAILED, "A3_FLAG_UNKNOWN");
        }
        String reason = trim(request.reason());
        if (reason == null || reason.length() < 8 || reason.length() > 200) {
            return fail(OpsErrorCode.VALIDATION_FAILED, "A3_REASON_LENGTH_INVALID");
        }
        String value = normalize(request.value());
        if (!definition.allowedValues().contains(value)) {
            return fail(OpsErrorCode.VALIDATION_FAILED, "A3_FLAG_VALUE_INVALID");
        }
        String expectedValue = normalize(request.expectedValue());
        if (!definition.allowedValues().contains(expectedValue)) {
            return fail(OpsErrorCode.VALIDATION_FAILED, "A3_FLAG_EXPECTED_VALUE_REQUIRED");
        }
        String role = normalizeRole(roleResolver.resolveCode());
        if (!definition.writerRoles().contains(role)) {
            return fail(OpsErrorCode.FORBIDDEN, "A3_FLAG_ROLE_FORBIDDEN");
        }

        String scope = "A3_FEATURE_FLAG:" + definition.key();
        String hash = requestHash(definition.key(), value, expectedValue, reason, role);
        ApiResult result = idempotencyService.execute(
                scope,
                idempotencyKey.trim(),
                hash,
                ApiResult.class,
                () -> updateOnce(definition, value, expectedValue, reason, role, idempotencyKey.trim()));
        return (ApiResult<PlatformConfigResponse>) result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ApiResult<PlatformConfigResponse> updateParam(String idempotencyKey, PlatformConfigUpdateRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED, OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        ParamDefinition definition = PARAM_DEFINITIONS.get(trim(request.flagKey()));
        if (definition == null) return fail(OpsErrorCode.VALIDATION_FAILED, "A3_PARAM_UNKNOWN");
        String reason = trim(request.reason());
        if (reason == null || reason.length() < 8 || reason.length() > 200) {
            return fail(OpsErrorCode.VALIDATION_FAILED, "A3_REASON_LENGTH_INVALID");
        }
        long value;
        long expected;
        try {
            value = Long.parseLong(trim(request.value()));
            expected = Long.parseLong(trim(request.expectedValue()));
        } catch (RuntimeException ex) {
            return fail(OpsErrorCode.VALIDATION_FAILED, "A3_PARAM_INTEGER_REQUIRED");
        }
        if (value < definition.min() || value > definition.max()) {
            return fail(OpsErrorCode.VALIDATION_FAILED, "A3_PARAM_OUT_OF_RANGE");
        }
        String role = normalizeRole(roleResolver.resolveCode());
        if (!definition.writerRoles().contains(role)) return fail(OpsErrorCode.FORBIDDEN, "A3_PARAM_ROLE_FORBIDDEN");
        String hash = requestHash(definition.key(), String.valueOf(value), String.valueOf(expected), reason, role);
        ApiResult result = idempotencyService.execute(
                "A3_PLATFORM_PARAM:" + definition.key(), idempotencyKey.trim(), hash, ApiResult.class,
                () -> updateParamOnce(definition, value, expected, reason, role, idempotencyKey.trim()));
        return (ApiResult<PlatformConfigResponse>) result;
    }

    private ApiResult<PlatformConfigResponse> updateParamOnce(
            ParamDefinition definition, long value, long expected, String reason, String role, String idempotencyKey) {
        PlatformConfigItem existing = configRepository.findActiveByKeyForUpdate(definition.key()).orElse(null);
        if (existing == null) return fail(OpsErrorCode.INVALID_STATE_TRANSITION, "A3_PARAM_NOT_CONFIGURED");
        long before;
        try {
            before = Long.parseLong(existing.configValue().trim());
        } catch (RuntimeException ex) {
            return fail(OpsErrorCode.INVALID_STATE_TRANSITION, "A3_PARAM_SOURCE_INVALID");
        }
        if (expected != before) return fail(OpsErrorCode.INVALID_STATE_TRANSITION, "A3_PARAM_STALE");
        if (value == before) return fail(OpsErrorCode.INVALID_STATE_TRANSITION, "A3_PARAM_SAME_VALUE");
        PlatformConfigItem saved = configRepository.save(existing.withValue(
                String.valueOf(value), GROUP_PARAM,
                "A3 platform param; consumer=" + definition.consumer() + "; reason=" + reason, 1));
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("A3_PLATFORM_PARAM_CHANGED").resourceType("A3_PLATFORM_PARAM")
                .resourceId(definition.key()).actorType("ADMIN")
                .actorUsername(AdminActorResolver.resolve(null)).result("SUCCESS").riskLevel("HIGH")
                .detail(Map.of("before", before, "after", value, "reason", reason, "role", role,
                        "consumer", definition.consumer(), "idempotencyKey", idempotencyKey)).build());
        return ApiResult.ok(toResponse(saved));
    }

    private ApiResult<PlatformConfigResponse> updateOnce(
            FeatureDefinition definition,
            String value,
            String expectedValue,
            String reason,
            String role,
            String idempotencyKey) {
        String key = configKey(definition.key());
        PlatformConfigItem existing = configRepository.findActiveByKeyForUpdate(key).orElse(null);
        if (existing == null) {
            return fail(OpsErrorCode.INVALID_STATE_TRANSITION, "A3_FLAG_NOT_CONFIGURED");
        }
        String before = normalize(existing.configValue());
        if (!expectedValue.equals(before)) {
            return fail(OpsErrorCode.INVALID_STATE_TRANSITION, "A3_FLAG_STALE");
        }
        if (value.equals(before)) {
            return fail(OpsErrorCode.INVALID_STATE_TRANSITION, "A3_FLAG_SAME_VALUE");
        }

        PlatformConfigItem saved = configRepository.save(existing.withValue(
                value,
                GROUP_FLAG,
                "A3 feature flag; consumer=" + definition.consumer() + "; reason=" + reason,
                1));
        String actor = AdminActorResolver.resolve(null);
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("ADMIN_FEATURE_FLAG_CHANGED")
                .resourceType("A3_PLATFORM_CONFIG")
                .resourceId(key)
                .actorType("ADMIN")
                .actorUsername(StringUtils.hasText(actor) ? actor : "admin-role:" + role)
                .result("SUCCESS")
                .riskLevel("MEDIUM")
                .detail(Map.of(
                        "kind", "flag",
                        "configKey", key,
                        "category", definition.category(),
                        "consumer", definition.consumer(),
                        "before", before,
                        "after", value,
                        "reason", reason,
                        "role", role,
                        "idempotencyKey", idempotencyKey))
                .build());
        return ApiResult.ok(toResponse(saved));
    }

    private Map<String, Object> featureView(FeatureDefinition definition, PlatformConfigItem item, String role) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", definition.key());
        row.put("name", definition.name());
        row.put("desc", definition.description());
        row.put("status", item.configValue());
        row.put("scope", definition.scope());
        row.put("lastChange", item.updatedAt() == null ? "未知" : item.updatedAt().format(ISO));
        row.put("resourceOwner", definition.owner());
        row.put("category", definition.category());
        row.put("consumer", definition.consumer());
        row.put("allowedValues", definition.allowedValues());
        row.put("writable", definition.writerRoles().contains(role));
        return row;
    }

    private Map<String, Object> paramView(ParamDefinition definition, PlatformConfigItem item, String role) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", definition.key());
        row.put("name", definition.name());
        row.put("desc", definition.description());
        row.put("value", item.configValue());
        row.put("unit", definition.unit());
        row.put("min", definition.min());
        row.put("max", definition.max());
        row.put("consumer", definition.consumer());
        row.put("writable", definition.writerRoles().contains(role));
        row.put("lastChange", item.updatedAt() == null ? "未知" : item.updatedAt().format(ISO));
        return row;
    }

    private PlatformConfigResponse toResponse(PlatformConfigItem item) {
        return new PlatformConfigResponse(
                item.id(), item.configKey(), item.configValue(), item.valueType(), item.configGroup(),
                item.visibility(), item.remark(), item.status(), item.createdAt(), item.updatedAt());
    }

    private String requestHash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String configKey(String key) {
        return "feature." + key;
    }

    private String normalizeRole(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private ApiResult<PlatformConfigResponse> fail(OpsErrorCode code, String message) {
        return ApiResult.fail(code.httpStatus(), message);
    }

    private record FeatureDefinition(
            String key,
            String name,
            String description,
            String scope,
            String owner,
            String category,
            String consumer,
            List<String> allowedValues,
            List<String> writerRoles) {
    }

    private record ParamDefinition(
            String key, String name, String description, String unit,
            long min, long max, String consumer, List<String> writerRoles) {
    }
}

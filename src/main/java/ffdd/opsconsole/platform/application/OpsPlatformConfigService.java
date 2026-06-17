package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.platform.domain.PlatformConfigItem;
import ffdd.opsconsole.platform.domain.PlatformConfigRepository;
import ffdd.opsconsole.platform.dto.PlatformConfigOverview;
import ffdd.opsconsole.platform.dto.PlatformConfigResponse;
import ffdd.opsconsole.platform.dto.PlatformConfigUpdateRequest;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;

@ApplicationService
public class OpsPlatformConfigService {
    private static final String GROUP_A3 = "admin_a3";
    private static final String GROUP_FLAG = "admin_feature_flag";
    private static final String GROUP_GATE = "admin_killswitch";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final Pattern CONFIG_SUFFIX_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,96}$");
    private static final Set<String> ACTIVE_GROUPS = Set.of(GROUP_A3, GROUP_FLAG, GROUP_GATE);

    private final PlatformConfigRepository configRepository;
    private final AuditLogService auditLogService;

    public OpsPlatformConfigService(PlatformConfigRepository configRepository, AuditLogService auditLogService) {
        this.configRepository = configRepository;
        this.auditLogService = auditLogService;
    }

    public ApiResult<PlatformConfigOverview> overview() {
        Map<String, PlatformConfigItem> configs = loadConfigMap(ACTIVE_GROUPS);
        List<Map<String, Object>> flags = featureFlags(configs);
        List<Map<String, Object>> gates = killSwitches(configs);
        int upGates = (int) gates.stream()
                .filter(gate -> Boolean.TRUE.equals(gate.get("up")))
                .count();
        return ApiResult.ok(new PlatformConfigOverview(
                LocalDateTime.now().format(ISO),
                Map.of(
                        "driftMillis", number(configs, "admin.system.clock_drift_ms", 4),
                        "driftThresholdMillis", number(configs, "admin.system.clock_threshold_ms", 100),
                        "ntpSource", text(configs, "admin.system.ntp_source", "pool.ntp.org x3")),
                Map.of(
                        "windowHours", number(configs, "admin.idempotency.window_hours", 24),
                        "blocked24h", number(configs, "admin.idempotency.blocked_24h", 0)),
                flags,
                gates,
                systemHealth(configs),
                Map.of(
                        "flagCount", flags.size(),
                        "flagGrayCount", flags.stream().filter(flag -> String.valueOf(flag.get("status")).contains("灰度")).count(),
                        "killGates", gates.size(),
                        "killGatesUp", upGates)));
    }

    public ApiResult<PlatformConfigResponse> update(String idempotencyKey, PlatformConfigUpdateRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (request == null || !StringUtils.hasText(request.reason())) {
            return fail(OpsErrorCode.REASON_REQUIRED);
        }
        if (!StringUtils.hasText(request.operator())) {
            return fail(OpsErrorCode.VALIDATION_FAILED, "OPERATOR_REQUIRED");
        }

        ConfigCommand command = commandFor(request);
        if (isSunsetCapabilityKey(command.configKey())) {
            return fail(OpsErrorCode.PHASE_PARAM_READONLY, "SUNSET_CAPABILITY_READONLY");
        }

        PlatformConfigItem existing = configRepository.findActiveByKey(command.configKey()).orElseGet(() ->
                new PlatformConfigItem(
                        null,
                        command.configKey(),
                        command.value(),
                        "STRING",
                        command.group(),
                        "ADMIN",
                        command.remark(),
                        1,
                        LocalDateTime.now(),
                        LocalDateTime.now()));
        PlatformConfigItem saved = configRepository.save(existing.withValue(command.value(), command.group(), command.remark(), 1));
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(command.action())
                .resourceType("A3_PLATFORM_CONFIG")
                .resourceId(command.configKey())
                .actorType("ADMIN")
                .actorUsername(request.operator().trim())
                .result("SUCCESS")
                .riskLevel("gate".equals(command.kind()) ? "HIGH" : "MEDIUM")
                .detail(Map.of(
                        "kind", command.kind(),
                        "configKey", command.configKey(),
                        "value", command.value(),
                        "reason", request.reason().trim(),
                        "idempotencyKey", idempotencyKey.trim()))
                .build());
        return ApiResult.ok(toResponse(saved));
    }

    private Map<String, PlatformConfigItem> loadConfigMap(Collection<String> groups) {
        return configRepository.findActiveByGroups(groups).stream()
                .collect(Collectors.toMap(
                        PlatformConfigItem::configKey,
                        item -> item,
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private List<Map<String, Object>> featureFlags(Map<String, PlatformConfigItem> configs) {
        return List.of(
                feature(configs, "ab.newWithdrawFlow", "灰度 20%", "全量随机", "增长可发起"),
                feature(configs, "ab.homeBannerExp", "on", "注册周 >=W20", "增长可发起"),
                feature(configs, "exp.questBoostAB", "on", "P3 阶段用户", "增长可发起"),
                feature(configs, "core.sse_v2", "灰度 50%", "全量随机", "超管(平台能力)"),
                feature(configs, "ops.maintenanceBanner", "off", "全量", "风控/超管(运维)"));
    }

    private Map<String, Object> feature(
            Map<String, PlatformConfigItem> configs, String key, String fallbackStatus, String scope, String owner) {
        String configKey = "feature." + key;
        PlatformConfigItem item = configs.get(configKey);
        return Map.of(
                "key", key,
                "status", item == null ? fallbackStatus : item.configValue(),
                "scope", scope,
                "lastChange", item == null || item.updatedAt() == null ? "后端默认值" : item.updatedAt().format(ISO),
                "resourceOwner", owner);
    }

    private List<Map<String, Object>> killSwitches(Map<String, PlatformConfigItem> configs) {
        return List.of(
                gate(configs, "exchange", "enabled"),
                gate(configs, "withdraw", "enabled"),
                gate(configs, "deposit", "enabled"),
                gate(configs, "staking", "enabled"),
                gate(configs, "missions", "enabled"),
                gate(configs, "commerce", "enabled"),
                gate(configs, "geo-block", "empty-list"));
    }

    private Map<String, Object> gate(Map<String, PlatformConfigItem> configs, String key, String fallbackStatus) {
        String configKey = "killswitch." + key;
        PlatformConfigItem item = configs.get(configKey);
        String status = item == null ? fallbackStatus : item.configValue();
        return Map.of(
                "key", key,
                "status", status,
                "up", "enabled".equalsIgnoreCase(status) || "empty-list".equalsIgnoreCase(status),
                "lastChange", item == null || item.updatedAt() == null ? "后端默认值" : item.updatedAt().format(ISO),
                "chain", item == null || !StringUtils.hasText(item.remark()) ? "system / config" : item.remark());
    }

    private List<Map<String, Object>> systemHealth(Map<String, PlatformConfigItem> configs) {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long usedMb = memory.getHeapMemoryUsage().getUsed() / 1024 / 1024;
        long maxMb = Math.max(1, memory.getHeapMemoryUsage().getMax() / 1024 / 1024);
        return List.of(
                Map.of("name", "事件管道(采集 -> 去重 -> 事件库)", "tone", "ok", "metric", text(configs, "admin.health.event_pipeline", "正常 · 延迟 1.2s")),
                Map.of("name", "账本写入(资金事务)", "tone", "ok", "metric", text(configs, "admin.health.ledger_write", "正常 · p99 84ms")),
                Map.of("name", "后台接口可用性(24h)", "tone", "ok", "metric", text(configs, "admin.health.admin_api_availability", "99.98%")),
                Map.of("name", "JVM 堆内存", "tone", usedMb * 100 / maxMb > 85 ? "warn" : "ok", "metric", usedMb + "MB / " + maxMb + "MB"),
                Map.of("name", "SSE 推送通道(配置失效广播)", "tone", "warn", "metric", text(configs, "admin.health.sse", "轻度抖动 · 重连率 2.1%")));
    }

    private ConfigCommand commandFor(PlatformConfigUpdateRequest request) {
        String kind = trimToLower(request.kind());
        return switch (kind) {
            case "ntp" -> new ConfigCommand(kind, "admin.system.ntp_source", normalizeTextValue(request.value()), GROUP_A3,
                    remarkFor(request), "ADMIN_SYSTEM_PARAM_CHANGED");
            case "idempotency" -> new ConfigCommand(kind, "admin.idempotency.window_hours", normalizeIdempotencyHours(request.value()), GROUP_A3,
                    remarkFor(request), "ADMIN_SYSTEM_PARAM_CHANGED");
            case "flag" -> new ConfigCommand(kind, "feature." + normalizeSuffix(request.flagKey(), "flagKey"),
                    normalizeTextValue(request.value()), GROUP_FLAG, remarkFor(request), "ADMIN_FEATURE_FLAG_CHANGED");
            case "gate" -> new ConfigCommand(kind, "killswitch." + normalizeSuffix(request.gateKey(), "gateKey"),
                    normalizeGateValue(request.value()), GROUP_GATE, remarkFor(request), "ADMIN_KILL_SWITCH_CHANGED");
            default -> throw new IllegalArgumentException("Unsupported A3 config kind: " + request.kind());
        };
    }

    private String normalizeIdempotencyHours(String value) {
        String normalized = normalizeTextValue(value).replaceAll("[^0-9]", "");
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("idempotency window must be numeric hours");
        }
        int hours = Integer.parseInt(normalized);
        if (hours < 1 || hours > 72) {
            throw new IllegalArgumentException("idempotency window must be 1-72 hours");
        }
        return String.valueOf(hours);
    }

    private String normalizeGateValue(String value) {
        String normalized = normalizeTextValue(value).toLowerCase(Locale.ROOT);
        if (!Set.of("enabled", "disabled", "empty-list").contains(normalized)) {
            throw new IllegalArgumentException("kill switch value must be enabled, disabled, or empty-list");
        }
        return normalized;
    }

    private String normalizeSuffix(String value, String fieldName) {
        String normalized = normalizeTextValue(value);
        if (!CONFIG_SUFFIX_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
        return normalized;
    }

    private String normalizeTextValue(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("value is required");
        }
        return value.trim();
    }

    private String trimToLower(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("kind is required");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isSunsetCapabilityKey(String configKey) {
        String normalized = configKey.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return normalized.contains("premium") || normalized.contains("nexv2") || normalized.contains("nex.v2")
                || normalized.contains("points");
    }

    private String remarkFor(PlatformConfigUpdateRequest request) {
        return "A3 " + trimToLower(request.kind()) + " change: " + request.reason().trim();
    }

    private String text(Map<String, PlatformConfigItem> configs, String key, String fallback) {
        PlatformConfigItem item = configs.get(key);
        return item == null || !StringUtils.hasText(item.configValue()) ? fallback : item.configValue();
    }

    private int number(Map<String, PlatformConfigItem> configs, String key, int fallback) {
        try {
            return Integer.parseInt(text(configs, key, String.valueOf(fallback)).replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private PlatformConfigResponse toResponse(PlatformConfigItem item) {
        return new PlatformConfigResponse(
                item.id(),
                item.configKey(),
                item.configValue(),
                item.valueType(),
                item.configGroup(),
                item.visibility(),
                item.remark(),
                item.status(),
                item.createdAt(),
                item.updatedAt());
    }

    private ApiResult<PlatformConfigResponse> fail(OpsErrorCode errorCode) {
        return fail(errorCode, errorCode.name());
    }

    private ApiResult<PlatformConfigResponse> fail(OpsErrorCode errorCode, String message) {
        return ApiResult.fail(errorCode.httpStatus(), message);
    }

    private record ConfigCommand(String kind, String configKey, String value, String group, String remark, String action) {
    }
}

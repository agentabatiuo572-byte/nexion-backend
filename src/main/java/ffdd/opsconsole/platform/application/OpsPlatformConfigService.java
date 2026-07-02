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
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
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
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsPlatformConfigService {
    private static final String GROUP_FLAG = "admin_feature_flag";
    private static final String GROUP_GATE = "admin_killswitch";
    private static final String GROUP_HEALTH = "admin_system_health";
    private static final String META_DELIMITER = "||";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final Pattern CONFIG_SUFFIX_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,96}$");
    private static final Set<String> ACTIVE_GROUPS = Set.of(GROUP_FLAG, GROUP_HEALTH);
    private static final Set<String> DELETED_CONFIG_KEYS = Set.of(
            "admin.system.clock_drift_ms",
            "admin.system.clock_threshold_ms",
            "admin.system.ntp_source",
            "admin.idempotency.window_hours",
            "admin.idempotency.blocked_24h");

    private static final List<FeatureSeed> FEATURE_SEEDS = List.of(
            new FeatureSeed("ab.newWithdrawFlow", "新版提现流程实验", "新提现页与旧版 A/B,对比转化与流失", "灰度 20%", "全量随机", "增长可发起"),
            new FeatureSeed("ab.homeBannerExp", "首页 Banner 实验", "活动入口 Banner 素材与排序实验", "on", "注册周 >=W20", "增长可发起"),
            new FeatureSeed("exp.questBoostAB", "任务加成实验", "新手任务收益加成实验", "on", "P3 阶段用户", "增长可发起"),
            new FeatureSeed("core.sse_v2", "SSE v2 通道", "新推送链路灰度,观察重连率与延迟", "灰度 50%", "全量随机", "超管(平台能力)"),
            new FeatureSeed("ops.maintenanceBanner", "维护公告横幅", "后台统一维护公告开关", "off", "全量", "风控/超管(运维)"));

    private static final List<GateSeed> GATE_SEEDS = List.of(
            new GateSeed("withdraw", "提现", "enabled", "2d 前 · risk@nexion / super@nexion", "risk@nexion / super@nexion"),
            new GateSeed("staking", "质押", "enabled", "6d 前 · ops@nexion / super@nexion", "ops@nexion / super@nexion"),
            new GateSeed("genesis", "Genesis 入口", "enabled", "3h 前 · ops@nexion / super@nexion", "ops@nexion / super@nexion"),
            new GateSeed("exchange", "交易", "enabled", "1d 前 · exchange@nexion / super@nexion", "exchange@nexion / super@nexion"),
            new GateSeed("trial", "试用权益", "enabled", "5d 前 · growth@nexion / super@nexion", "growth@nexion / super@nexion"),
            new GateSeed("geo-block", "地区屏蔽", "empty-list", "-", "- / -"));

    private static final List<HealthSeed> HEALTH_SEEDS = List.of(
            new HealthSeed("event_pipeline", "事件管道(采集 -> 事件库)", "正常 · 延迟 1.2s", "ok"),
            new HealthSeed("ledger_write", "账本写入(资金事务)", "正常 · p99 84ms", "ok"),
            new HealthSeed("admin_api_availability", "后台接口可用性(24h)", "99.98%", "ok"),
            new HealthSeed("sse", "SSE 推送通道(配置失效广播)", "轻度抖动 · 重连率 2.1%", "warn"));

    private final PlatformConfigRepository configRepository;
    private final AuditLogService auditLogService;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;

    public ApiResult<PlatformConfigOverview> overview() {
        ensureSeedData();
        Map<String, PlatformConfigItem> configs = loadConfigMap(ACTIVE_GROUPS);
        List<Map<String, Object>> flags = featureFlags(configs);
        List<Map<String, Object>> gates = killSwitches(configs);
        List<Map<String, Object>> health = systemHealth(configs);
        int upGates = (int) gates.stream()
                .filter(gate -> Boolean.TRUE.equals(gate.get("up")))
                .count();
        return ApiResult.ok(new PlatformConfigOverview(
                flags,
                gates,
                health,
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
        if ("gate".equals(command.kind())) {
            return fail(OpsErrorCode.VALIDATION_FAILED, "J1_KILLSWITCH_MOVED_TO_EMERGENCY_CONTROL_SETTING");
        }
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

    private void ensureSeedData() {
        // Read paths must not mutate nx_config_item. Retired keys are ignored by active-group filtering.
    }

    private void retireDeletedConfigData() {
        DELETED_CONFIG_KEYS.forEach(configKey -> configRepository.findActiveByKey(configKey)
                .ifPresent(item -> configRepository.save(new PlatformConfigItem(
                        item.id(),
                        item.configKey(),
                        item.configValue(),
                        item.valueType(),
                        item.configGroup(),
                        item.visibility(),
                        "A3 deleted config domain retired",
                        0,
                        item.createdAt(),
                        LocalDateTime.now()))));
    }

    private void ensureConfig(String configKey, String configValue, String configGroup, String remark) {
        if (configRepository.findActiveByKey(configKey).isPresent()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        configRepository.save(new PlatformConfigItem(
                null,
                configKey,
                configValue,
                "STRING",
                configGroup,
                "ADMIN",
                remark,
                1,
                now,
                now));
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
        return configs.values().stream()
                .filter(item -> GROUP_FLAG.equals(item.configGroup()))
                .map(this::genericFeature)
                .toList();
    }

    private Map<String, Object> genericFeature(PlatformConfigItem item) {
        String key = item.configKey().startsWith("feature.")
                ? item.configKey().substring("feature.".length())
                : item.configKey();
        return Map.of(
                "key", key,
                "name", key,
                "desc", item.remark() == null ? "" : item.remark(),
                "status", item.configValue() == null ? "" : item.configValue(),
                "scope", "",
                "lastChange", updatedAt(item),
                "resourceOwner", "");
    }

    private Map<String, Object> feature(Map<String, PlatformConfigItem> configs, FeatureSeed seed) {
        PlatformConfigItem item = configs.get(seed.configKey());
        List<String> meta = metaParts(item, 4);
        return Map.of(
                "key", seed.key(),
                "name", metaValue(meta, 0, seed.name()),
                "desc", metaValue(meta, 1, seed.description()),
                "status", valueOf(item, seed.status()),
                "scope", metaValue(meta, 2, seed.scope()),
                "lastChange", updatedAt(item),
                "resourceOwner", metaValue(meta, 3, seed.owner()));
    }

    private List<Map<String, Object>> killSwitches(Map<String, PlatformConfigItem> configs) {
        return configs.values().stream()
                .filter(item -> GROUP_GATE.equals(item.configGroup()))
                .map(this::genericGate)
                .toList();
    }

    private Map<String, Object> genericGate(PlatformConfigItem item) {
        String key = item.configKey().startsWith("killswitch.")
                ? item.configKey().substring("killswitch.".length())
                : item.configKey();
        String status = normalizeGateDisplay(item.configValue());
        return Map.of(
                "key", key,
                "name", key,
                "status", status,
                "up", isGateUp(status),
                "lastChange", updatedAt(item),
                "chain", "");
    }

    private Map<String, Object> gate(Map<String, PlatformConfigItem> configs, GateSeed seed) {
        PlatformConfigItem item = configs.get(seed.configKey());
        List<String> meta = metaParts(item, 3);
        String status = normalizeGateDisplay(valueOf(item, seed.status()));
        return Map.of(
                "key", seed.key(),
                "name", metaValue(meta, 0, seed.name()),
                "status", status,
                "up", isGateUp(status),
                "lastChange", metaValue(meta, 1, updatedAt(item)),
                "chain", metaValue(meta, 2, seed.chain()));
    }

    private List<Map<String, Object>> systemHealth(Map<String, PlatformConfigItem> configs) {
        List<Map<String, Object>> seededHealth = configs.values().stream()
                .filter(item -> GROUP_HEALTH.equals(item.configGroup()))
                .map(this::genericHealth)
                .collect(Collectors.toList());
        seededHealth.add(jvmHealth());
        return seededHealth;
    }

    private Map<String, Object> genericHealth(PlatformConfigItem item) {
        return Map.of(
                "name", item.configKey(),
                "tone", "",
                "metric", item.configValue() == null ? "" : item.configValue());
    }

    private Map<String, Object> health(Map<String, PlatformConfigItem> configs, HealthSeed seed) {
        PlatformConfigItem item = configs.get(seed.configKey());
        List<String> meta = metaParts(item, 2);
        return Map.of(
                "name", metaValue(meta, 0, seed.name()),
                "tone", metaValue(meta, 1, seed.tone()),
                "metric", valueOf(item, seed.metric()));
    }

    private Map<String, Object> jvmHealth() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long usedMb = memory.getHeapMemoryUsage().getUsed() / 1024 / 1024;
        long maxMb = Math.max(1, memory.getHeapMemoryUsage().getMax() / 1024 / 1024);
        return Map.of(
                "name", "JVM 堆内存",
                "tone", usedMb * 100 / maxMb > 85 ? "warn" : "ok",
                "metric", usedMb + "MB / " + maxMb + "MB");
    }

    private ConfigCommand commandFor(PlatformConfigUpdateRequest request) {
        String kind = trimToLower(request.kind());
        return switch (kind) {
            case "flag" -> new ConfigCommand(kind, "feature." + normalizeSuffix(request.flagKey(), "flagKey"),
                    normalizeTextValue(request.value()), GROUP_FLAG, remarkFor(request), "ADMIN_FEATURE_FLAG_CHANGED");
            case "gate" -> new ConfigCommand(kind, "killswitch." + normalizeSuffix(request.gateKey(), "gateKey"),
                    normalizeGateValue(request.value()), GROUP_GATE, remarkFor(request), "ADMIN_KILL_SWITCH_CHANGED");
            default -> throw new IllegalArgumentException("Unsupported A3 config kind: " + request.kind());
        };
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

    private String valueOf(PlatformConfigItem item, String fallback) {
        if (item != null && StringUtils.hasText(item.configValue())) {
            return item.configValue();
        }
        return "";
    }

    private String updatedAt(PlatformConfigItem item) {
        if (item != null && item.updatedAt() != null) {
            return item.updatedAt().format(ISO);
        }
        return "未配置";
    }

    private static String joinMeta(String... parts) {
        return String.join(META_DELIMITER, parts);
    }

    private List<String> metaParts(PlatformConfigItem item, int expectedParts) {
        if (item == null || !StringUtils.hasText(item.remark())) {
            return List.of();
        }
        String[] parts = item.remark().split("\\|\\|", -1);
        return parts.length >= expectedParts ? List.of(parts) : List.of();
    }

    private String metaValue(List<String> meta, int index, String fallback) {
        if (index >= meta.size() || !StringUtils.hasText(meta.get(index))) {
            return fallback;
        }
        return meta.get(index);
    }

    private String normalizeGateDisplay(String status) {
        return "empty-list".equalsIgnoreCase(status) ? "空列表 · 无封锁" : status;
    }

    private boolean isGateUp(String status) {
        return Set.of("enabled", "on", "true", "空列表 · 无封锁")
                .contains(status.toLowerCase(Locale.ROOT));
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

    private record FeatureSeed(String key, String name, String description, String status, String scope, String owner) {
        private String configKey() {
            return "feature." + key;
        }

        private String remark() {
            return joinMeta(name, description, scope, owner);
        }
    }

    private record GateSeed(String key, String name, String status, String lastChange, String chain) {
        private String configKey() {
            return "killswitch." + key;
        }

        private String remark() {
            return joinMeta(name, lastChange, chain);
        }
    }

    private record HealthSeed(String key, String name, String metric, String tone) {
        private String configKey() {
            return "admin.health." + key;
        }

        private String remark() {
            return joinMeta(name, tone);
        }
    }
}

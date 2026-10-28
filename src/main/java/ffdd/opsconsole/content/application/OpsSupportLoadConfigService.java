package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.dto.SupportAgentLoadStateRequest;
import ffdd.opsconsole.content.dto.SupportLoadConfigUpdateRequest;
import ffdd.opsconsole.content.dto.SupportLoadRebalanceRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** M1 seat capacity (load config / manual rebalance) of the live customer-service workbench. */
@ApplicationService
@RequiredArgsConstructor
public class OpsSupportLoadConfigService {
    private static final String LOAD_GROUP = "content_support_load";
    private static final String LOAD_PREFIX = "content.support.load.";
    private static final String LOAD_VERSION_KEY = LOAD_PREFIX + "version";
    private static final Pattern LOAD_AGENT_KEY = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final OpsSupportAgentService supportAgentService;
    private final PlatformConfigFacade configFacade;
    private final AuditLogService auditLogService;
    private final AdminIdempotencyService idempotencyService;
    private final Clock clock;
    private final SupportOwnershipService ownership;

    @Transactional(readOnly=true)
    public ApiResult<Map<String, Object>> loadConfig() {
        return ApiResult.ok(loadConfigView());
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateLoadConfig(String idempotencyKey, SupportLoadConfigUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireSupportCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request.expectedVersion() == null || request.expectedVersion() < 1L) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_LOAD_EXPECTED_VERSION_REQUIRED");
        }
        Map<String, SupportAgentLoadStateRequest> agentState = request.agentState() == null ? Map.of() : request.agentState();
        for (String agentId : agentState.keySet()) {
            if (!isSafeAgentKey(agentId)) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_LOAD_AGENT_ID_INVALID");
            }
        }
        return idempotentCommand(
                "M1_SUPPORT_LOAD_CONFIG_UPDATE",
                idempotencyKey,
                requestHash(String.valueOf(request)),
                () -> updateLoadConfigOnce(idempotencyKey, request, agentState));
    }

    private ApiResult<Map<String, Object>> updateLoadConfigOnce(
            String idempotencyKey,
            SupportLoadConfigUpdateRequest request,
            Map<String, SupportAgentLoadStateRequest> agentState) {
        long currentVersion = currentLoadVersionForUpdate();
        if (request.expectedVersion() != currentVersion) {
            return ApiResult.fail(409, "SUPPORT_LOAD_VERSION_CONFLICT");
        }
        int defaultCap = boundedInt(request.defaultCap(), 0, 40, 8);
        int burstCap = boundedInt(request.burstCap(), 0, 40, Math.max(defaultCap, 12));
        int warnPct = boundedInt(request.warnPct(), 50, 100, 80);
        String overflowQueue = StringUtils.hasText(request.overflowQueue()) ? request.overflowQueue().trim() : "转人工备勤队列";
        if (overflowQueue.length() > 80) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_LOAD_OVERFLOW_QUEUE_TOO_LONG");
        }
        String remark = request.reason().trim();
        List<Map<String, Object>> availabilityChanges = supportAgentService.updateAvailabilityForLoad(agentState);
        upsertLoadValue("autoBalance", Boolean.TRUE.equals(request.autoBalance()) ? "true" : "false", "BOOLEAN", remark);
        upsertLoadValue("defaultCap", String.valueOf(defaultCap), "NUMBER", remark);
        upsertLoadValue("burstCap", String.valueOf(burstCap), "NUMBER", remark);
        upsertLoadValue("warnPct", String.valueOf(warnPct), "NUMBER", remark);
        upsertLoadValue("quietHourBalance", Boolean.TRUE.equals(request.quietHourBalance()) ? "true" : "false", "BOOLEAN", remark);
        upsertLoadValue("overflowQueue", overflowQueue, "STRING", remark);
        agentState.forEach((agentId, state) -> {
            int cap = boundedInt(state == null ? null : state.cap(), 0, 40, defaultCap);
            upsertLoadValue("agent." + agentId + ".cap", String.valueOf(cap), "NUMBER", remark);
        });
        upsertLoadValue("version", String.valueOf(currentVersion + 1L), "NUMBER", remark);
        audit("M1_SUPPORT_LOAD_CONFIG_CHANGED", "SUPPORT_LOAD_CONFIG", "m1.support.load", request.operator(), Map.of(
                "reason", remark,
                "idempotencyKey", idempotencyKey.trim(),
                "agentCount", agentState.size(),
                "availabilityChanges", availabilityChanges,
                "beforeVersion", currentVersion,
                "afterVersion", currentVersion + 1L,
                "defaultCap", defaultCap,
                "burstCap", burstCap,
                "warnPct", warnPct));
        return loadConfig();
    }

    @Transactional
    public ApiResult<Map<String, Object>> rebalanceLoad(String idempotencyKey, SupportLoadRebalanceRequest request) {
        ApiResult<Map<String, Object>> guard = requireSupportCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request.expectedVersion() == null || request.expectedVersion() < 1L) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_LOAD_EXPECTED_VERSION_REQUIRED");
        }
        List<Map<String, Object>> agents = request.agents() == null ? List.of() : request.agents();
        for (Map<String, Object> agent : agents) {
            if (!isSafeAgentKey(loadAgentId(agent))) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_LOAD_AGENT_ID_INVALID");
            }
        }
        return idempotentCommand(
                "M1_SUPPORT_LOAD_REBALANCE",
                idempotencyKey,
                requestHash(String.valueOf(request)),
                () -> rebalanceLoadOnce(idempotencyKey, request, agents));
    }

    private ApiResult<Map<String, Object>> rebalanceLoadOnce(
            String idempotencyKey,
            SupportLoadRebalanceRequest request,
            List<Map<String, Object>> agents) {
        long currentVersion = currentLoadVersionForUpdate();
        if (request.expectedVersion() != currentVersion) {
            return ApiResult.fail(409, "SUPPORT_LOAD_VERSION_CONFLICT");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        String remark = request.reason().trim();
        for (Map<String, Object> agent : agents) {
            String agentId = loadAgentId(agent);
            int cap = boundedInt(parseInt(stringValue(agent.get("cap"))), 0, 40, 8);
            upsertLoadValue("agent." + agentId + ".cap", String.valueOf(cap), "NUMBER", remark);
        }
        configFacade.upsertAdminValue(LOAD_PREFIX + "lastRebalanceAt", now.toString(), "STRING", LOAD_GROUP, remark);
        upsertLoadValue("version", String.valueOf(currentVersion + 1L), "NUMBER", remark);
        audit("M1_SUPPORT_LOAD_REBALANCED", "SUPPORT_LOAD_CONFIG", "m1.support.load", request.operator(), Map.of(
                "reason", remark,
                "idempotencyKey", idempotencyKey.trim(),
                "agentCount", agents.size(),
                "beforeVersion", currentVersion,
                "afterVersion", currentVersion + 1L,
                "rebalancedAt", now.toString()));
        Map<String, Object> view = loadConfigView();
        view.put("rebalanced", true);
        return ApiResult.ok(view);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private <T> ApiResult<T> idempotentCommand(
            String scope,
            String idempotencyKey,
            String requestHash,
            java.util.function.Supplier<ApiResult<T>> action) {
        return (ApiResult<T>) idempotencyService.executeRetained(
                scope + ":" + ownership.actorId(),
                idempotencyKey.trim(),
                requestHash,
                ApiResult.class,
                (java.util.function.Supplier) action);
    }

    private String requestHash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update((value == null ? "<null>" : value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private ApiResult<Map<String, Object>> requireSupportCommand(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason) || reason.trim().length() < 8 || reason.trim().length() > 200) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private Map<String, Object> loadConfigView() {
        var visibleAgents=supportAgentService.availabilityStates();
        Map<String, String> values = configFacade.activeValuesByGroup(LOAD_GROUP);
        int defaultCap = boundedInt(parseInt(values.get(LOAD_PREFIX + "defaultCap")), 0, 40, 8);
        Map<String, Object> loadConfig = new LinkedHashMap<>();
        loadConfig.put("autoBalance", boolValue(values, "autoBalance", false));
        loadConfig.put("version", boundedLong(parseLong(values.get(LOAD_VERSION_KEY)), 1L, Long.MAX_VALUE, 1L));
        loadConfig.put("defaultCap", defaultCap);
        loadConfig.put("burstCap", boundedInt(parseInt(values.get(LOAD_PREFIX + "burstCap")), 0, 40, 12));
        loadConfig.put("warnPct", boundedInt(parseInt(values.get(LOAD_PREFIX + "warnPct")), 50, 100, 80));
        loadConfig.put("quietHourBalance", boolValue(values, "quietHourBalance", false));
        loadConfig.put("overflowQueue", textValue(values, "overflowQueue", "转人工备勤队列"));

        Map<String, Map<String, Object>> agentState = new LinkedHashMap<>();
        String agentPrefix = LOAD_PREFIX + "agent.";
        values.forEach((key, value) -> {
            if (!key.startsWith(agentPrefix)) {
                return;
            }
            String suffix = key.substring(agentPrefix.length());
            int split = suffix.lastIndexOf('.');
            if (split <= 0 || split >= suffix.length() - 1) {
                return;
            }
            String agentId = suffix.substring(0, split);
            String field = suffix.substring(split + 1);
            if (!isSafeAgentKey(agentId) || !visibleAgents.containsKey(agentId)) {
                return;
            }
            Map<String, Object> state = agentState.computeIfAbsent(agentId, ignored -> new LinkedHashMap<>());
            if ("cap".equals(field)) {
                state.put("cap", boundedInt(parseInt(value), 0, 40, defaultCap));
            }
        });
        visibleAgents.forEach((id, state) ->
                agentState.computeIfAbsent(id, ignored -> new LinkedHashMap<>()).putAll(state));
        agentState.values().forEach(state -> {
            state.putIfAbsent("cap", defaultCap);
        });

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "M1");
        response.put("loadConfig", loadConfig);
        response.put("agentState", agentState);
        response.put("lastRebalanceAt", values.getOrDefault(LOAD_PREFIX + "lastRebalanceAt", ""));
        response.put("sources", List.of("nx_config_item:" + LOAD_GROUP, "nx_support_agent_profile", "nx_support_ticket", "nx_conversation"));
        return response;
    }

    private void upsertLoadValue(String suffix, String value, String valueType, String remark) {
        configFacade.upsertAdminValue(LOAD_PREFIX + suffix, value, valueType, LOAD_GROUP, remark);
    }

    private long currentLoadVersionForUpdate() {
        return boundedLong(
                configFacade.activeValueForUpdate(LOAD_VERSION_KEY).map(this::parseLong).orElse(null),
                1L,
                Long.MAX_VALUE,
                1L);
    }

    private boolean boolValue(Map<String, String> values, String suffix, boolean fallback) {
        String value = values.get(LOAD_PREFIX + suffix);
        return StringUtils.hasText(value) ? Boolean.parseBoolean(value) : fallback;
    }

    private String textValue(Map<String, String> values, String suffix, String fallback) {
        String value = values.get(LOAD_PREFIX + suffix);
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private Integer parseInt(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private Long parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private int boundedInt(Integer value, int min, int max, int fallback) {
        int raw = value == null ? fallback : value;
        return Math.max(min, Math.min(max, raw));
    }

    private long boundedLong(Long value, long min, long max, long fallback) {
        long raw = value == null ? fallback : value;
        return Math.max(min, Math.min(max, raw));
    }

    private String loadAgentId(Map<String, Object> agent) {
        if (agent == null) {
            return "";
        }
        String id = stringValue(agent.get("id"));
        if (!StringUtils.hasText(id)) {
            id = stringValue(agent.get("agentId"));
        }
        if (!StringUtils.hasText(id)) {
            id = stringValue(agent.get("value"));
        }
        return StringUtils.hasText(id) ? id.trim() : "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private boolean isSafeAgentKey(String agentId) {
        return StringUtils.hasText(agentId) && LOAD_AGENT_KEY.matcher(agentId.trim()).matches();
    }

    private String operator(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private String authenticatedOperator(String fallback) {
        return AdminActorResolver.resolve(operator(fallback));
    }

    private void audit(String action, String resourceType, String resourceId, String operator, Map<String, Object> detail) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(authenticatedOperator(operator))
                .result("SUCCESS")
                .riskLevel("MEDIUM")
                .detail(detail)
                .build());
    }
}

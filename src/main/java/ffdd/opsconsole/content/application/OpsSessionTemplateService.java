package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.SessionAdvisorPolicyView;
import ffdd.opsconsole.content.domain.SessionCategoryView;
import ffdd.opsconsole.content.domain.SessionCtaOption;
import ffdd.opsconsole.content.domain.SessionReplyTemplateView;
import ffdd.opsconsole.content.domain.SessionScriptView;
import ffdd.opsconsole.content.domain.SessionSegmentField;
import ffdd.opsconsole.content.domain.SessionTemplateOverview;
import ffdd.opsconsole.content.domain.SessionTemplateRepository;
import ffdd.opsconsole.content.domain.SessionWorkbenchPolicyView;
import ffdd.opsconsole.content.dto.SessionAdvisorPolicyUpdateRequest;
import ffdd.opsconsole.content.dto.SessionCategoryToggleRequest;
import ffdd.opsconsole.content.dto.SessionReplyTemplateCreateRequest;
import ffdd.opsconsole.content.dto.SessionReplyTemplateQueryRequest;
import ffdd.opsconsole.content.dto.SessionReplyTemplateStatusRequest;
import ffdd.opsconsole.content.dto.SessionScriptAudienceRequest;
import ffdd.opsconsole.content.dto.SessionScriptCreateRequest;
import ffdd.opsconsole.content.dto.SessionScriptQueryRequest;
import ffdd.opsconsole.content.dto.SessionScriptStatusRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsSessionTemplateService {
    private static final String CONFIG_GROUP = "content-session";
    private static final List<SessionCategorySeed> CATEGORY_SEEDS = List.of(
            new SessionCategorySeed("advisor", "专属顾问", "conversations.roleAdvisor", true, "增长 / 客服坐席", false),
            new SessionCategorySeed("support", "普通客服", "conversations.roleSupport", true, "客服坐席", false),
            new SessionCategorySeed("ai", "Nova AI 顾问", "conversations.roleAi", true, "Nova 自动(配置见 I2)", true));
    private static final List<String> AUDIENCE_OPTIONS = List.of("全量", "SFC 辖区 · 未重确认", "近 30 天提现偏高", "注册 ≤14 天", "P3 阶段活跃");
    private static final List<SessionSegmentField> SEGMENT_FIELDS = List.of(
            new SessionSegmentField("vrank", "V 等级", List.of(">=", "<=", "="), List.of("V0", "V1", "V2", "V3", "V4", "V5"), null),
            new SessionSegmentField("balance", "账户余额", List.of(">=", "<="), List.of(), "USDT"),
            new SessionSegmentField("withdraw30", "近 30 天提现额", List.of(">=", "<="), List.of(), "USDT"),
            new SessionSegmentField("holdings", "持仓节点数", List.of(">=", "<=", "="), List.of(), null),
            new SessionSegmentField("regdays", "注册天数", List.of("<=", ">="), List.of(), "天"),
            new SessionSegmentField("jurisdiction", "司法辖区", List.of("="), List.of("越南", "印尼", "泰国"), null),
            new SessionSegmentField("kyc", "KYC 状态", List.of("="), List.of("已通过", "待补充", "未提交"), null),
            new SessionSegmentField("device", "持有设备", List.of("="), List.of("NexionBox Air", "NexionBox Pro", "创世节点", "未购机"), null));
    private static final List<SessionCtaOption> CTA_OPTIONS = List.of(
            new SessionCtaOption("无跳转", "—", "content"),
            new SessionCtaOption("设备商城", "/store", "device"),
            new SessionCtaOption("锁仓 Staking", "/staking", "market"),
            new SessionCtaOption("创世节点", "/genesis", "market"));
    private static final List<String> SCRIPT_GROUPS = List.of("开场", "升级", "锁仓", "复投");
    private static final List<String> SCRIPT_STATUS_OPTIONS = List.of("published", "draft");
    private static final Set<String> SCRIPT_STATUSES = Set.copyOf(SCRIPT_STATUS_OPTIONS);
    private static final Set<String> TEMPLATE_TYPES = Set.of("advisor", "support");
    private static final DateTimeFormatter TEMPLATE_ID_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final SessionTemplateRepository templateRepository;
    private final PlatformConfigFacade configFacade;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;

    public ApiResult<SessionTemplateOverview> overview() {
        return ApiResult.ok(new SessionTemplateOverview(
                CATEGORY_SEEDS.stream().map(this::categoryView).toList(),
                advisorPolicy(),
                workbenchPolicy(),
                AUDIENCE_OPTIONS,
                SEGMENT_FIELDS,
                CTA_OPTIONS,
                SCRIPT_GROUPS,
                List.copyOf(TEMPLATE_TYPES),
                SCRIPT_STATUS_OPTIONS,
                templateRepository.listScripts(),
                templateRepository.listReplyTemplates(),
                List.of("nx_config_item:content-session", "nx_help_article:session_script", "nx_help_article:session_reply_template")));
    }

    public ApiResult<PageResult<SessionScriptView>> scripts(SessionScriptQueryRequest request) {
        return ApiResult.ok(templateRepository.pageScripts(request));
    }

    public ApiResult<PageResult<SessionReplyTemplateView>> replyTemplates(SessionReplyTemplateQueryRequest request) {
        return ApiResult.ok(templateRepository.pageReplyTemplates(request));
    }

    public ApiResult<SessionCategoryView> updateCategory(String type, String idempotencyKey, SessionCategoryToggleRequest request) {
        SessionCategorySeed seed = categorySeed(type);
        if (seed == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SESSION_CATEGORY_UNSUPPORTED");
        }
        if (seed.readOnly()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "SESSION_CATEGORY_READONLY");
        }
        ApiResult<SessionCategoryView> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request.enabled() == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SESSION_CATEGORY_ENABLED_REQUIRED");
        }
        String key = categoryKey(seed.type());
        String value = request.enabled() ? "on" : "off";
        configFacade.upsertAdminValue(key, value, "BOOLEAN", CONFIG_GROUP, request.reason().trim());
        SessionCategoryView updated = new SessionCategoryView(seed.type(), seed.name(), seed.roleKey(), request.enabled(), seed.managedBy(), false);
        audit("M5_SESSION_CATEGORY_UPDATED", seed.type(), request.operator(), detail(
                "enabled", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<SessionAdvisorPolicyView> updateAdvisorPolicy(
            String field,
            String idempotencyKey,
            SessionAdvisorPolicyUpdateRequest request) {
        String normalizedField = normalizeField(field);
        if (!List.of("enabled", "delayMs", "cooldownHours", "maxPerSession", "audience").contains(normalizedField)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SESSION_POLICY_FIELD_UNSUPPORTED");
        }
        ApiResult<SessionAdvisorPolicyView> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String value;
        try {
            value = normalizePolicyValue(normalizedField, request.value());
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        configFacade.upsertAdminValue(policyKey(normalizedField), value, valueType(normalizedField), CONFIG_GROUP, request.reason().trim());
        audit("M5_SESSION_ADVISOR_POLICY_UPDATED", normalizedField, request.operator(), detail(
                "value", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(advisorPolicy());
    }

    public ApiResult<SessionWorkbenchPolicyView> updateWorkbenchPolicy(
            String field,
            String idempotencyKey,
            SessionAdvisorPolicyUpdateRequest request) {
        String normalizedField = normalizeField(field);
        if (!"timeoutFallback".equals(normalizedField)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SESSION_WORKBENCH_POLICY_FIELD_UNSUPPORTED");
        }
        ApiResult<SessionWorkbenchPolicyView> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String value;
        try {
            value = normalizeWorkbenchPolicyValue(normalizedField, request.value());
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        configFacade.upsertAdminValue(workbenchKey(normalizedField), value, "BOOLEAN", CONFIG_GROUP, request.reason().trim());
        audit("M3_SESSION_WORKBENCH_POLICY_UPDATED", normalizedField, request.operator(), detail(
                "value", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(workbenchPolicy());
    }

    public ApiResult<SessionScriptView> createScript(String idempotencyKey, SessionScriptCreateRequest request) {
        ApiResult<SessionScriptView> guard = requireScriptCreate(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        String scriptId = "AS-" + LocalDateTime.now(clock).format(TEMPLATE_ID_TIME);
        SessionScriptView created = templateRepository.createScript(scriptId, normalizeScriptRequest(request), LocalDateTime.now(clock));
        audit("M5_SESSION_SCRIPT_CREATED", created.id(), request.operator(), detail(
                "scriptGroup", created.scriptGroup(),
                "ctaPath", created.ctaPath(),
                "audience", created.audience(),
                "status", created.status(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(created);
    }

    public ApiResult<SessionScriptView> updateScriptStatus(String scriptId, String idempotencyKey, SessionScriptStatusRequest request) {
        if (!StringUtils.hasText(scriptId)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SESSION_SCRIPT_ID_REQUIRED");
        }
        ApiResult<SessionScriptView> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String status = normalizeScriptStatus(request.status());
        if (!SCRIPT_STATUSES.contains(status)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SESSION_SCRIPT_STATUS_UNSUPPORTED");
        }
        SessionScriptView current = templateRepository.findScript(scriptId.trim()).orElse(null);
        if (current == null) {
            return ApiResult.fail(404, "SESSION_SCRIPT_NOT_FOUND");
        }
        if (status.equals(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        templateRepository.updateScriptStatus(current.id(), status, LocalDateTime.now(clock));
        SessionScriptView updated = templateRepository.findScript(current.id()).orElse(current);
        audit("M5_SESSION_SCRIPT_STATUS_CHANGED", current.id(), request.operator(), detail(
                "from", current.status(),
                "to", status,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<SessionScriptView> updateScriptAudience(String scriptId, String idempotencyKey, SessionScriptAudienceRequest request) {
        if (!StringUtils.hasText(scriptId)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SESSION_SCRIPT_ID_REQUIRED");
        }
        ApiResult<SessionScriptView> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String audience = requireOption(request.audience(), AUDIENCE_OPTIONS, "SESSION_SCRIPT_AUDIENCE_UNSUPPORTED");
        SessionScriptView current = templateRepository.findScript(scriptId.trim()).orElse(null);
        if (current == null) {
            return ApiResult.fail(404, "SESSION_SCRIPT_NOT_FOUND");
        }
        templateRepository.updateScriptAudience(current.id(), audience, LocalDateTime.now(clock));
        SessionScriptView updated = templateRepository.findScript(current.id()).orElse(current);
        audit("M5_SESSION_SCRIPT_AUDIENCE_CHANGED", current.id(), request.operator(), detail(
                "audience", audience,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<SessionReplyTemplateView> createReplyTemplate(String idempotencyKey, SessionReplyTemplateCreateRequest request) {
        ApiResult<SessionReplyTemplateView> guard = requireReplyTemplateCreate(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        String templateId = "RT-" + LocalDateTime.now(clock).format(TEMPLATE_ID_TIME);
        SessionReplyTemplateView created = templateRepository.createReplyTemplate(templateId, normalizeReplyTemplateRequest(request), LocalDateTime.now(clock));
        audit("M5_SESSION_REPLY_TEMPLATE_CREATED", created.id(), request.operator(), detail(
                "type", created.type(),
                "status", created.status(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(created);
    }

    public ApiResult<SessionReplyTemplateView> updateReplyTemplateStatus(
            String templateId,
            String idempotencyKey,
            SessionReplyTemplateStatusRequest request) {
        if (!StringUtils.hasText(templateId)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SESSION_REPLY_TEMPLATE_ID_REQUIRED");
        }
        ApiResult<SessionReplyTemplateView> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String status = normalizeScriptStatus(request.status());
        if (!SCRIPT_STATUSES.contains(status)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SESSION_REPLY_TEMPLATE_STATUS_UNSUPPORTED");
        }
        SessionReplyTemplateView current = templateRepository.findReplyTemplate(templateId.trim()).orElse(null);
        if (current == null) {
            return ApiResult.fail(404, "SESSION_REPLY_TEMPLATE_NOT_FOUND");
        }
        if (status.equals(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        templateRepository.updateReplyTemplateStatus(current.id(), status, LocalDateTime.now(clock));
        SessionReplyTemplateView updated = templateRepository.findReplyTemplate(current.id()).orElse(current);
        audit("M5_SESSION_REPLY_TEMPLATE_STATUS_CHANGED", current.id(), request.operator(), detail(
                "from", current.status(),
                "to", status,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    private SessionAdvisorPolicyView advisorPolicy() {
        return new SessionAdvisorPolicyView(
                "on".equalsIgnoreCase(configValue(policyKey("enabled"), "")),
                intConfig(policyKey("delayMs"), 0),
                intConfig(policyKey("cooldownHours"), 0),
                intConfig(policyKey("maxPerSession"), 0),
                configValue(policyKey("audience"), ""));
    }

    private SessionWorkbenchPolicyView workbenchPolicy() {
        return new SessionWorkbenchPolicyView("on".equalsIgnoreCase(configValue(workbenchKey("timeoutFallback"), "")));
    }

    private SessionCategoryView categoryView(SessionCategorySeed seed) {
        boolean enabled = "on".equalsIgnoreCase(configValue(categoryKey(seed.type()), ""));
        return new SessionCategoryView(seed.type(), seed.name(), seed.roleKey(), enabled, seed.managedBy(), seed.readOnly());
    }

    private SessionCategorySeed categorySeed(String type) {
        String normalized = StringUtils.hasText(type) ? type.trim().toLowerCase(Locale.ROOT) : "";
        return CATEGORY_SEEDS.stream().filter(seed -> seed.type().equals(normalized)).findFirst().orElse(null);
    }

    private <T> ApiResult<T> requireCommand(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason) || reason.trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private ApiResult<SessionScriptView> requireScriptCreate(String idempotencyKey, SessionScriptCreateRequest request) {
        ApiResult<SessionScriptView> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        try {
            normalizeScriptRequest(request);
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        return null;
    }

    private ApiResult<SessionReplyTemplateView> requireReplyTemplateCreate(String idempotencyKey, SessionReplyTemplateCreateRequest request) {
        ApiResult<SessionReplyTemplateView> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        try {
            normalizeReplyTemplateRequest(request);
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        return null;
    }

    private SessionScriptCreateRequest normalizeScriptRequest(SessionScriptCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("SESSION_SCRIPT_REQUIRED");
        }
        String group = requireOption(request.scriptGroup(), SCRIPT_GROUPS, "SESSION_SCRIPT_GROUP_UNSUPPORTED");
        String text = requireText(request.text(), "SESSION_SCRIPT_TEXT_REQUIRED", 400);
        rejectJsonOrUrlText(text, "SESSION_SCRIPT_TEXT_INVALID");
        String ctaPath = requireCtaPath(request.ctaPath());
        String audience = requireOption(request.audience(), AUDIENCE_OPTIONS, "SESSION_SCRIPT_AUDIENCE_UNSUPPORTED");
        String status = normalizeScriptStatus(request.status());
        if (!SCRIPT_STATUSES.contains(status)) {
            throw new IllegalArgumentException("SESSION_SCRIPT_STATUS_UNSUPPORTED");
        }
        return new SessionScriptCreateRequest(group, text, ctaPath, audience, status, operator(request.operator()), request.reason().trim());
    }

    private SessionReplyTemplateCreateRequest normalizeReplyTemplateRequest(SessionReplyTemplateCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("SESSION_REPLY_TEMPLATE_REQUIRED");
        }
        String type = StringUtils.hasText(request.type()) ? request.type().trim().toLowerCase(Locale.ROOT) : "";
        if (!TEMPLATE_TYPES.contains(type)) {
            throw new IllegalArgumentException("SESSION_REPLY_TEMPLATE_TYPE_UNSUPPORTED");
        }
        String text = requireText(request.text(), "SESSION_REPLY_TEMPLATE_TEXT_REQUIRED", 400);
        rejectJsonOrUrlText(text, "SESSION_REPLY_TEMPLATE_TEXT_INVALID");
        String status = normalizeScriptStatus(request.status());
        if (!SCRIPT_STATUSES.contains(status)) {
            throw new IllegalArgumentException("SESSION_REPLY_TEMPLATE_STATUS_UNSUPPORTED");
        }
        return new SessionReplyTemplateCreateRequest(type, text, status, operator(request.operator()), request.reason().trim());
    }

    private String normalizePolicyValue(String field, String value) {
        return switch (field) {
            case "enabled" -> parseBooleanSwitch(value);
            case "delayMs" -> String.valueOf(parseIntRange(value, 0, 60_000, "SESSION_POLICY_DELAY_INVALID"));
            case "cooldownHours" -> String.valueOf(parseIntRange(value, 1, 720, "SESSION_POLICY_COOLDOWN_INVALID"));
            case "maxPerSession" -> String.valueOf(parseIntRange(value, 0, 20, "SESSION_POLICY_MAX_INVALID"));
            case "audience" -> requireOption(value, AUDIENCE_OPTIONS, "SESSION_POLICY_AUDIENCE_UNSUPPORTED");
            default -> throw new IllegalArgumentException("SESSION_POLICY_FIELD_UNSUPPORTED");
        };
    }

    private String normalizeWorkbenchPolicyValue(String field, String value) {
        return switch (field) {
            case "timeoutFallback" -> parseBooleanSwitch(value);
            default -> throw new IllegalArgumentException("SESSION_WORKBENCH_POLICY_FIELD_UNSUPPORTED");
        };
    }

    private String requireCtaPath(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim() : "—";
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            throw new IllegalArgumentException("SESSION_SCRIPT_CTA_MUST_BE_BACKEND_OPTION");
        }
        return CTA_OPTIONS.stream()
                .map(SessionCtaOption::value)
                .filter(option -> option.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("SESSION_SCRIPT_CTA_UNSUPPORTED"));
    }

    private String requireOption(String value, List<String> options, String message) {
        String normalized = requireText(value, message, 80);
        return options.stream()
                .filter(option -> option.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(message));
    }

    private String requireText(String value, String message, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(message);
        }
        return trimmed;
    }

    private void rejectJsonOrUrlText(String value, String message) {
        String trimmed = value.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.contains("http://") || trimmed.contains("https://")) {
            throw new IllegalArgumentException(message);
        }
    }

    private String normalizeScriptStatus(String status) {
        String normalized = StringUtils.hasText(status) ? status.trim().toLowerCase(Locale.ROOT) : "draft";
        return "archived".equals(normalized) ? "draft" : normalized;
    }

    private String normalizeField(String field) {
        if (!StringUtils.hasText(field)) {
            return "";
        }
        return switch (field.trim().toLowerCase(Locale.ROOT)) {
            case "enabled" -> "enabled";
            case "delayms", "delay-ms" -> "delayMs";
            case "cooldownhours", "cooldown-hours" -> "cooldownHours";
            case "maxpersession", "max-per-session" -> "maxPerSession";
            case "audience" -> "audience";
            case "timeoutfallback", "timeout-fallback" -> "timeoutFallback";
            default -> field.trim();
        };
    }

    private String parseBooleanSwitch(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
        return switch (normalized) {
            case "on", "true", "1", "enabled" -> "on";
            case "off", "false", "0", "disabled" -> "off";
            default -> throw new IllegalArgumentException("SESSION_POLICY_ENABLED_INVALID");
        };
    }

    private int parseIntRange(String value, int min, int max, String message) {
        try {
            int parsed = Integer.parseInt(requireText(value, message, 16));
            if (parsed < min || parsed > max) {
                throw new IllegalArgumentException(message);
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(message);
        }
    }

    private int intConfig(String key, int fallback) {
        try {
            return Integer.parseInt(configValue(key, String.valueOf(fallback)));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private String configValue(String key, String fallback) {
        return configFacade.activeValue(key)
                .filter(StringUtils::hasText)
                .orElse("");
    }

    private String categoryKey(String type) {
        return "I.session.cat." + type + ".enabled";
    }

    private String policyKey(String field) {
        return "I.session.advisor.policy." + field;
    }

    private String workbenchKey(String field) {
        return "I.session.workbench." + field;
    }

    private String valueType(String field) {
        return switch (field) {
            case "enabled" -> "BOOLEAN";
            case "audience" -> "STRING";
            default -> "NUMBER";
        };
    }

    private String operator(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private Map<String, Object> detail(Object... pairs) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            detail.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return detail;
    }

    private void audit(String action, String resourceId, String operator, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("SESSION_TEMPLATE")
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(operator(operator))
                .result("SUCCESS")
                .riskLevel("LOW")
                .detail(detail)
                .build());
    }

    private record SessionCategorySeed(
            String type,
            String name,
            String roleKey,
            boolean enabled,
            String managedBy,
            boolean readOnly) {
    }
}

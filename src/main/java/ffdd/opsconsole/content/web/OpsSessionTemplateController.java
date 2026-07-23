package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.OpsSessionTemplateService;
import ffdd.opsconsole.content.application.OpsSupportAgentService;
import ffdd.opsconsole.content.domain.SessionAdvisorPolicyView;
import ffdd.opsconsole.content.domain.SessionCategoryView;
import ffdd.opsconsole.content.domain.SessionReplyTemplateView;
import ffdd.opsconsole.content.domain.SessionScriptView;
import ffdd.opsconsole.content.domain.SessionTemplateOverview;
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
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Supplier;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/session-templates")
@RequiredArgsConstructor
public class OpsSessionTemplateController {
    private final OpsSessionTemplateService templateService;
    private final OpsSupportAgentService supportAgentService;
    private final AdminIdempotencyService idempotencyService;
    private final AuditLogService auditLogService;

    @SuppressWarnings({"rawtypes", "unchecked"})
    private <T> ApiResult<T> executeCommand(
            String scope,
            String idempotencyKey,
            Object request,
            Supplier<ApiResult<T>> action) {
        ApiResult<T> result;
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            result = action.get();
        } else {
            result = (ApiResult<T>) idempotencyService.execute(
                    scope,
                    idempotencyKey.trim(),
                    requestHash(String.valueOf(request)),
                    ApiResult.class,
                    (Supplier) action);
        }
        if (result != null && result.getCode() != 0) {
            auditRejected(scope, result.getCode(), result.getMessage(), request);
        }
        return result;
    }

    private String requestHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private <T> ApiResult<T> executeManagedCommand(
            String scope,
            String idempotencyKey,
            Object request,
            Supplier<ApiResult<T>> action) {
        if (!supportAgentService.canManageSupportSeats()) {
            ApiResult<T> rejected = ApiResult.fail(403, "M5_CONFIGURATION_MANAGEMENT_FORBIDDEN");
            auditRejected(scope, rejected.getCode(), rejected.getMessage(), request);
            return rejected;
        }
        return executeCommand(scope, idempotencyKey, request, action);
    }

    private void auditRejected(String scope, int code, String message, Object request) {
        auditLogService.recordRequiredInNewTransaction(AuditLogWriteRequest.builder()
                .action("M5_CONFIGURATION_REJECTED")
                .resourceType("SESSION_TEMPLATE")
                .resourceId(scope)
                .bizNo(scope)
                .actorType("ADMIN")
                .actorUsername(AdminActorResolver.resolve("system"))
                .result("REJECTED")
                .riskLevel(code == 403 ? "HIGH" : "MEDIUM")
                .detail(Map.of(
                        "scope", scope,
                        "errorCode", code,
                        "error", message == null ? "UNKNOWN" : message,
                        "reason", requestReason(request)))
                .build());
    }

    private String requestReason(Object request) {
        if (request == null) {
            return "";
        }
        try {
            Object value = request.getClass().getMethod("reason").invoke(request);
            return value == null ? "" : String.valueOf(value);
        } catch (ReflectiveOperationException ignored) {
            return "";
        }
    }

    // 模板总览（会话类别/推送策略/话术/模板） — M5 话术与模板配置 读
    @PreAuthorize("hasAuthority('service_m5_read')")
    @GetMapping("/overview")
    public ApiResult<SessionTemplateOverview> overview() {
        return templateService.overview();
    }

    // 话术列表 — M5 话术与模板配置 读
    @PreAuthorize("hasAuthority('service_m5_read')")
    @GetMapping("/scripts")
    public ApiResult<PageResult<SessionScriptView>> scripts(SessionScriptQueryRequest request) {
        return templateService.scripts(request);
    }

    // 模板列表 — M5 话术与模板配置 读
    @PreAuthorize("hasAuthority('service_m5_read')")
    @GetMapping("/reply-templates")
    public ApiResult<PageResult<SessionReplyTemplateView>> replyTemplates(SessionReplyTemplateQueryRequest request) {
        return templateService.replyTemplates(request);
    }

    // 会话类别启停 — M5 话术与模板配置 写
    @PreAuthorize("hasAuthority('service_m5_write')")
    @PatchMapping("/categories/{type}")
    public ApiResult<SessionCategoryView> updateCategory(
            @PathVariable String type,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SessionCategoryToggleRequest request) {
        return executeManagedCommand("M5_CATEGORY_UPDATE:" + type, idempotencyKey, request,
                () -> templateService.updateCategory(type, idempotencyKey, request));
    }

    // 顾问推送策略（开关/延迟/冷却/上限/受众） — M5 话术与模板配置 写
    @PreAuthorize("hasAuthority('service_m5_write')")
    @PatchMapping("/advisor-policy/{field}")
    public ApiResult<SessionAdvisorPolicyView> updateAdvisorPolicy(
            @PathVariable String field,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SessionAdvisorPolicyUpdateRequest request) {
        return executeManagedCommand("M5_ADVISOR_POLICY_UPDATE:" + field, idempotencyKey, request,
                () -> templateService.updateAdvisorPolicy(field, idempotencyKey, request));
    }

    // 工作台推送策略 — M5 话术与模板配置 写
    @PreAuthorize("hasAuthority('service_m3_write')")
    @PatchMapping("/workbench-policy/{field}")
    public ApiResult<SessionWorkbenchPolicyView> updateWorkbenchPolicy(
            @PathVariable String field,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SessionAdvisorPolicyUpdateRequest request) {
        return executeCommand("M3_WORKBENCH_POLICY_UPDATE:" + field, idempotencyKey, request,
                () -> templateService.updateWorkbenchPolicy(field, idempotencyKey, request));
    }

    // 话术新增 — M5 话术与模板配置 写
    @PreAuthorize("hasAuthority('service_m5_write')")
    @PostMapping("/scripts")
    public ApiResult<SessionScriptView> createScript(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SessionScriptCreateRequest request) {
        return executeManagedCommand("M5_SCRIPT_CREATE", idempotencyKey, request,
                () -> templateService.createScript(idempotencyKey, request));
    }

    // 话术发布/上下架 — M5 话术与模板配置 写
    @PreAuthorize("hasAuthority('service_m5_write')")
    @PatchMapping("/scripts/{scriptId}/status")
    public ApiResult<SessionScriptView> updateScriptStatus(
            @PathVariable String scriptId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SessionScriptStatusRequest request) {
        return executeManagedCommand("M5_SCRIPT_STATUS:" + scriptId, idempotencyKey, request,
                () -> templateService.updateScriptStatus(scriptId, idempotencyKey, request));
    }

    // 话术受众配置 — M5 话术与模板配置 写
    @PreAuthorize("hasAuthority('service_m5_write')")
    @PatchMapping("/scripts/{scriptId}/audience")
    public ApiResult<SessionScriptView> updateScriptAudience(
            @PathVariable String scriptId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SessionScriptAudienceRequest request) {
        return executeManagedCommand("M5_SCRIPT_AUDIENCE:" + scriptId, idempotencyKey, request,
                () -> templateService.updateScriptAudience(scriptId, idempotencyKey, request));
    }

    // 模板新增 — M5 话术与模板配置 写
    @PreAuthorize("hasAuthority('service_m5_write')")
    @PostMapping("/reply-templates")
    public ApiResult<SessionReplyTemplateView> createReplyTemplate(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SessionReplyTemplateCreateRequest request) {
        return executeManagedCommand("M5_REPLY_TEMPLATE_CREATE", idempotencyKey, request,
                () -> templateService.createReplyTemplate(idempotencyKey, request));
    }

    // 模板启停 — M5 话术与模板配置 写
    @PreAuthorize("hasAuthority('service_m5_write')")
    @PatchMapping("/reply-templates/{templateId}/status")
    public ApiResult<SessionReplyTemplateView> updateReplyTemplateStatus(
            @PathVariable String templateId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SessionReplyTemplateStatusRequest request) {
        return executeManagedCommand("M5_REPLY_TEMPLATE_STATUS:" + templateId, idempotencyKey, request,
                () -> templateService.updateReplyTemplateStatus(templateId, idempotencyKey, request));
    }
}

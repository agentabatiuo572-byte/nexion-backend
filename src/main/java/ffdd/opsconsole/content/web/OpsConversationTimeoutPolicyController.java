package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.application.ConversationTimeoutPolicyService;
import ffdd.opsconsole.content.domain.ConversationTimeoutPolicy;
import ffdd.opsconsole.content.dto.ConversationTimeoutPolicyUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/conversations/timeout-policy")
@RequiredArgsConstructor
public class OpsConversationTimeoutPolicyController {
    private static final String SCOPE = "M3_CONVERSATION_TIMEOUT_POLICY";

    private final ConversationTimeoutPolicyService service;
    private final AdminIdempotencyService idempotencyService;
    private final AuditLogService auditLogService;

    @PreAuthorize("hasAuthority('service_m3_read')")
    @GetMapping
    public ApiResult<ConversationTimeoutPolicy> current() {
        return service.current();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @PreAuthorize("hasAuthority('service_m3_timeout_manage')")
    @PutMapping
    public ApiResult<ConversationTimeoutPolicy> update(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ConversationTimeoutPolicyUpdateRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            ApiResult<ConversationTimeoutPolicy> rejected = ApiResult.fail(
                    OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(),
                    OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
            auditRejected(rejected, request);
            return rejected;
        }
        ApiResult<ConversationTimeoutPolicy> result;
        try {
            result = (ApiResult<ConversationTimeoutPolicy>) idempotencyService.execute(
                    SCOPE,
                    idempotencyKey.trim(),
                    requestHash(String.valueOf(request)),
                    ApiResult.class,
                    (Supplier) () -> service.update(request));
        } catch (BizException rejected) {
            result = ApiResult.fail(rejected.getCode(), rejected.getMessage());
        }
        if (result.getCode() != 0) {
            auditRejected(result, request);
        }
        return result;
    }

    private void auditRejected(ApiResult<?> result, ConversationTimeoutPolicyUpdateRequest request) {
        auditLogService.recordRequiredInNewTransaction(AuditLogWriteRequest.builder()
                .action("M3_CONVERSATION_TIMEOUT_POLICY_REJECTED")
                .resourceType("CONVERSATION_TIMEOUT_POLICY")
                .resourceId("GLOBAL")
                .actorType("ADMIN")
                .actorUsername(AdminActorResolver.resolve(request == null ? null : request.operator()))
                .result("REJECTED")
                .riskLevel("HIGH")
                .detail(Map.of(
                        "code", result.getCode(),
                        "message", String.valueOf(result.getMessage())))
                .build());
    }

    private String requestHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}

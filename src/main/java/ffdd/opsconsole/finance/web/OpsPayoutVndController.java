package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.finance.application.PayoutVndCommandBoundary;
import ffdd.opsconsole.finance.application.PayoutVndConfigService;
import ffdd.opsconsole.finance.dto.PayoutVndChannelUpdateRequest;
import ffdd.opsconsole.finance.dto.PayoutVndConfigUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/finance/payout-vnd")
@RequiredArgsConstructor
public class OpsPayoutVndController {
    private final PayoutVndConfigService service;
    private final PayoutVndCommandBoundary commands;
    private final AuditLogService audit;

    @GetMapping("/config")
    @PreAuthorize("hasAuthority('finance_d7_read')")
    public ApiResult<Map<String, Object>> overview() {
        return service.overview();
    }

    @PatchMapping("/config")
    @PreAuthorize("hasAuthority('finance_d7_manage') and (#request == null or #request.forceInverted != true or hasAuthority('finance_d7_force_inverted'))")
    public ApiResult<Map<String, Object>> updateConfig(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) PayoutVndConfigUpdateRequest request) {
        return executeAudited("D7_PAYOUT_VND_CONFIG_REJECTED", "CONFIG_UPDATE",
                idempotencyKey, request, () -> service.update(request));
    }

    @PatchMapping("/channel")
    @PreAuthorize("hasAuthority('finance_d7_channel_toggle')")
    public ApiResult<Map<String, Object>> updateChannel(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) PayoutVndChannelUpdateRequest request) {
        return executeAudited("D7_PAYOUT_VND_CHANNEL_REJECTED", "CHANNEL_UPDATE",
                idempotencyKey, request, () -> service.updateChannel(request));
    }

    private ApiResult<Map<String, Object>> executeAudited(
            String action,
            String operation,
            String idempotencyKey,
            Object request,
            Supplier<ApiResult<Map<String, Object>>> write) {
        try {
            // The rejection audit belongs inside the idempotent action. A replay
            // returns the stored ApiResult without executing this supplier again.
            return commands.execute(operation, idempotencyKey, request,
                    () -> auditedWrite(action, idempotencyKey, write));
        } catch (BizException rejectedBeforeClaim) {
            return auditRejected(action, idempotencyKey,
                    ApiResult.fail(rejectedBeforeClaim.getCode(), rejectedBeforeClaim.getMessage()));
        }
    }

    private ApiResult<Map<String, Object>> auditedWrite(
            String action,
            String idempotencyKey,
            Supplier<ApiResult<Map<String, Object>>> operation) {
        ApiResult<Map<String, Object>> result;
        try {
            result = operation.get();
        } catch (BizException rejected) {
            result = ApiResult.fail(rejected.getCode(), rejected.getMessage());
        }
        return auditRejected(action, idempotencyKey, result);
    }

    private ApiResult<Map<String, Object>> auditRejected(
            String action,
            String idempotencyKey,
            ApiResult<Map<String, Object>> result) {
        if (result.getCode() != 0) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("code", result.getCode());
            detail.put("message", String.valueOf(result.getMessage()));
            detail.put("idempotencyKey", normalizedCommandKey(idempotencyKey));
            audit.recordRequiredInNewTransaction(AuditLogWriteRequest.builder()
                    .action(action)
                    .resourceType("PAYOUT_VND_CONFIG")
                    .resourceId("D7")
                    .actorType("ADMIN")
                    .actorUsername(AdminActorResolver.resolve("system"))
                    .result("REJECTED")
                    .riskLevel("CRITICAL")
                    .detail(detail)
                    .build());
        }
        return result;
    }

    private String normalizedCommandKey(String value) {
        if (value == null || value.isBlank()) return "MISSING";
        String normalized = value.trim();
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }
}

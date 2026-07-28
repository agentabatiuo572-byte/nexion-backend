package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.finance.application.OpsFinanceService;
import ffdd.opsconsole.finance.dto.WithdrawalLimitsUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/withdraw/limits")
@RequiredArgsConstructor
public class OpsWithdrawalLimitsController {
    private final OpsFinanceService financeService;
    private final AuditLogService auditLogService;
    private final AdminIdempotencyService idempotencyService;

    @GetMapping
    @PreAuthorize("hasAuthority('finance_d5_read')")
    public ResponseEntity<?> getLimits() {
        return response(financeService.withdrawalLimits());
    }

    @PutMapping
    @PreAuthorize("@d5WithdrawalAuthorization.canUpdateLimits(authentication,#request)")
    public ResponseEntity<?> updateLimits(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) WithdrawalLimitsUpdateRequest request) {
        ApiResult<Map<String, Object>> result = financeService.updateWithdrawalLimits(idempotencyKey, request);
        if (result.getCode() != 0) {
            auditRejectedOnce(idempotencyKey, request, result);
        }
        return response(result);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void auditRejectedOnce(
            String idempotencyKey,
            WithdrawalLimitsUpdateRequest request,
            ApiResult<Map<String, Object>> result) {
        java.util.function.Supplier<Boolean> write = () -> {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("code", result.getMessage() == null ? "D5_REQUEST_FAILED" : result.getMessage());
            detail.put("httpStatus", result.getCode());
            detail.put("expectedVersion", request == null ? null : request.getExpectedVersion());
            detail.put("changedFields", request == null ? java.util.List.of() : request.changedD5Fields());
            auditLogService.recordRequiredInNewTransaction(AuditLogWriteRequest.builder()
                    .action("D5_WITHDRAWAL_LIMITS_REJECTED")
                    .resourceType("WITHDRAWAL_LIMITS")
                    .resourceId("D5")
                    .actorUsername(AdminActorResolver.resolve(request == null ? null : request.getOperator()))
                    .result("REJECTED")
                    .riskLevel("HIGH")
                    .detail(detail)
                    .build());
            return Boolean.TRUE;
        };
        if (idempotencyKey != null && !idempotencyKey.isBlank() && idempotencyKey.trim().length() <= 128) {
            String material = result.getCode() + "|" + result.getMessage() + "|"
                    + (request == null ? "null" : request.getExpectedVersion()) + "|"
                    + (request == null ? java.util.List.of() : request.changedD5Fields());
            idempotencyService.execute(
                    "D5_WITHDRAWAL_LIMITS_REJECTED_AUDIT",
                    idempotencyKey.trim(),
                    sha256(material),
                    Boolean.class,
                    (java.util.function.Supplier) write);
        } else {
            write.get();
        }
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private ResponseEntity<?> response(ApiResult<Map<String, Object>> result) {
        if (result.getCode() == 0) {
            return ResponseEntity.ok(result);
        }
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", result.getMessage());
        error.put("message", errorMessage(result.getMessage()));
        if (result.getData() != null) {
            error.putAll(result.getData());
        }
        int status = "REASON_REQUIRED".equals(result.getMessage()) ? 400 : result.getCode();
        return ResponseEntity.status(status).body(error);
    }

    private String errorMessage(String code) {
        return switch (code == null ? "" : code) {
            case "PHASE_PARAM_READONLY" ->
                    "cooldownDays/penaltyFeeRate/complianceHoldEnabled 为 Phase 派发参数，请经 H1 调整";
            case "COVERAGE_BELOW_REDLINE" -> "当前兑付覆盖率低于红线，不能放大资金流出";
            case "CONFIG_VERSION_CONFLICT" -> "参数已被其他运营员更新，请刷新后重试";
            case "REASON_REQUIRED" -> "变更理由必须为 8-200 字";
            default -> code == null ? "D5_REQUEST_FAILED" : code;
        };
    }
}

package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.finance.application.OpsFinanceService;
import ffdd.opsconsole.finance.dto.WithdrawalLimitsUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
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
        return response(financeService.updateWithdrawalLimits(idempotencyKey, request));
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

package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.application.CregisPayoutRecoveryService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/finance/withdrawals")
public class OpsCregisPayoutRecoveryController {
    private final CregisPayoutRecoveryService recovery;

    @GetMapping("/{withdrawalNo}/cregis")
    @PreAuthorize("hasAuthority('finance_d2_read')")
    public ApiResult<CregisPayoutRecoveryService.Detail> detail(@PathVariable String withdrawalNo) {
        return ApiResult.ok(recovery.detail(withdrawalNo));
    }

    @PostMapping("/{withdrawalNo}/cregis/recover")
    @PreAuthorize("hasAuthority('finance_d2_read') and hasAuthority('finance_d2_withdrawal_approve')")
    public ApiResult<CregisPayoutRecoveryService.Result> recover(@PathVariable String withdrawalNo,
            @RequestHeader("Idempotency-Key") String key, @RequestBody RecoveryRequest request) {
        if (request == null) throw new BizException(422, "CREGIS_PAYOUT_RECOVERY_REQUEST_INVALID");
        return ApiResult.ok(recovery.recover(withdrawalNo, key, request.providerCid(), request.reason()));
    }

    public record RecoveryRequest(String providerCid, String reason) {}
}

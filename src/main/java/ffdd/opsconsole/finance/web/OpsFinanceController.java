package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.finance.application.OpsFinanceService;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import ffdd.opsconsole.finance.dto.WithdrawalParamUpdateRequest;
import ffdd.opsconsole.finance.dto.WithdrawalReviewRequest;
import java.util.Map;
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
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/finance")
public class OpsFinanceController {
    private final OpsFinanceService financeService;

    public OpsFinanceController(OpsFinanceService financeService) {
        this.financeService = financeService;
    }

    @GetMapping("/withdrawal-params")
    @PreAuthorize("hasAuthority('PERM_WITHDRAWAL_READ')")
    public ApiResult<Map<String, Object>> withdrawalParams() {
        return financeService.withdrawalParams();
    }

    @PatchMapping("/withdrawal-params")
    @PreAuthorize("hasAuthority('PERM_WITHDRAWAL_REVIEW')")
    public ApiResult<Map<String, Object>> updateWithdrawalParam(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) WithdrawalParamUpdateRequest request) {
        return financeService.updateWithdrawalParam(idempotencyKey, request);
    }

    @GetMapping("/withdrawals/{withdrawalNo}")
    @PreAuthorize("hasAuthority('PERM_WITHDRAWAL_READ')")
    public ApiResult<WithdrawalOrderView> withdrawalDetail(@PathVariable String withdrawalNo) {
        return financeService.withdrawalDetail(withdrawalNo);
    }

    @PostMapping("/withdrawals/{withdrawalNo}/review")
    @PreAuthorize("hasAuthority('PERM_WITHDRAWAL_REVIEW')")
    public ApiResult<WithdrawalOrderView> reviewWithdrawal(
            @PathVariable String withdrawalNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) WithdrawalReviewRequest request) {
        return financeService.reviewWithdrawal(withdrawalNo, idempotencyKey, request);
    }
}

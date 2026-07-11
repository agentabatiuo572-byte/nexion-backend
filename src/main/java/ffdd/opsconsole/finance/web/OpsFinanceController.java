package ffdd.opsconsole.finance.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.finance.application.OpsFinanceService;
import ffdd.opsconsole.finance.domain.DepositFlowView;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import ffdd.opsconsole.finance.dto.TopupCommandRequest;
import ffdd.opsconsole.finance.dto.WithdrawalParamUpdateRequest;
import ffdd.opsconsole.finance.dto.WithdrawalQueryRequest;
import ffdd.opsconsole.finance.dto.WithdrawalReviewRequest;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/finance")
@RequiredArgsConstructor
public class OpsFinanceController {
    private final OpsFinanceService financeService;

    @GetMapping("/topup/overview")
    @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<Map<String, Object>> topupOverview() {
        return financeService.topupOverview();
    }

    @GetMapping("/topup/flows")
    @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<PageResult<DepositFlowView>> topupFlows(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        return financeService.topupFlows(status, userId, keyword, pageNum, pageSize);
    }

    @PatchMapping("/topup/channels/{channelCode}/enabled")
    @PreAuthorize("hasAuthority('finance_d1_write')")
    public ApiResult<Map<String, Object>> updateTopupChannelEnabled(
            @PathVariable String channelCode,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TopupCommandRequest request) {
        return financeService.updateTopupChannelEnabled(channelCode, idempotencyKey, request);
    }

    @PatchMapping("/topup/channels/{channelCode}/fee")
    @PreAuthorize("hasAuthority('finance_d1_write')")
    public ApiResult<Map<String, Object>> updateTopupChannelFee(
            @PathVariable String channelCode,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TopupCommandRequest request) {
        return financeService.updateTopupChannelFee(channelCode, idempotencyKey, request);
    }

    @PatchMapping("/topup/channels/{channelCode}/min-amount")
    @PreAuthorize("hasAuthority('finance_d1_write')")
    public ApiResult<Map<String, Object>> updateTopupChannelMinAmount(
            @PathVariable String channelCode,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TopupCommandRequest request) {
        return financeService.updateTopupChannelMinAmount(channelCode, idempotencyKey, request);
    }

    @PatchMapping("/topup/psp/primary")
    @PreAuthorize("hasAuthority('finance_d1_write')")
    public ApiResult<Map<String, Object>> switchTopupPsp(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TopupCommandRequest request) {
        return financeService.switchTopupPsp(idempotencyKey, request);
    }

    @PatchMapping("/topup/card-risk/{key}")
    @PreAuthorize("hasAuthority('finance_d1_write')")
    public ApiResult<Map<String, Object>> updateTopupCardRiskParam(
            @PathVariable String key,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TopupCommandRequest request) {
        return financeService.updateTopupCardRiskParam(key, idempotencyKey, request);
    }

    @PostMapping("/topup/reconciliation/{channelCode}/writeoff")
    @PreAuthorize("hasAuthority('finance_d1_write')")
    public ApiResult<Map<String, Object>> writeoffTopupReconciliation(
            @PathVariable String channelCode,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TopupCommandRequest request) {
        return financeService.writeoffTopupReconciliation(channelCode, idempotencyKey, request);
    }

    @PostMapping("/topup/bin-locks")
    @PreAuthorize("hasAuthority('finance_d1_bin_manual_lock')")
    public ApiResult<Map<String, Object>> createTopupBinLock(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TopupCommandRequest request) {
        return financeService.createTopupBinLock(idempotencyKey, request);
    }

    @PatchMapping("/topup/bin-locks/{segment}")
    @PreAuthorize("hasAnyAuthority('finance_d1_bin_lock','finance_d1_bin_unlock')")
    public ApiResult<Map<String, Object>> setTopupBinLock(
            @PathVariable String segment,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TopupCommandRequest request) {
        return financeService.setTopupBinLock(segment, idempotencyKey, request);
    }

    @PostMapping("/topup/chargebacks/{caseNo}/refund")
    @PreAuthorize("hasAuthority('finance_d1_chargeback_refund')")
    public ApiResult<Map<String, Object>> refundTopupChargeback(
            @PathVariable String caseNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TopupCommandRequest request) {
        return financeService.refundTopupChargeback(caseNo, idempotencyKey, request);
    }

    @GetMapping("/withdrawal-params")
    @PreAuthorize("hasAuthority('finance_d5_read')")
    public ApiResult<Map<String, Object>> withdrawalParams() {
        return financeService.withdrawalParams();
    }

    @PatchMapping("/withdrawal-params")
    @PreAuthorize("hasAnyAuthority('finance_d5_daily_limit_write','finance_d5_balance_max_write','finance_d5_fee_write')")
    public ApiResult<Map<String, Object>> updateWithdrawalParam(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) WithdrawalParamUpdateRequest request) {
        return financeService.updateWithdrawalParam(idempotencyKey, request);
    }

    @GetMapping("/withdrawals")
    @PreAuthorize("hasAuthority('finance_d2_read')")
    public ApiResult<PageResult<WithdrawalOrderView>> withdrawals(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) Integer minRiskScore) {
        return financeService.withdrawals(new WithdrawalQueryRequest(status, userId, keyword, pageNum, pageSize, minAmount, maxAmount, minRiskScore));
    }

    @GetMapping("/withdrawals/{withdrawalNo}")
    @PreAuthorize("hasAuthority('finance_d2_read')")
    public ApiResult<WithdrawalOrderView> withdrawalDetail(@PathVariable String withdrawalNo) {
        return financeService.withdrawalDetail(withdrawalNo);
    }

    @PostMapping("/withdrawals/{withdrawalNo}/review")
    @PreAuthorize("hasAnyAuthority('finance_d2_withdrawal_approve','finance_d2_withdrawal_freeze','finance_d2_withdrawal_unfreeze','finance_d2_withdrawal_reject')")
    public ApiResult<WithdrawalOrderView> reviewWithdrawal(
            @PathVariable String withdrawalNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) WithdrawalReviewRequest request) {
        return financeService.reviewWithdrawal(withdrawalNo, idempotencyKey, request);
    }
}

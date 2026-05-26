package ffdd.wallet.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.wallet.domain.DepositOrder;
import ffdd.wallet.domain.ExchangeOrder;
import ffdd.wallet.domain.UserWallet;
import ffdd.wallet.domain.WalletLedger;
import ffdd.wallet.domain.WithdrawalOrder;
import ffdd.wallet.dto.ApplyRiskDecisionRequest;
import ffdd.wallet.dto.ConfirmDepositRequest;
import ffdd.wallet.dto.CreateExchangeRequest;
import ffdd.wallet.dto.CreateWithdrawalRequest;
import ffdd.wallet.dto.FailWithdrawalRequest;
import ffdd.wallet.dto.LedgerQueryRequest;
import ffdd.wallet.dto.PostEarningRequest;
import ffdd.wallet.dto.PostEarningsResponse;
import ffdd.wallet.dto.PostPendingEarningsRequest;
import ffdd.wallet.dto.PostWalletCreditRequest;
import ffdd.wallet.dto.PostWalletDebitRequest;
import ffdd.wallet.dto.RiskDecisionApplyResult;
import ffdd.wallet.dto.SubmitWithdrawalChainRequest;
import ffdd.wallet.dto.SucceedWithdrawalRequest;
import ffdd.wallet.service.DepositPostingService;
import ffdd.wallet.service.WalletService;
import ffdd.common.audit.AuditLogService;
import ffdd.common.audit.AuditLogWriteRequest;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wallet")
public class UserWalletController {
    private final WalletService walletService;
    private final DepositPostingService depositPostingService;
    private final AuditLogService auditLogService;

    public UserWalletController(
            WalletService walletService,
            DepositPostingService depositPostingService,
            AuditLogService auditLogService) {
        this.walletService = walletService;
        this.depositPostingService = depositPostingService;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/users/{userId}")
    public ApiResult<UserWallet> wallet(@PathVariable Long userId) {
        return ApiResult.ok(walletService.getOrCreateWallet(userId));
    }

    @GetMapping("/ledgers")
    public ApiResult<PageResult<WalletLedger>> ledgers(LedgerQueryRequest request) {
        return ApiResult.ok(walletService.pageLedgers(request));
    }

    @PostMapping("/earnings/post")
    public ApiResult<WalletLedger> postEarning(@Valid @RequestBody PostEarningRequest request) {
        return ApiResult.ok(walletService.postEarning(request));
    }

    @PostMapping("/earnings/post-pending")
    public ApiResult<PostEarningsResponse> postPendingEarnings(@Valid @RequestBody PostPendingEarningsRequest request) {
        return ApiResult.ok(walletService.postPendingEarnings(request));
    }

    @PostMapping("/credits/post")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<WalletLedger> postCredit(@Valid @RequestBody PostWalletCreditRequest request) {
        return ApiResult.ok(walletService.postCredit(request));
    }

    @PostMapping("/debits/post")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<WalletLedger> postDebit(@Valid @RequestBody PostWalletDebitRequest request) {
        return ApiResult.ok(walletService.postDebit(request));
    }

    @PostMapping("/deposits/confirmed")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<DepositOrder> confirmDeposit(@Valid @RequestBody ConfirmDepositRequest request) {
        DepositOrder order = depositPostingService.confirm(request);
        audit("DEPOSIT_CONFIRM", "DEPOSIT", order.getDepositNo(), order.getUserId(), detail(
                "asset", order.getAsset(),
                "amount", order.getAmount(),
                "chain", order.getChain(),
                "confirmations", order.getConfirmations(),
                "status", order.getStatus()));
        return ApiResult.ok(order);
    }

    @PostMapping("/withdrawals")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<WithdrawalOrder> createWithdrawal(@Valid @RequestBody CreateWithdrawalRequest request) {
        WithdrawalOrder order = walletService.createWithdrawal(request);
        auditWithdrawal("WITHDRAWAL_CREATE", order);
        return ApiResult.ok(order);
    }

    @PostMapping("/exchanges")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<ExchangeOrder> createExchange(@Valid @RequestBody CreateExchangeRequest request) {
        ExchangeOrder order = walletService.createExchange(request);
        audit("EXCHANGE_CREATE", "EXCHANGE", order.getExchangeNo(), order.getUserId(), detail(
                "fromAsset", order.getFromAsset(),
                "toAsset", order.getToAsset(),
                "fromAmount", order.getFromAmount(),
                "toAmount", order.getToAmount(),
                "status", order.getStatus()));
        return ApiResult.ok(order);
    }

    @PostMapping("/risk-decisions/apply")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE') and hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<RiskDecisionApplyResult> applyRiskDecision(
            @Valid @RequestBody ApplyRiskDecisionRequest request) {
        RiskDecisionApplyResult result = walletService.applyRiskDecision(request);
        audit("WALLET_RISK_DECISION_APPLY", result.getBizType(), result.getBizNo(), null, detail(
                "decisionNo", request.getDecisionNo(),
                "decision", request.getDecision(),
                "status", result.getStatus()));
        return ApiResult.ok(result);
    }

    @PostMapping("/withdrawals/{withdrawalNo}/chain-submitted")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<WithdrawalOrder> submitWithdrawalChain(
            @PathVariable String withdrawalNo,
            @Valid @RequestBody SubmitWithdrawalChainRequest request) {
        WithdrawalOrder order = walletService.submitWithdrawalChain(withdrawalNo, request);
        auditWithdrawal("WITHDRAWAL_CHAIN_SUBMITTED", order);
        return ApiResult.ok(order);
    }

    @PostMapping("/withdrawals/{withdrawalNo}/succeeded")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<WithdrawalOrder> succeedWithdrawal(
            @PathVariable String withdrawalNo,
            @Valid @RequestBody SucceedWithdrawalRequest request) {
        WithdrawalOrder order = walletService.succeedWithdrawal(withdrawalNo, request);
        auditWithdrawal("WITHDRAWAL_SUCCEEDED", order);
        return ApiResult.ok(order);
    }

    @PostMapping("/withdrawals/{withdrawalNo}/failed")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<WithdrawalOrder> failWithdrawal(
            @PathVariable String withdrawalNo,
            @Valid @RequestBody FailWithdrawalRequest request) {
        WithdrawalOrder order = walletService.failWithdrawal(withdrawalNo, request);
        auditWithdrawal("WITHDRAWAL_FAILED", order);
        return ApiResult.ok(order);
    }

    private void auditWithdrawal(String action, WithdrawalOrder order) {
        audit(action, "WITHDRAWAL", order.getWithdrawalNo(), order.getUserId(), detail(
                "asset", order.getAsset(),
                "amount", order.getAmount(),
                "fee", order.getFee(),
                "riskDecisionId", order.getRiskDecisionId(),
                "status", order.getStatus(),
                "chainSubmittedAt", order.getChainSubmittedAt(),
                "completedAt", order.getCompletedAt(),
                "failedAt", order.getFailedAt()));
    }

    private void audit(String action, String resourceType, String bizNo, Long userId, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(bizNo)
                .bizNo(bizNo)
                .userId(userId)
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }

    private Map<String, Object> detail(Object... pairs) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value != null) {
                detail.put(String.valueOf(pairs[i]), value);
            }
        }
        return detail;
    }
}

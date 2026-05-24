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
import jakarta.validation.Valid;
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

    public UserWalletController(WalletService walletService, DepositPostingService depositPostingService) {
        this.walletService = walletService;
        this.depositPostingService = depositPostingService;
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
        return ApiResult.ok(depositPostingService.confirm(request));
    }

    @PostMapping("/withdrawals")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<WithdrawalOrder> createWithdrawal(@Valid @RequestBody CreateWithdrawalRequest request) {
        return ApiResult.ok(walletService.createWithdrawal(request));
    }

    @PostMapping("/exchanges")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<ExchangeOrder> createExchange(@Valid @RequestBody CreateExchangeRequest request) {
        return ApiResult.ok(walletService.createExchange(request));
    }

    @PostMapping("/risk-decisions/apply")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE') and hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<RiskDecisionApplyResult> applyRiskDecision(
            @Valid @RequestBody ApplyRiskDecisionRequest request) {
        return ApiResult.ok(walletService.applyRiskDecision(request));
    }

    @PostMapping("/withdrawals/{withdrawalNo}/chain-submitted")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<WithdrawalOrder> submitWithdrawalChain(
            @PathVariable String withdrawalNo,
            @Valid @RequestBody SubmitWithdrawalChainRequest request) {
        return ApiResult.ok(walletService.submitWithdrawalChain(withdrawalNo, request));
    }

    @PostMapping("/withdrawals/{withdrawalNo}/succeeded")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<WithdrawalOrder> succeedWithdrawal(
            @PathVariable String withdrawalNo,
            @Valid @RequestBody SucceedWithdrawalRequest request) {
        return ApiResult.ok(walletService.succeedWithdrawal(withdrawalNo, request));
    }

    @PostMapping("/withdrawals/{withdrawalNo}/failed")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<WithdrawalOrder> failWithdrawal(
            @PathVariable String withdrawalNo,
            @Valid @RequestBody FailWithdrawalRequest request) {
        return ApiResult.ok(walletService.failWithdrawal(withdrawalNo, request));
    }
}

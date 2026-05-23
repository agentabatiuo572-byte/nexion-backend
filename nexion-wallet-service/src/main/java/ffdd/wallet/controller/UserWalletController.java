package ffdd.wallet.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.wallet.domain.UserWallet;
import ffdd.wallet.domain.WalletLedger;
import ffdd.wallet.dto.LedgerQueryRequest;
import ffdd.wallet.dto.PostEarningRequest;
import ffdd.wallet.dto.PostEarningsResponse;
import ffdd.wallet.dto.PostPendingEarningsRequest;
import ffdd.wallet.dto.PostWalletCreditRequest;
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

    public UserWalletController(WalletService walletService) {
        this.walletService = walletService;
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
}

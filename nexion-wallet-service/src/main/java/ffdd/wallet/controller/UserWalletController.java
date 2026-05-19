package ffdd.wallet.controller;

import ffdd.common.api.ApiResult;
import ffdd.wallet.domain.UserWallet;
import ffdd.wallet.service.UserWalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wallet")
@RequiredArgsConstructor
public class UserWalletController {
    private final UserWalletService userWalletService;

    @GetMapping("/summary")
    public ApiResult<UserWallet> summary() {
        return ApiResult.ok(userWalletService.summary());
    }
}


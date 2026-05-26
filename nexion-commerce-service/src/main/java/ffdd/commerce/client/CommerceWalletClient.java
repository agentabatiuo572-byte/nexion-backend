package ffdd.commerce.client;

import ffdd.commerce.client.config.WalletFeignConfig;
import ffdd.commerce.client.dto.PostWalletDebitRequest;
import ffdd.commerce.client.dto.WalletLedgerResponse;
import ffdd.common.api.ApiResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "commerce-wallet-client",
        url = "${nexion.services.wallet-url:http://localhost:8105}",
        configuration = WalletFeignConfig.class)
public interface CommerceWalletClient {
    @PostMapping("/wallet/debits/post")
    ApiResult<WalletLedgerResponse> postDebit(@RequestBody PostWalletDebitRequest request);
}

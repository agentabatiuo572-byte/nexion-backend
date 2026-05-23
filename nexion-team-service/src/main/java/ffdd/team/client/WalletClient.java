package ffdd.team.client;

import ffdd.common.api.ApiResult;
import ffdd.team.client.config.InternalFeignConfig;
import ffdd.team.dto.WalletCreditRequest;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "wallet-client",
        url = "${nexion.services.wallet-url:http://localhost:8105}",
        configuration = InternalFeignConfig.class)
public interface WalletClient {
    @PostMapping("/wallet/credits/post")
    ApiResult<Map<String, Object>> postCredit(@RequestBody WalletCreditRequest request);
}

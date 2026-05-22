package ffdd.earnings.client;

import ffdd.common.api.ApiResult;
import ffdd.earnings.client.config.InternalFeignConfig;
import ffdd.earnings.client.dto.WalletPostEarningRequest;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "nexion-wallet-service",
        url = "${nexion.services.wallet-url:http://localhost:8105}",
        configuration = InternalFeignConfig.class)
public interface WalletClient {
    @PostMapping("/wallet/earnings/post")
    ApiResult<Map<String, Object>> postEarning(@RequestBody WalletPostEarningRequest request);
}

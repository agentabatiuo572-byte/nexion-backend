package ffdd.bff.client;

import ffdd.bff.client.config.InternalFeignConfig;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "nexion-wallet-service", url = "${nexion.services.wallet-url:http://localhost:8105}", configuration = InternalFeignConfig.class)
public interface WalletClient {
    @GetMapping("/wallet/users/{userId}")
    ApiResult<Map<String, Object>> wallet(@PathVariable Long userId);

    @GetMapping("/wallet/ledgers")
    ApiResult<PageResult<Map<String, Object>>> ledgers(
            @RequestParam Long userId,
            @RequestParam Long pageNum,
            @RequestParam Long pageSize);
}

package ffdd.bff.client;

import ffdd.bff.client.config.InternalFeignConfig;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "nexion-commerce-service", url = "${nexion.services.commerce-url:http://localhost:8104}", configuration = InternalFeignConfig.class)
public interface CommerceClient {
    @GetMapping("/commerce/orders")
    ApiResult<PageResult<Map<String, Object>>> orders(
            @RequestParam Long userId,
            @RequestParam Long pageNum,
            @RequestParam Long pageSize);

    @GetMapping("/commerce/app/price-index")
    ApiResult<List<Map<String, Object>>> priceIndex();
}

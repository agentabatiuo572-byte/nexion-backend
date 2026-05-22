package ffdd.compute.client;

import ffdd.common.api.ApiResult;
import ffdd.compute.client.config.InternalFeignConfig;
import ffdd.compute.client.dto.EarningsReceiptSettleRequest;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "nexion-earnings-service",
        url = "${nexion.services.earnings-url:http://localhost:8108}",
        configuration = InternalFeignConfig.class)
public interface EarningsClient {
    @PostMapping("/earnings/events/settle-receipt")
    ApiResult<Map<String, Object>> settleReceipt(@RequestBody EarningsReceiptSettleRequest request);
}

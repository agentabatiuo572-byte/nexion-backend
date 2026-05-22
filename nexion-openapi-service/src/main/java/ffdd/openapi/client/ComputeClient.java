package ffdd.openapi.client;

import ffdd.common.api.ApiResult;
import ffdd.openapi.client.config.InternalFeignConfig;
import ffdd.openapi.client.dto.ComputeReceiptCreateRequest;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "nexion-compute-service", url = "${nexion.clients.compute:http://localhost:8102}", configuration = InternalFeignConfig.class)
public interface ComputeClient {
    @PostMapping("/compute/receipts")
    ApiResult<Map<String, Object>> createReceipt(@RequestBody ComputeReceiptCreateRequest request);
}

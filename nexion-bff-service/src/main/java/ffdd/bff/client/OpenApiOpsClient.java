package ffdd.bff.client;

import ffdd.bff.client.config.OpsFeignConfig;
import ffdd.common.api.ApiResult;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "nexion-openapi-service-ops", url = "${nexion.services.openapi-url:http://localhost:8111}", configuration = OpsFeignConfig.class)
public interface OpenApiOpsClient {
    @GetMapping("/openapi/ops/stats")
    ApiResult<Map<String, Object>> opsStats(@RequestParam int days);
}

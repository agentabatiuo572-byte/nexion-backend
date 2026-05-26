package ffdd.bff.client;

import ffdd.bff.client.config.OpsFeignConfig;
import ffdd.common.api.ApiResult;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "nexion-commerce-service-ops", url = "${nexion.services.commerce-url:http://localhost:8104}", configuration = OpsFeignConfig.class)
public interface CommerceOpsClient {
    @GetMapping("/commerce/ops/stats")
    ApiResult<Map<String, Object>> opsStats(@RequestParam int days);
}

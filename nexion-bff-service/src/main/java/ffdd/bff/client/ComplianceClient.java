package ffdd.bff.client;

import ffdd.bff.client.config.OpsFeignConfig;
import ffdd.common.api.ApiResult;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "nexion-compliance-service-ops", url = "${nexion.services.compliance-url:http://localhost:8109}", configuration = OpsFeignConfig.class)
public interface ComplianceClient {
    @GetMapping("/compliance/ops/stats")
    ApiResult<Map<String, Object>> opsStats(@RequestParam int days);
}

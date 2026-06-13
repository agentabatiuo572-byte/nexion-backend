package ffdd.wallet.client;

import ffdd.common.api.ApiResult;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

@FeignClient(
        name = "nexion-system-service",
        url = "${nexion.services.system-url:http://localhost:8110}")
public interface SystemConfigClient {
    @GetMapping("/config/wallet")
    ApiResult<Map<String, Object>> wallet();
}

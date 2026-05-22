package ffdd.bff.client;

import ffdd.bff.client.config.InternalFeignConfig;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "nexion-compute-service", url = "${nexion.services.compute-url:http://localhost:8102}", configuration = InternalFeignConfig.class)
public interface ComputeClient {
    @GetMapping("/compute/devices")
    ApiResult<PageResult<Map<String, Object>>> devices(
            @RequestParam Long userId,
            @RequestParam Long pageNum,
            @RequestParam Long pageSize);
}

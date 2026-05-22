package ffdd.bff.client;

import ffdd.bff.client.config.InternalFeignConfig;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "nexion-earnings-service", url = "${nexion.services.earnings-url:http://localhost:8108}", configuration = InternalFeignConfig.class)
public interface EarningsClient {
    @GetMapping("/earnings/summaries")
    ApiResult<PageResult<Map<String, Object>>> summaries(
            @RequestParam Long userId,
            @RequestParam Long pageNum,
            @RequestParam Long pageSize);

    @GetMapping("/earnings/events")
    ApiResult<PageResult<Map<String, Object>>> events(
            @RequestParam Long userId,
            @RequestParam Long pageNum,
            @RequestParam Long pageSize);
}

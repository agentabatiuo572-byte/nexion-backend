package ffdd.bff.client;

import ffdd.bff.client.config.InternalFeignConfig;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "nexion-team-service", url = "${nexion.services.team-url:http://localhost:8106}", configuration = InternalFeignConfig.class)
public interface TeamClient {
    @GetMapping("/team/overview")
    ApiResult<Map<String, Object>> overview(@RequestParam Long userId);

    @GetMapping("/team/commissions")
    ApiResult<PageResult<Map<String, Object>>> commissions(
            @RequestParam Long userId,
            @RequestParam Long pageNum,
            @RequestParam Long pageSize);
}

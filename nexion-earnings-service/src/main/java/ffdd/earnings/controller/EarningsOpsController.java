package ffdd.earnings.controller;

import ffdd.common.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/earnings")
public class EarningsOpsController {
    @GetMapping("/ops/overview")
    public ApiResult<Map<String, Object>> overview() {
        return ApiResult.ok(Map.of(
                "service", "nexion-earnings-service",
                "database", "nexion_earnings",
                "responsibilities", List.of("earning ticks", "earning summaries", "event stream", "wallet posting outbox")));
    }
}

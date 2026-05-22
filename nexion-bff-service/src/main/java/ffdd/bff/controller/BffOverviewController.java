package ffdd.bff.controller;

import ffdd.common.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bff")
public class BffOverviewController {
    @GetMapping("/ops/overview")
    public ApiResult<Map<String, Object>> overview() {
        return ApiResult.ok(Map.of(
                "service", "nexion-bff-service",
                "domain", "page aggregation and high-concurrency read cache",
                "routes", List.of("/bff/ops/overview"),
                "upstreamDomains", List.of("auth", "wallet", "compute", "earnings", "team", "mission", "system")));
    }
}

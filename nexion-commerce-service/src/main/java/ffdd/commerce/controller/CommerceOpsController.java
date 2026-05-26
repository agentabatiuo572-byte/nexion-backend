package ffdd.commerce.controller;

import ffdd.common.api.ApiResult;
import ffdd.commerce.service.CommerceOpsStatsService;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/commerce")
public class CommerceOpsController {
    private final CommerceOpsStatsService statsService;

    public CommerceOpsController(CommerceOpsStatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/ops/overview")
    public ApiResult<Map<String, Object>> overview() {
        return ApiResult.ok(Map.of(
                "service", "nexion-commerce-service",
                "database", "nexion_commerce",
                "responsibilities", List.of("SKU catalog", "orders", "payment callbacks", "trade-in workflow")));
    }

    @GetMapping("/ops/stats")
    @PreAuthorize("hasAuthority('PERM_COMMERCE_READ')")
    public ApiResult<Map<String, Object>> stats(@RequestParam(defaultValue = "7") int days) {
        return ApiResult.ok(statsService.summary(days));
    }
}

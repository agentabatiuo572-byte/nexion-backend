package ffdd.commerce.controller;

import ffdd.common.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/commerce")
public class CommerceOpsController {
    @GetMapping("/ops/overview")
    public ApiResult<Map<String, Object>> overview() {
        return ApiResult.ok(Map.of(
                "service", "nexion-commerce-service",
                "database", "nexion_commerce",
                "responsibilities", List.of("SKU catalog", "orders", "payment callbacks", "trade-in workflow")));
    }
}

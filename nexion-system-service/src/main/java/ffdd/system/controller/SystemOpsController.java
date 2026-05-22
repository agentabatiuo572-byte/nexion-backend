package ffdd.system.controller;

import ffdd.common.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/system")
public class SystemOpsController {
    @GetMapping("/ops/overview")
    public ApiResult<Map<String, Object>> overview() {
        return ApiResult.ok(Map.of(
                "service", "nexion-system-service",
                "database", "nexion_system",
                "responsibilities", List.of("operation config", "i18n", "content", "help center", "feature flags")));
    }
}

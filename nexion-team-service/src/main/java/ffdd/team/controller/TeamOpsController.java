package ffdd.team.controller;

import ffdd.common.api.ApiResult;
import ffdd.team.service.TeamOpsMetricsService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/team")
@RequiredArgsConstructor
public class TeamOpsController {
    private final TeamOpsMetricsService metricsService;

    @GetMapping("/ops/overview")
    public ApiResult<Map<String, Object>> overview() {
        return ApiResult.ok(metricsService.overview());
    }
}

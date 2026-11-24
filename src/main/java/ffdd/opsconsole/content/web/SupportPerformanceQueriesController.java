package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
import ffdd.opsconsole.content.application.SupportLeaderboardService;
import ffdd.opsconsole.content.application.SupportPerformanceReadService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/support-workbench")
@RequiredArgsConstructor
public class SupportPerformanceQueriesController {
    private final ProductionSupportPathGuard guard;
    private final SupportPerformanceReadService performance;
    private final SupportLeaderboardService leaderboard;

    @GetMapping(value = "/analytics", params = "metricVersion")
    @PreAuthorize("hasAnyAuthority('service_m1_read','service_m3_read')")
    public ApiResult<Map<String, Object>> analytics(@RequestParam MultiValueMap<String, String> parameters) {
        guard.requireOpsWriteAllowed();
        return ApiResult.ok(performance.analytics(parameters));
    }

    @GetMapping(value = {"/leaderboard", "/leaderboard/{agentId}"}, params = "metricVersion")
    @PreAuthorize("hasAnyAuthority('service_m1_read','service_m3_read')")
    public ApiResult<Map<String, Object>> leaderboard(@RequestParam MultiValueMap<String, String> parameters,
            @PathVariable(required = false) Long agentId) {
        guard.requireOpsWriteAllowed();
        return ApiResult.ok(leaderboard.performance(parameters, agentId));
    }
}

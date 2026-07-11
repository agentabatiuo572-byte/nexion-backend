package ffdd.opsconsole.platform.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.platform.application.OpsDashboardService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/ops-dashboard")
@RequiredArgsConstructor
public class OpsDashboardController {
    private final OpsDashboardService dashboardService;

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('overview_b1_read')")
    public ApiResult<Map<String, Object>> summary() {
        return dashboardService.summary();
    }
}

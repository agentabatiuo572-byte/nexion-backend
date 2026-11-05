package ffdd.opsconsole.platform.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.platform.application.OpsDashboardService;
import ffdd.opsconsole.platform.application.OpsDashboardExportService;
import ffdd.opsconsole.platform.dto.OpsDashboardExportRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/ops-dashboard")
@RequiredArgsConstructor
public class OpsDashboardController {
    private final OpsDashboardService dashboardService;
    private final OpsDashboardExportService exportService;

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('overview_b1_read')")
    public ApiResult<Map<String, Object>> summary() {
        return dashboardService.summary();
    }

    @PostMapping("/exports")
    @PreAuthorize("hasAuthority('overview_b0_export') && @superAdminAuthorization.isSuperAdmin(authentication)")
    public ApiResult<OpsDashboardExportService.ExportReceipt> recordExport(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) OpsDashboardExportRequest request) {
        return ApiResult.ok(exportService.recordExport(idempotencyKey, request));
    }
}

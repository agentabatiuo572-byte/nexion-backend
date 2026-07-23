package ffdd.opsconsole.bi.web;

import ffdd.opsconsole.bi.application.L3FinanceReportService;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/bi/finance")
@RequiredArgsConstructor
public class L3FinanceReportController {
    private final L3FinanceReportService service;

    @GetMapping("/revenue")
    @PreAuthorize("hasAuthority('bi_l3_read')")
    public ApiResult<Map<String, Object>> revenue(
            @RequestParam(defaultValue = "month") String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "stream") String breakdown) {
        return service.revenue(period, from, to);
    }

    @GetMapping("/redemption")
    @PreAuthorize("hasAuthority('bi_l3_read')")
    public ApiResult<Map<String, Object>> redemption(
            @RequestParam(defaultValue = "month") String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String cohort) {
        return service.redemption(period, from, to, cohort);
    }
}

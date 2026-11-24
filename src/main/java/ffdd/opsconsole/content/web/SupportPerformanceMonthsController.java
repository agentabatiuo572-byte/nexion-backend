package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
import ffdd.opsconsole.content.application.SupportPerformanceCache;
import ffdd.opsconsole.content.domain.SupportPerformance;
import ffdd.opsconsole.content.domain.SupportPerformance.Generation;
import ffdd.opsconsole.content.domain.SupportPerformance.Reason;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/support-workbench")
public class SupportPerformanceMonthsController {
    private final ProductionSupportPathGuard productionPathGuard;
    private final SupportPerformanceCache cache;

    public SupportPerformanceMonthsController(ProductionSupportPathGuard productionPathGuard, SupportPerformanceCache cache) {
        this.productionPathGuard = productionPathGuard;
        this.cache = cache;
    }

    @GetMapping(value = "/analytics/months", params = "metricVersion")
    @PreAuthorize("hasAnyAuthority('service_m1_read','service_m3_read')")
    public ApiResult<MonthDirectory> months(@RequestParam MultiValueMap<String, String> parameters) {
        productionPathGuard.requireOpsWriteAllowed();
        var versions = parameters.get("metricVersion");
        if (parameters.size() != 1 || versions == null || versions.size() != 1) {
            throw new BizException(422, "SUPPORT_ANALYTICS_QUERY_INVALID");
        }
        if (!SupportPerformance.DEFINITION_VERSION.equals(versions.get(0))) {
            throw new BizException(422, "SUPPORT_METRIC_VERSION_UNSUPPORTED");
        }

        var published = cache.current();
        if (published.isEmpty() || published.get().generation() == Generation.UPGRADING) {
            return ApiResult.ok(new MonthDirectory(SupportPerformance.DEFINITION_VERSION, Generation.UPGRADING, List.of()));
        }
        var months = published.get().months();
        var entries = new ArrayList<MonthEntry>(months.size());
        for (int index = months.size() - 1; index >= 0; index--) {
            var month = months.get(index);
            String availability = switch (month.coverage()) {
                case COMPLETE -> "READY";
                case PARTIAL -> "PARTIAL";
                case UNKNOWN, FAILED -> "UNKNOWN";
            };
            entries.add(new MonthEntry(month.month(), month.from().toString(), month.to().toString(),
                    month.businessZone(), availability, month.reasons()));
        }
        return ApiResult.ok(new MonthDirectory(SupportPerformance.DEFINITION_VERSION, Generation.READY, List.copyOf(entries)));
    }

    public record MonthDirectory(String metricVersion, Generation generation, List<MonthEntry> months) { }

    public record MonthEntry(String month, String from, String to, String businessZone,
                             String availability, List<Reason> reasonCodes) { }
}

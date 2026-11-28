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
import java.util.Map;
import java.util.stream.Collectors;
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
            var channels = month.channels().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                    entry -> new ChannelStatus(entry.getValue().status().name(), entry.getValue().reasons())));
            entries.add(new MonthEntry(month.month(), month.from().toString(), month.calendarEnd().toString(),
                    month.businessZone(), availability, month.reasons(), channels));
        }
        return ApiResult.ok(new MonthDirectory(SupportPerformance.DEFINITION_VERSION, Generation.READY, List.copyOf(entries)));
    }

    public record MonthDirectory(String metricVersion, Generation generation, List<MonthEntry> months) { }

    public record MonthEntry(String month, String from, String to, String businessZone,
                             String availability, List<Reason> reasonCodes, Map<String, ChannelStatus> channels) { }

    public record ChannelStatus(String status, List<Reason> reasonCodes) { }

    @GetMapping("/analytics/channel-coverage")
    @PreAuthorize("@superAdminAuthorization.isSuperAdmin(authentication) and hasAuthority('service_m1_read')")
    public ApiResult<List<CoverageEvidence>> channelCoverage() {
        productionPathGuard.requireOpsWriteAllowed();
        return ApiResult.ok(cache.current().filter(snapshot -> snapshot.generation() == Generation.READY)
                .map(snapshot -> snapshot.months().stream().map(month -> new CoverageEvidence(month.month(),
                        snapshot.sourceVersion(), month.from().toString(), month.to().toString(), month.channels(),
                        SupportPerformance.digest(snapshot.firstPayments().stream()
                                .filter(payment -> month.contains(payment.at())).toList().toString()),
                        month.firstHistoryComplete())).toList()).orElseGet(List::of));
    }

    public record CoverageEvidence(String month, String sourceVersion, String from, String to,
            Map<String, SupportPerformance.ChannelCoverage> channels, String firstPaymentDigest, boolean firstHistoryComplete) { }
}

package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportPerformance;
import ffdd.opsconsole.shared.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Lightweight v2 publication slice. It is disabled by default so the old one-thread scheduler
 * never performs an extra full v1 snapshot read in this scaffold. When explicitly enabled after
 * integration, it reuses the completed analytics generation's {@code asOf} and subject set as the
 * shared source/cutoff, and only writes the separate v2 cache keys. Event-level performance facts
 * land through B80-BE-01 later.
 */
@Service
public class SupportPerformanceSampler {
    private static final Logger log = LoggerFactory.getLogger(SupportPerformanceSampler.class);
    @Value("${nexion.support.performance.enabled:false}")
    private boolean enabled = true;
    private final SupportAnalyticsFactSampler analytics;
    private final SupportPerformanceCache cache;
    private final ProductionSupportPathGuard production;

    public SupportPerformanceSampler(SupportAnalyticsFactSampler analytics, SupportPerformanceCache cache,
            ProductionSupportPathGuard production) {
        this.analytics = analytics;
        this.cache = cache;
        this.production = production;
    }

    @Scheduled(cron = "0 * * * * *", scheduler = "supportAnalyticsFactTaskScheduler")
    public void poll() {
        if (!enabled || !production.productionSupportAutomationAllowed()) return;
        try {
            var source = analytics.current();
            var snapshot = SupportPerformance.fromAnalytics(source.asOf(),
                    SupportPerformance.sourceVersion(source.asOf(), source.subjects()));
            cache.publish(snapshot);
        } catch (BizException failure) {
            log.warn("Support performance v2 publication unavailable reason={}", failure.getMessage());
        } catch (RuntimeException failure) {
            log.warn("Support performance v2 publication failed type={}", failure.getClass().getSimpleName());
        }
    }
}

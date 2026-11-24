package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportPerformance;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.ConfirmedChainWithdrawal;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.WithdrawalCapture;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Publishes B80 observations collected inside the existing analytics source transaction. */
@Service
public class SupportPerformanceSampler {
    private static final Logger log = LoggerFactory.getLogger(SupportPerformanceSampler.class);
    @Value("${nexion.support.performance.enabled:false}")
    private boolean enabled;
    private final SupportPerformanceCache cache;

    public SupportPerformanceSampler(SupportPerformanceCache cache) {
        this.cache = cache;
    }

    Capture prepare(Instant asOf, Collection<Long> subjects) {
        if (!enabled || asOf.isBefore(SupportPerformance.HISTORY_START)) return null;
        try {
            var directory = SupportPerformance.fromAnalytics(asOf, SupportPerformance.sourceVersion(asOf, subjects));
            return new Capture(directory, new ArrayList<>());
        } catch (RuntimeException failure) {
            log.warn("Support performance v2 observation unavailable type={}", failure.getClass().getSimpleName());
            return null;
        }
    }

    void publish(Capture capture) {
        try {
            var directory = capture.directory();
            cache.publish(new SupportPerformance.Snapshot(directory.definitionVersion(), directory.sourceVersion(),
                    directory.historyStart(), directory.asOf(), directory.generation(), directory.months(), capture.withdrawals()));
        } catch (BizException failure) {
            log.warn("Support performance v2 publication unavailable reason={}", failure.getMessage());
        } catch (RuntimeException failure) {
            log.warn("Support performance v2 publication failed type={}", failure.getClass().getSimpleName());
        }
    }

    record Capture(SupportPerformance.Snapshot directory, List<ConfirmedChainWithdrawal> withdrawals) {
        WithdrawalCapture withdrawalScope() {
            var months = directory.months();
            if (months.isEmpty()) return null;
            var month = months.get(months.size() - 1);
            return new WithdrawalCapture(month.from(), month.to(), withdrawals::add);
        }
    }
}

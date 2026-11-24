package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportPerformance;
import ffdd.opsconsole.content.domain.SupportPerformance.ObservedDeposit;
import ffdd.opsconsole.content.mapper.SupportPerformanceHistoryMapper;
import ffdd.opsconsole.finance.facade.SupportDirectPurchaseFactsFacade;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.ConfirmedDepositCorrection;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.ConfirmedWithdrawal;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.PerformanceCapture;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
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
    private final SupportDirectPurchaseFactsFacade directPurchases;
    private final SupportPerformanceHistoryMapper history;

    public SupportPerformanceSampler(SupportPerformanceCache cache, SupportDirectPurchaseFactsFacade directPurchases,
            SupportPerformanceHistoryMapper history) {
        this.cache = cache;
        this.directPurchases = directPurchases;
        this.history = history;
    }

    Capture prepare(Instant asOf, Collection<Long> subjects, SupportPaymentFacts.Snapshot payments) {
        if (!enabled || asOf.isBefore(SupportPerformance.HISTORY_START)) return null;
        try {
            var directory = SupportPerformance.fromAnalytics(asOf, SupportPerformance.sourceVersion(asOf, subjects));
            if (directory.months().isEmpty()) return new Capture(directory, List.of(),
                    new SupportDirectPurchaseFactsFacade.Snapshot(List.of(), List.of()));
            var storageZone = ZoneId.of(payments.businessZone());
            var deposits = payments.facts().stream()
                    .filter(fact -> fact.kind() == SupportPaymentFacts.Kind.DEPOSIT && SupportPerformance.CURRENCY.equals(fact.currency()))
                    .map(fact -> new ObservedDeposit(fact.factId(), fact.customerId(), fact.ledgerId(), fact.source(),
                            fact.sourceBusinessId(), fact.succeededAt().atZone(storageZone).toInstant(),
                            fact.amount(), fact.historicalEnvironmentStatus()))
                    .filter(fact -> SupportPerformance.contains(directory.historyStart(), asOf, fact.creditedAt()))
                    .toList();
            var purchases = directPurchases.readDirectPurchases(subjects, directory.historyStart(), asOf);
            return new Capture(directory, deposits, purchases);
        } catch (RuntimeException failure) {
            cache.markRefreshFailed();
            log.warn("Support performance v2 observation unavailable type={}", failure.getClass().getSimpleName());
            return null;
        }
    }

    SupportPerformance.Snapshot freeze(Capture capture, SupportAnalyticsFactSnapshot source) {
        if (capture.withdrawalReadFailed) {
            cache.markRefreshFailed();
            log.warn("Support performance v2 funds source read failed, previous generation retained");
            return null;
        }
        try {
            var directory = capture.directory;
            var attributions = SupportPerformanceDepositAttribution.resolve(source, capture.deposits, capture.depositCorrections);
            var events = SupportPerformanceAggregation.events(source.payments(), capture.deposits,
                    capture.depositCorrections, capture.withdrawals, capture.purchases.facts(), attributions, history);
            var months = directory.months().stream().map(month -> {
                var reasons = EnumSet.of(SupportPerformance.Reason.SOURCE_INCOMPLETE, SupportPerformance.Reason.EXTERNAL_REFUND_UNVERIFIED);
                var monthEvents = events.stream().filter(event -> month.contains(event.effectiveAt())).toList();
                if (monthEvents.stream().anyMatch(event -> event.attribution().state() == SupportPerformance.AttributionState.UNKNOWN)) {
                    reasons.add(SupportPerformance.Reason.ATTRIBUTION_UNKNOWN);
                }
                if (capture.purchases.issues().stream().anyMatch(issue -> "MISSING_PAYMENT_TIME".equals(issue.reason()))) {
                    reasons.add(SupportPerformance.Reason.PAYMENT_TIME_UNVERIFIED);
                }
                var coverage = monthEvents.isEmpty() ? SupportPerformance.Coverage.UNKNOWN : SupportPerformance.Coverage.PARTIAL;
                return new SupportPerformance.Month(month.month(), month.from(), month.to(), month.businessZone(),
                        coverage, List.copyOf(reasons));
            }).toList();
            return new SupportPerformance.Snapshot(directory.definitionVersion(), directory.sourceVersion(),
                    directory.historyStart(), directory.asOf(), SupportPerformance.Generation.READY, months, events,
                    capture.purchases.issues(), SupportPerformanceAggregation.byMonth(months, events));
        } catch (RuntimeException failure) {
            cache.markRefreshFailed();
            log.warn("Support performance v2 generation failed type={}", failure.getClass().getSimpleName());
            return null;
        }
    }

    void publish(SupportPerformance.Snapshot snapshot) {
        try {
            cache.publish(snapshot);
        } catch (BizException failure) {
            cache.markRefreshFailed();
            log.warn("Support performance v2 publication unavailable reason={}", failure.getMessage());
        } catch (RuntimeException failure) {
            cache.markRefreshFailed();
            log.warn("Support performance v2 publication failed type={}", failure.getClass().getSimpleName());
        }
    }

    static final class Capture {
        private final SupportPerformance.Snapshot directory;
        private final List<ConfirmedWithdrawal> withdrawals = new ArrayList<>();
        private final List<ObservedDeposit> deposits;
        private final SupportDirectPurchaseFactsFacade.Snapshot purchases;
        private final List<ConfirmedDepositCorrection> depositCorrections = new ArrayList<>();
        private boolean withdrawalReadFailed;

        Capture(SupportPerformance.Snapshot directory, List<ObservedDeposit> deposits,
                SupportDirectPurchaseFactsFacade.Snapshot purchases) {
            this.directory = directory;
            this.deposits = deposits;
            this.purchases = purchases;
        }

        PerformanceCapture cashScope() {
            var months = directory.months();
            if (months.isEmpty()) return null;
            return new PerformanceCapture(directory.historyStart(), directory.asOf(), withdrawals::add,
                    depositCorrections::add, () -> withdrawalReadFailed = true);
        }
    }
}

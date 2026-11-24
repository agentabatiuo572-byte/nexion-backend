package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportPerformance;
import ffdd.opsconsole.content.domain.SupportPerformance.ObservedDeposit;
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

    public SupportPerformanceSampler(SupportPerformanceCache cache, SupportDirectPurchaseFactsFacade directPurchases) {
        this.cache = cache;
        this.directPurchases = directPurchases;
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
            log.warn("Support performance v2 observation unavailable type={}", failure.getClass().getSimpleName());
            return null;
        }
    }

    void publish(Capture capture, SupportAnalyticsFactSnapshot source) {
        if (capture.withdrawalReadFailed) {
            log.warn("Support performance v2 funds source read failed, previous generation retained");
            return;
        }
        try {
            var directory = capture.directory;
            var attributions = SupportPerformanceDepositAttribution.resolve(source, capture.deposits, capture.depositCorrections);
            var subtotals = SupportPerformanceAggregation.summarize(directory.months(), source.payments(),
                    capture.deposits, capture.depositCorrections, capture.withdrawals, capture.purchases.facts(), attributions);
            cache.publish(new SupportPerformance.Snapshot(directory.definitionVersion(), directory.sourceVersion(),
                    directory.historyStart(), directory.asOf(), directory.generation(), directory.months(),
                    capture.withdrawals, capture.deposits, capture.depositCorrections, attributions.events(),
                    capture.purchases.facts(), capture.purchases.issues(), subtotals));
        } catch (BizException failure) {
            log.warn("Support performance v2 publication unavailable reason={}", failure.getMessage());
        } catch (RuntimeException failure) {
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

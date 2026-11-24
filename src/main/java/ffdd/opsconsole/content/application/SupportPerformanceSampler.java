package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportPerformance;
import ffdd.opsconsole.content.domain.SupportPerformance.ObservedDeposit;
import ffdd.opsconsole.content.mapper.SupportPerformanceHistoryMapper;
import ffdd.opsconsole.content.mapper.SupportLeaderboardMapper;
import ffdd.opsconsole.finance.facade.SupportDirectPurchaseFactsFacade;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.ConfirmedDepositCorrection;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.ConfirmedWithdrawal;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.PerformanceCapture;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.WithdrawalReference;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
    private final SupportPerformanceFirstPaymentService firstPayments;
    private final FinanceSupportPaymentFactsFacade paymentsEvidence;
    private final SupportLeaderboardMapper leaderboard;

    public SupportPerformanceSampler(SupportPerformanceCache cache, SupportDirectPurchaseFactsFacade directPurchases,
            SupportPerformanceHistoryMapper history, SupportPerformanceFirstPaymentService firstPayments,
            FinanceSupportPaymentFactsFacade paymentsEvidence, SupportLeaderboardMapper leaderboard) {
        this.cache = cache;
        this.directPurchases = directPurchases;
        this.history = history;
        this.firstPayments = firstPayments;
        this.paymentsEvidence = paymentsEvidence;
        this.leaderboard = leaderboard;
    }

    Capture prepare(Instant asOf, Collection<Long> subjects, SupportPaymentFacts.Snapshot payments) {
        if (!enabled || asOf.isBefore(SupportPerformance.HISTORY_START)) return null;
        try {
            var directory = SupportPerformance.fromAnalytics(asOf, SupportPerformance.sourceVersion(asOf, subjects));
            if (directory.months().isEmpty()) return new Capture(directory, List.of(),
                    new SupportDirectPurchaseFactsFacade.Snapshot(List.of(), List.of()), List.of());
            var storageZone = ZoneId.of(payments.businessZone());
            var depositFacts = payments.facts().stream()
                    .filter(fact -> fact.kind() == SupportPaymentFacts.Kind.DEPOSIT && SupportPerformance.CURRENCY.equals(fact.currency()))
                    .filter(fact -> SupportPerformance.contains(directory.historyStart(), asOf, fact.succeededAt().atZone(storageZone).toInstant()))
                    .toList();
            var bankAmounts = paymentsEvidence.bankTopupOriginalAmounts(depositFacts);
            var deposits = depositFacts.stream()
                    .map(fact -> new ObservedDeposit(fact.factId(), fact.customerId(), fact.ledgerId(), fact.source(),
                            fact.sourceBusinessId(), fact.succeededAt().atZone(storageZone).toInstant(),
                            fact.amount(), fact.historicalEnvironmentStatus(), bankAmounts.get(fact.ledgerId())))
                    .toList();
            var purchases = directPurchases.readDirectPurchases(subjects);
            return new Capture(directory, deposits, purchases,
                    paymentsEvidence.walletReferences(subjects, directory.historyStart(), asOf));
        } catch (RuntimeException failure) {
            cache.markRefreshFailed(asOf);
            log.warn("Support performance v2 observation unavailable type={}", failure.getClass().getSimpleName());
            return null;
        }
    }

    SupportPerformanceCache.DaySnapshot freeze(Capture capture, SupportAnalyticsFactSnapshot source) {
        if (capture.withdrawalReadFailed) {
            cache.markRefreshFailed(capture.directory.asOf());
            log.warn("Support performance v2 funds source read failed, previous generation retained");
            return null;
        }
        try {
            var directory = capture.directory;
            var historyReader = cache.historyReader(history, directory.asOf());
            var attributions = SupportPerformanceDepositAttribution.resolve(source, capture.deposits, capture.depositCorrections);
            var chainTopups = paymentsEvidence.chainTopupEvidence(source.payments().facts().stream()
                    .filter(fact -> attributions.verifiedOriginals().containsKey(fact.factId())).toList());
            var events = SupportPerformanceAggregation.events(source.payments(), capture.deposits,
                    capture.depositCorrections, capture.withdrawals, capture.withdrawalReferences, capture.purchases.facts(),
                    capture.purchases.refunds(), capture.references, directory.historyStart(), directory.asOf(), attributions, historyReader, chainTopups);
            var firstSource = firstPayments.select(source.payments(), capture.purchases, directory.asOf());
            var first = SupportPerformanceAggregation.firstPayments(firstSource, historyReader);
            var bindings = history.bindingHistory(LocalDateTime.ofInstant(directory.asOf(), ZoneOffset.UTC)).stream()
                    .map(row -> new SupportPerformance.Binding(row.customerId(), new SupportPerformance.EventAttribution(
                            row.groupId() == null ? SupportPerformance.AttributionState.AGENT_WITHOUT_GROUP
                                    : SupportPerformance.AttributionState.ASSIGNED, row.agentId(), row.groupId()),
                            row.startsAt().toInstant(ZoneOffset.UTC),
                            row.endsAt() == null ? null : row.endsAt().toInstant(ZoneOffset.UTC))).toList();
            var reviews = history.channelReviews(LocalDateTime.ofInstant(directory.asOf(), ZoneOffset.UTC));
            var eventsByMonth = events.stream().collect(java.util.stream.Collectors.groupingBy(event ->
                    ffdd.opsconsole.shared.config.BusinessCalendar.legacy().month(event.effectiveAt()).toString()));
            var months = new ArrayList<SupportPerformance.Month>();
            var subtotals = new ArrayList<SupportPerformance.MonthlySubtotal>();
            for (var month : directory.months()) {
                var monthEvents = eventsByMonth.getOrDefault(month.month(), List.of());
                months.add(coverage(month, monthEvents, first, reviews, capture));
                subtotals.addAll(cache.monthlySubtotals(month, monthEvents));
            }
            var snapshot = new SupportPerformance.Snapshot(directory.definitionVersion(), directory.sourceVersion(),
                    directory.historyStart(), directory.asOf(), SupportPerformance.Generation.READY, months, events,
                    capture.purchases.issues(), subtotals, bindings, first, firstSource.coveredCustomers(), null);
            return new SupportPerformanceCache.DaySnapshot(snapshot, SupportPerformanceCache.RankEvidence.capture(leaderboard));
        } catch (RuntimeException failure) {
            cache.markRefreshFailed(capture.directory.asOf());
            log.warn("Support performance v2 generation failed type={}", failure.getClass().getSimpleName());
            return null;
        }
    }

    private static SupportPerformance.Month coverage(SupportPerformance.Month month, List<SupportPerformance.Event> events,
            List<SupportPerformance.FirstPayment> first,
            List<SupportPerformanceHistoryMapper.ChannelReview> reviews, Capture capture) {
        var channels = new java.util.LinkedHashMap<String, SupportPerformance.ChannelCoverage>();
        var options = new ArrayList<>(SupportPerformance.INFLOW_CHANNELS);
        options.addAll(SupportPerformance.WITHDRAWAL_CHANNELS);
        var monthReasons = EnumSet.noneOf(SupportPerformance.Reason.class);
        for (String channel : options) {
            boolean withdrawal = SupportPerformance.WITHDRAWAL_CHANNELS.contains(channel);
            var verified = events.stream().filter(event -> channel.equals(event.channel())
                    && (withdrawal ? event.kind() == SupportPerformance.Kind.WITHDRAWAL
                                   : event.kind() == SupportPerformance.Kind.INFLOW || event.kind() == SupportPerformance.Kind.CORRECTION))
                    .sorted(java.util.Comparator.comparing(SupportPerformance.Event::id)).toList();
            var details = new StringBuilder(month.month()).append('|').append(channel)
                    .append('|').append(month.from()).append('|').append(month.to());
            for (var event : verified) details.append('\n').append(event.id()).append('|').append(event.customerId())
                    .append('|').append(event.effectiveAt()).append('|').append(event.deltaUsdt().stripTrailingZeros().toPlainString());
            String digest = SupportPerformance.digest(details.toString());
            var reasons = EnumSet.noneOf(SupportPerformance.Reason.class);
            if (verified.stream().anyMatch(event -> event.attribution().state() == SupportPerformance.AttributionState.UNKNOWN)) {
                reasons.add(SupportPerformance.Reason.ATTRIBUTION_UNKNOWN);
            }
            if (channel.endsWith("DIRECT_PURCHASE")) {
                for (var issue : capture.purchases.issues()) {
                    if (issue.paymentMethod() != null && ("HDPAY_BANK".equals(issue.paymentMethod())
                            != "VN_BANK_DIRECT_PURCHASE".equals(channel))) continue;
                    if ("EXTERNAL_REFUND_UNVERIFIED".equals(issue.reason())) {
                        if (issue.paidAt() == null || issue.paidAt().isBefore(month.to())) {
                            reasons.add(SupportPerformance.Reason.EXTERNAL_REFUND_UNVERIFIED);
                        }
                    } else if (issue.paidAt() == null || month.contains(issue.paidAt())) {
                        reasons.add("MISSING_PAYMENT_TIME".equals(issue.reason())
                                ? SupportPerformance.Reason.PAYMENT_TIME_UNVERIFIED
                                : SupportPerformance.Reason.SOURCE_INCOMPLETE);
                    }
                }
            } else if (withdrawal && capture.withdrawalReferences.stream().anyMatch(reference -> reference.effectiveAt() == null)) {
                reasons.add(SupportPerformance.Reason.WITHDRAWAL_TIME_UNVERIFIED);
            }
            if (withdrawal && events.stream().anyMatch(event -> channel.equals(event.channel())
                    && event.kind() == SupportPerformance.Kind.WITHDRAWAL_REFERENCE
                    && "CONFIRMED".equals(event.sourceStatus()))) {
                reasons.add(SupportPerformance.Reason.WITHDRAWAL_SETTLEMENT_UNVERIFIED);
            }
            boolean attested = reviews.stream().anyMatch(review -> month.month().equals(review.month())
                    && channel.equals(review.channel()) && digest.equals(review.evidenceDigest())
                    && month.from().equals(review.fromUtc().toInstant(ZoneOffset.UTC))
                    && month.to().equals(review.toUtc().toInstant(ZoneOffset.UTC)));
            if (!attested) reasons.add(SupportPerformance.Reason.SOURCE_INCOMPLETE);
            var status = reasons.isEmpty() ? SupportPerformance.Coverage.COMPLETE : verified.isEmpty()
                    ? SupportPerformance.Coverage.UNKNOWN : SupportPerformance.Coverage.PARTIAL;
            var amount = verified.stream().map(event -> withdrawal ? event.amountUsdt() : event.deltaUsdt())
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            channels.put(channel, new SupportPerformance.ChannelCoverage(status, List.copyOf(reasons),
                    verified.isEmpty() && !attested ? null : amount, verified.size(), digest));
            monthReasons.addAll(reasons);
        }
        var status = monthReasons.isEmpty() ? SupportPerformance.Coverage.COMPLETE : channels.values().stream()
                .anyMatch(channel -> channel.status() != SupportPerformance.Coverage.UNKNOWN)
                ? SupportPerformance.Coverage.PARTIAL : SupportPerformance.Coverage.UNKNOWN;
        var firstInMonth = first.stream().filter(payment -> month.contains(payment.at())).toList();
        String firstDigest = SupportPerformance.digest(firstInMonth.toString());
        boolean firstComplete = firstInMonth.stream().allMatch(payment -> payment.confirmed()
                        && payment.attribution().state() != SupportPerformance.AttributionState.UNKNOWN)
                && reviews.stream().anyMatch(review -> "FIRST_PAYMENT_HISTORY".equals(review.channel())
                        && month.month().equals(review.month()) && firstDigest.equals(review.evidenceDigest())
                        && month.from().equals(review.fromUtc().toInstant(ZoneOffset.UTC))
                        && month.to().equals(review.toUtc().toInstant(ZoneOffset.UTC)));
        return new SupportPerformance.Month(month.month(), month.from(), month.to(), month.businessZone(),
                status, List.copyOf(monthReasons), channels, firstComplete);
    }

    void publish(SupportPerformanceCache.DaySnapshot publication) {
        try {
            cache.publish(publication.snapshot(), publication.evidence());
        } catch (BizException failure) {
            cache.markRefreshFailed(publication.snapshot().asOf());
            log.warn("Support performance v2 publication unavailable reason={}", failure.getMessage());
        } catch (RuntimeException failure) {
            cache.markRefreshFailed(publication.snapshot().asOf());
            log.warn("Support performance v2 publication failed type={}", failure.getClass().getSimpleName());
        }
    }

    void markSourceFailed(Instant asOf) {
        if (enabled && !asOf.isBefore(SupportPerformance.HISTORY_START)) cache.markRefreshFailed(asOf);
    }

    static final class Capture {
        private final SupportPerformance.Snapshot directory;
        private final List<ConfirmedWithdrawal> withdrawals = new ArrayList<>();
        private final List<WithdrawalReference> withdrawalReferences = new ArrayList<>();
        private final List<ObservedDeposit> deposits;
        private final SupportDirectPurchaseFactsFacade.Snapshot purchases;
        private final List<FinanceSupportPaymentFactsFacade.WalletReference> references;
        private final List<ConfirmedDepositCorrection> depositCorrections = new ArrayList<>();
        private boolean withdrawalReadFailed;

        Capture(SupportPerformance.Snapshot directory, List<ObservedDeposit> deposits,
                SupportDirectPurchaseFactsFacade.Snapshot purchases, List<FinanceSupportPaymentFactsFacade.WalletReference> references) {
            this.directory = directory;
            this.deposits = deposits;
            this.purchases = purchases;
            this.references = references;
        }

        PerformanceCapture cashScope() {
            var months = directory.months();
            if (months.isEmpty()) return null;
            return new PerformanceCapture(directory.historyStart(), directory.asOf(), withdrawals::add,
                    depositCorrections::add, withdrawalReferences::add, () -> withdrawalReadFailed = true);
        }
    }
}

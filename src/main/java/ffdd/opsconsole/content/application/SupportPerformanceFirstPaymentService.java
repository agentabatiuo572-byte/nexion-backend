package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportDirectPurchaseFactsFacade;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Kind;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@ApplicationService
public class SupportPerformanceFirstPaymentService {
    private static final Comparator<ObservedPayment> FIRST_PAYMENT = Comparator
            .comparing(ObservedPayment::paidAt)
            .thenComparingInt(payment -> payment.kind() == Kind.DEPOSIT ? 0 : 1)
            .thenComparing(ObservedPayment::factId);

    private final FinanceSupportPaymentFactsFacade payments;
    private final SupportDirectPurchaseFactsFacade directPurchases;

    public SupportPerformanceFirstPaymentService(FinanceSupportPaymentFactsFacade payments,
            SupportDirectPurchaseFactsFacade directPurchases) {
        this.payments = payments;
        this.directPurchases = directPurchases;
    }

    /** The caller authorizes customer scope and uses coverage and attribution to publish counts. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot read(Collection<Long> customerIds, Instant asOf) {
        Objects.requireNonNull(asOf, "A first-payment cutoff is required");
        if (customerIds.isEmpty()) {
            return new Snapshot(Map.of(), List.of(), List.of(), List.of());
        }

        SupportPaymentFacts.Snapshot legacy = payments.readHistory(customerIds);
        SupportDirectPurchaseFactsFacade.Snapshot direct = directPurchases.readDirectPurchases(customerIds);
        return select(legacy, direct, asOf);
    }

    Snapshot select(SupportPaymentFacts.Snapshot legacy, SupportDirectPurchaseFactsFacade.Snapshot direct, Instant asOf) {
        ZoneId storageZone = ZoneId.of(legacy.businessZone());
        Map<Long, ObservedPayment> first = new HashMap<>();
        var directOrders = new HashSet<String>();
        for (var purchase : direct.facts()) {
            directOrders.add(purchase.orderNo());
            if (purchase.paidAt().isBefore(asOf)) {
                chooseFirst(first, new ObservedPayment("PURCHASE:" + purchase.orderNo(), purchase.customerId(),
                        Kind.DEVICE_PURCHASE, purchase.orderNo(), purchase.paidAt(), purchase.businessAmountUsdt(),
                        "USDT", purchase.paymentMethod(), purchase.intentNo()));
            }
        }
        for (var payment : legacy.facts()) {
            if (!SupportPaymentFacts.firstEligible(payment.kind())
                    || payment.kind() == Kind.DEVICE_PURCHASE && directOrders.contains(payment.orderNo())) {
                continue;
            }
            Instant paidAt = payment.succeededAt().atZone(storageZone).toInstant();
            if (paidAt.isBefore(asOf)) {
                chooseFirst(first, new ObservedPayment(payment.factId(), payment.customerId(), payment.kind(),
                        payment.orderNo(), paidAt, payment.amount(), payment.currency(), payment.source().name(),
                        payment.sourceBusinessId()));
            }
        }
        return new Snapshot(first, legacy.firstHistory(), legacy.issues(), direct.issues());
    }

    private static void chooseFirst(Map<Long, ObservedPayment> first, ObservedPayment payment) {
        first.merge(payment.customerId(), payment,
                (selected, next) -> FIRST_PAYMENT.compare(selected, next) <= 0 ? selected : next);
    }

    public record ObservedPayment(String factId, long customerId, Kind kind, String orderNo, Instant paidAt,
                                  BigDecimal amount, String currency, String source, String evidenceId) { }

    public record Snapshot(Map<Long, ObservedPayment> observedFirst,
                           List<SupportPaymentFacts.FirstHistory> legacyHistory,
                           List<SupportPaymentFacts.Issue> legacyIssues,
                           List<SupportDirectPurchaseFactsFacade.Issue> directIssues) {
        public Snapshot {
            observedFirst = Map.copyOf(observedFirst);
            legacyHistory = List.copyOf(legacyHistory);
            legacyIssues = List.copyOf(legacyIssues);
            directIssues = List.copyOf(directIssues);
        }

        java.util.Set<Long> coveredCustomers() {
            var covered = new HashSet<Long>();
            legacyHistory.stream().filter(h -> h.status() == SupportPaymentFacts.Status.READY)
                    .forEach(h -> covered.add(h.customerId()));
            directIssues.stream().filter(issue -> !"EXTERNAL_REFUND_UNVERIFIED".equals(issue.reason()))
                    .forEach(issue -> covered.remove(issue.customerId()));
            return java.util.Set.copyOf(covered);
        }
    }
}

package ffdd.opsconsole.finance.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Direct purchase evidence for the new performance definition. The caller authorizes the customer scope. */
public interface SupportDirectPurchaseFactsFacade extends DomainFacade {
    Snapshot readDirectPurchases(Collection<Long> customerIds);
    Snapshot readDirectPurchases(Collection<Long> customerIds, Instant from, Instant to);

    record Fact(
            String intentNo, long customerId, String orderNo, String purchaseKind, String paymentMethod,
            String providerReference, Instant paidAt, BigDecimal businessAmountUsdt,
            BigDecimal subtotalUsdt, BigDecimal roundingAdjustmentUsdt,
            BigDecimal channelFeeUsdt, BigDecimal networkFeeUsdt, BigDecimal totalUsdt, BigDecimal paidVnd) { }

    record Issue(String intentNo, long customerId, String reason) { }

    record Snapshot(List<Fact> facts, List<Issue> issues) {
        public Snapshot {
            facts = List.copyOf(facts);
            issues = List.copyOf(issues);
        }
    }
}

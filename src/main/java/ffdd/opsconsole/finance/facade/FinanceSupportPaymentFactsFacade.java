package ffdd.opsconsole.finance.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.time.LocalDateTime;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.Map;

public interface FinanceSupportPaymentFactsFacade extends DomainFacade {
    SupportPaymentFacts.Snapshot readHistory(Collection<Long> customers);
    default Map<Long, BigDecimal> bankTopupOriginalAmounts(List<SupportPaymentFacts.Fact> provedFacts) { return Map.of(); }
    default List<WalletReference> walletReferences(Collection<Long> customers, Instant from, Instant to) { return List.of(); }

    record WalletReference(long ledgerId, long customerId, String businessNo, String kind, BigDecimal amount, Instant at) { }
    default BeforeSource beforeSource(long customer, SupportPaymentFacts.Source source, String stableBusinessKey) {
        return beforeSource(customer,source,stableBusinessKey,null);
    }
    BeforeSource beforeSource(long customer, SupportPaymentFacts.Source source, String stableBusinessKey,String sourcePartition);
    /** At the writer's existing wallet lock, immediately before its first asset write. */
    void beforeCash(BeforeSource before);
    void beforeGenesisCash(BeforeSource before,Collection<Long> customerIds,String holdingNo);
    /** An observed entry number is not authority; finance rereads the actual trial reward. */
    void trialReward(BeforeSource before,String entryNo);
    Optional<SupportPaymentFacts.Fact> readSettled(BeforeSource before);
    FreshLedgerReceipt insertFreshPaymentLedger(BeforeSource before,BigDecimal amount,BigDecimal balanceAfter,String remark);
    Optional<SupportPaymentFacts.Fact> readSettled(BeforeSource before,FreshLedgerReceipt receipt);

    /** Observation only; a caller implementation is never a fresh-insert authorization. */
    interface FreshLedgerReceipt { long ledgerId(); }

    /** Observation only; implementations supplied by callers never authorize a new success. */
    interface BeforeSource {
        long customerId();
        SupportPaymentFacts.Source source();
        String stableBusinessKey();
        String sourcePartition();
        boolean oldSource();
        Long existingLedgerId();
        String existingFactId();
        LocalDateTime existingSuccessAt();
        List<String> sourceIds();
        String sourceVersion();
        String successTimeField();
        int fractionalSecondDigits();
        String businessZone();
    }
}

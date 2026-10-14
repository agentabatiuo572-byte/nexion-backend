package ffdd.opsconsole.content.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Source;
import java.math.BigDecimal;
import java.util.Collection;

/** Captures ownership inside the actual successful payment transaction. */
public interface SupportPaymentAttributionFacade extends DomainFacade {
    default Prepared prepare(long customer, Source source, String stableBusinessKey) {
        return prepare(customer,source,stableBusinessKey,null);
    }
    Prepared prepare(long customer, Source source, String stableBusinessKey, String sourcePartition);
    void beforeCash(Prepared prepared);
    void beforeGenesisCash(Prepared prepared,Collection<Long> customerIds,String holdingNo);
    void trialReward(Prepared prepared,String entryNo);
    int insertLedger(Prepared prepared, BigDecimal amount, BigDecimal balanceAfter, String remark);
    void record(Prepared prepared);
    interface Prepared { }
}

package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.mapper.SupportAnalyticsFactMapper;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Prewarms before web-server lifecycle start; never impersonates an authenticated operator. */
@Service
public class SupportAnalyticsFactSampler implements SmartInitializingSingleton {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SupportAnalyticsFactSampler.class);
    private final SupportAnalyticsFactMapper mapper;
    private final FinanceSupportPaymentFactsFacade payments;
    private final SupportFundsReadFacade funds;
    private final ProductionSupportPathGuard guard;
    private final TransactionTemplate reads;
    private final Clock clock;
    private final java.util.function.LongSupplier ticks;
    private final AtomicReference<SupportAnalyticsFactSnapshot> latest=new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean started=new java.util.concurrent.atomic.AtomicBoolean();
    @org.springframework.beans.factory.annotation.Autowired
    public SupportAnalyticsFactSampler(SupportAnalyticsFactMapper mapper,FinanceSupportPaymentFactsFacade payments,
            SupportFundsReadFacade funds,ProductionSupportPathGuard guard,PlatformTransactionManager transactions) {
        this(mapper,payments,funds,guard,transactions,Clock.systemUTC());
    }
    SupportAnalyticsFactSampler(SupportAnalyticsFactMapper mapper,FinanceSupportPaymentFactsFacade payments,
            SupportFundsReadFacade funds,ProductionSupportPathGuard guard,PlatformTransactionManager transactions,Clock clock) {
        this(mapper,payments,funds,guard,transactions,clock,System::nanoTime);
    }
    SupportAnalyticsFactSampler(SupportAnalyticsFactMapper mapper,FinanceSupportPaymentFactsFacade payments,
            SupportFundsReadFacade funds,ProductionSupportPathGuard guard,PlatformTransactionManager transactions,Clock clock,java.util.function.LongSupplier ticks) {
        this.mapper=mapper;this.payments=payments;this.funds=funds;this.guard=guard;this.clock=clock;
        this.ticks=ticks;
        reads=new TransactionTemplate(transactions);reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);reads.setReadOnly(true);
    }
    @Override public void afterSingletonsInstantiated() {
        if(!guard.productionSupportAutomationAllowed())return;
        try {refresh();}
        catch(BizException failure) {
            if(!"SUPPORT_ANALYTICS_SOURCE_SNAPSHOT_EXPIRED".equals(failure.getMessage()))throw failure;
            // A validated cold frame may warm pure receipt decoding without being published.
            // Capture again with a new RR start; HTTP is still not listening. Never retry a source failure.
            refresh();
        }
        started.set(true);
    }
    @Scheduled(fixedDelay=100,scheduler="supportAnalyticsFactTaskScheduler")
    public void poll() {
        if(!started.get() || !guard.productionSupportAutomationAllowed())return;
        try {refresh();}
        catch(RuntimeException failure){LOG.warn("Support analytics fact refresh failed type={}",failure.getClass().getSimpleName());}
    }
    synchronized void refresh() {
        long startedAt=ticks.getAsLong();
        SupportAnalyticsFactSnapshot next=reads.execute(status->{
            Instant asOf=Objects.requireNonNull(mapper.nowUtc()).toInstant(ZoneOffset.UTC);
            var ids=List.copyOf(mapper.productionCustomers());
            var payment=ids.isEmpty()?new SupportPaymentFacts.Snapshot(List.of(),List.of(),List.of(),"Asia/Shanghai",asOf):payments.readHistory(ids);
            return new SupportAnalyticsFactSnapshot(asOf,ids,payment,funds.readNetContributions(ids),mapper.attributions(),mapper.attributionProofs(),startedAt);
        });
        Objects.requireNonNull(next);
        long elapsedMs=(ticks.getAsLong()-startedAt)/1_000_000;
        if(!started.get() || elapsedMs>2000)LOG.info("Support analytics fact capture subjects={} durationMs={} asOf={}",next.subjects().size(),elapsedMs,next.asOf());
        next.requireFresh(clock.instant(),ticks.getAsLong());latest.set(next);
    }
    public SupportAnalyticsFactSnapshot current() {
        var snapshot=latest.get();
        if(snapshot==null)throw new BizException(503,"SUPPORT_ANALYTICS_SOURCE_SNAPSHOT_NOT_READY");
        snapshot.requireFresh(clock.instant(),ticks.getAsLong());return snapshot;
    }
    public void requireFresh(SupportAnalyticsFactSnapshot snapshot) {snapshot.requireFresh(clock.instant(),ticks.getAsLong());}
}

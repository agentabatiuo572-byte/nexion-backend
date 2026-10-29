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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Computes one complete generation each minute. HTTP reads the last committed Redis generation. */
@Service
public class SupportAnalyticsFactSampler implements SmartInitializingSingleton {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SupportAnalyticsFactSampler.class);
    private final SupportAnalyticsFactMapper mapper;
    private final FinanceSupportPaymentFactsFacade payments;
    private final SupportFundsReadFacade funds;
    private final ProductionSupportPathGuard guard;
    private final TransactionTemplate reads;
    private final SupportAnalyticsRedisStore store;
    private final ObjectProvider<SupportAnalyticsService> compiler;
    private final SupportAnalyticsCacheCodec codec=new SupportAnalyticsCacheCodec();
    private final AtomicReference<Decoded> decoded=new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean started=new java.util.concurrent.atomic.AtomicBoolean();
    private record Decoded(String json,SupportAnalyticsFactSnapshot snapshot) { }
    public SupportAnalyticsFactSampler(SupportAnalyticsFactMapper mapper,FinanceSupportPaymentFactsFacade payments,
            SupportFundsReadFacade funds,ProductionSupportPathGuard guard,PlatformTransactionManager transactions,
            SupportAnalyticsRedisStore store,ObjectProvider<SupportAnalyticsService> compiler) {
        this.mapper=mapper;this.payments=payments;this.funds=funds;this.guard=guard;this.store=store;this.compiler=compiler;
        reads=new TransactionTemplate(transactions);reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);reads.setReadOnly(true);
    }
    @Override public void afterSingletonsInstantiated() {
        if(!guard.productionSupportAutomationAllowed())return;
        try {
            boolean ready=false;
            try {current();ready=true;}
            catch(BizException failure){if(!Set.of("SUPPORT_ANALYTICS_CACHE_NOT_READY","SUPPORT_ANALYTICS_CACHE_INVALID").contains(failure.getMessage()))throw failure;}
            if(!ready)refresh();
        } catch(RuntimeException failure) {
            LOG.warn("Support analytics startup generation unavailable type={}; minute retry enabled",failure.getClass().getSimpleName());
        } finally {started.set(true);}
    }
    @Scheduled(initialDelay=60000,fixedRate=60000,scheduler="supportAnalyticsFactTaskScheduler")
    public void poll() {
        if(!started.get() || !guard.productionSupportAutomationAllowed())return;
        try {refresh();}
        catch(RuntimeException failure){LOG.warn("Support analytics minute computation failed type={}; previous generation retained",failure.getClass().getSimpleName());}
    }
    void refresh() {
        String token=store.tryLock();if(token==null)return;
        long startedAt=System.nanoTime();
        try {
            SupportAnalyticsFactSnapshot next=reads.execute(status->{
                Instant asOf=Objects.requireNonNull(mapper.nowUtc()).toInstant(ZoneOffset.UTC);
                var ids=List.copyOf(mapper.productionCustomers());
                var payment=ids.isEmpty()?new SupportPaymentFacts.Snapshot(List.of(),List.of(),List.of(),"Asia/Shanghai",asOf):payments.readHistory(ids);
                var raw=new SupportAnalyticsFactSnapshot(asOf,ids,payment,funds.readNetContributions(ids),mapper.attributions(),mapper.attributionProofs());
                return compiler.getObject().compileForCache(raw);
            });
            String json=codec.encode(Objects.requireNonNull(next));
            boolean published=store.publish(token,json);
            LOG.info("Support analytics minute generation published={} subjects={} durationMs={} asOf={}",published,next.subjects().size(),(System.nanoTime()-startedAt)/1_000_000,next.asOf());
        } finally {store.release(token);}
    }
    public SupportAnalyticsFactSnapshot current() {
        String json=store.read();
        if(json==null)throw new BizException(503,"SUPPORT_ANALYTICS_CACHE_NOT_READY");
        var previous=decoded.get();if(previous!=null&&previous.json().equals(json))return previous.snapshot();
        var snapshot=codec.decode(json);decoded.set(new Decoded(json,snapshot));return snapshot;
    }
    public void requireFresh(SupportAnalyticsFactSnapshot snapshot) { /* Stale completed data keeps its true asOf. */ }
}

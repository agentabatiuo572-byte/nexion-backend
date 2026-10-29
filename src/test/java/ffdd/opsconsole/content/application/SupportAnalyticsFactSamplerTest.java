package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.mapper.SupportAnalyticsFactMapper;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.NetSnapshot;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Snapshot;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class SupportAnalyticsFactSamplerTest {
    @Test void singletonPrewarmCompletesBeforeAnyHttpSmartLifecycleStarts() {
        var mapper=mock(SupportAnalyticsFactMapper.class);var funds=mock(SupportFundsReadFacade.class);
        var guard=mock(ProductionSupportPathGuard.class);var tx=mock(PlatformTransactionManager.class);var clock=new MutableClock();
        var events=new java.util.ArrayList<String>();
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(guard.productionSupportAutomationAllowed()).thenReturn(true);
        when(mapper.nowUtc()).thenReturn(LocalDateTime.ofInstant(clock.now,ZoneOffset.UTC));
        when(mapper.productionCustomers()).thenReturn(List.of());
        when(funds.readNetContributions(List.of())).thenAnswer(call->{events.add("capture");return new NetSnapshot(List.of(),List.of(),List.of());});
        when(mapper.attributions()).thenReturn(List.of());when(mapper.attributionProofs()).thenReturn(List.of());
        var sampler=new SupportAnalyticsFactSampler(mapper,mock(FinanceSupportPaymentFactsFacade.class),funds,guard,tx,clock);
        try(var context=new org.springframework.context.support.GenericApplicationContext()) {
            context.registerBean("facts",SupportAnalyticsFactSampler.class,()->sampler);
            context.registerBean("http",org.springframework.context.SmartLifecycle.class,()->new org.springframework.context.SmartLifecycle() {
                boolean running;
                public void start(){events.add("listen");running=true;}
                public void stop(){running=false;}
                public boolean isRunning(){return running;}
            });
            context.refresh();
            assertThat(events).containsExactly("capture","listen");
            assertThat(sampler.current().asOf()).isEqualTo(clock.now);
        }
    }
    static final class MutableClock extends Clock {
        Instant now=SupportAnalyticsFactSnapshotTest.AT;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    @Test void startupPrewarmsBeforeReadyAndRefreshFailureNeverRenewsOldAsOf() throws Exception {
        var mapper=mock(SupportAnalyticsFactMapper.class);var payments=mock(FinanceSupportPaymentFactsFacade.class);
        var funds=mock(SupportFundsReadFacade.class);var guard=mock(ProductionSupportPathGuard.class);
        var tx=mock(PlatformTransactionManager.class);var clock=new MutableClock();
        when(tx.getTransaction(any())).thenAnswer(call->{
            var definition=(TransactionDefinition)call.getArgument(0);
            assertThat(definition.isReadOnly()).isTrue();assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            return new SimpleTransactionStatus();
        });
        when(guard.productionSupportAutomationAllowed()).thenReturn(true);
        when(mapper.nowUtc()).thenReturn(LocalDateTime.ofInstant(clock.now,ZoneOffset.UTC));
        when(mapper.productionCustomers()).thenReturn(List.of(1L));
        when(payments.readHistory(List.of(1L))).thenReturn(new Snapshot(List.of(),List.of(),List.of(),"Asia/Shanghai",clock.now,
            List.of(new ffdd.opsconsole.finance.facade.SupportPaymentFacts.FirstHistory(1L,ffdd.opsconsole.finance.facade.SupportPaymentFacts.Status.UNKNOWN,List.of("HISTORY_UNPROVEN")))));
        when(funds.readNetContributions(List.of(1L))).thenReturn(new NetSnapshot(List.of(new SupportFundsReadFacade.NetCustomer(1L,
            SupportFundsReadFacade.EvidenceState.UNKNOWN,List.of("HISTORY_UNPROVEN"),null,null)),List.of(),List.of()));
        when(mapper.attributions()).thenReturn(List.of());when(mapper.attributionProofs()).thenReturn(List.of());
        var sampler=new SupportAnalyticsFactSampler(mapper,payments,funds,guard,tx,clock);
        assertThatThrownBy(sampler::current).hasMessageContaining("NOT_READY");
        sampler.afterSingletonsInstantiated();var first=sampler.current();assertThat(first.asOf()).isEqualTo(clock.now);
        clock.now=clock.now.plusSeconds(4);when(mapper.productionCustomers()).thenThrow(new IllegalStateException("source failed"));
        sampler.poll();assertThat(sampler.current()).isSameAs(first);
        clock.now=clock.now.plusSeconds(2);assertThatThrownBy(sampler::current).hasMessageContaining("EXPIRED");
        assertThat(first.asOf()).isEqualTo(SupportAnalyticsFactSnapshotTest.AT);
    }
    @Test void slowInitialCaptureFailsBeforeHttpLifecycleRatherThanServingAnUnreadyShell() {
        var mapper=mock(SupportAnalyticsFactMapper.class);var funds=mock(SupportFundsReadFacade.class);
        var guard=mock(ProductionSupportPathGuard.class);var tx=mock(PlatformTransactionManager.class);var clock=new MutableClock();
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(guard.productionSupportAutomationAllowed()).thenReturn(true);
        when(mapper.nowUtc()).thenReturn(LocalDateTime.ofInstant(clock.now,ZoneOffset.UTC));
        when(mapper.productionCustomers()).thenReturn(List.of());
        when(funds.readNetContributions(List.of())).thenAnswer(call->{clock.now=clock.now.plusSeconds(6);return new NetSnapshot(List.of(),List.of(),List.of());});
        when(mapper.attributions()).thenReturn(List.of());when(mapper.attributionProofs()).thenReturn(List.of());
        var sampler=new SupportAnalyticsFactSampler(mapper,mock(FinanceSupportPaymentFactsFacade.class),funds,guard,tx,clock);
        assertThatThrownBy(sampler::afterSingletonsInstantiated).hasMessageContaining("EXPIRED");
        assertThatThrownBy(sampler::current).hasMessageContaining("NOT_READY");
        verify(funds,times(2)).readNetContributions(List.of());
    }
    @Test void expiredColdFrameWarmsOnceThenPublishesOnlyTheNewFreshCapture() {
        var mapper=mock(SupportAnalyticsFactMapper.class);var funds=mock(SupportFundsReadFacade.class);
        var guard=mock(ProductionSupportPathGuard.class);var tx=mock(PlatformTransactionManager.class);var clock=new MutableClock();
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(guard.productionSupportAutomationAllowed()).thenReturn(true);
        when(mapper.nowUtc()).thenAnswer(call->LocalDateTime.ofInstant(clock.now,ZoneOffset.UTC));
        when(mapper.productionCustomers()).thenReturn(List.of());
        var captures=new java.util.concurrent.atomic.AtomicInteger();
        when(funds.readNetContributions(List.of())).thenAnswer(call->{
            clock.now=clock.now.plusSeconds(captures.incrementAndGet()==1?6:1);
            return new NetSnapshot(List.of(),List.of(),List.of());
        });
        when(mapper.attributions()).thenReturn(List.of());when(mapper.attributionProofs()).thenReturn(List.of());
        var sampler=new SupportAnalyticsFactSampler(mapper,mock(FinanceSupportPaymentFactsFacade.class),funds,guard,tx,clock);
        sampler.afterSingletonsInstantiated();
        assertThat(captures.get()).isEqualTo(2);
        assertThat(sampler.current().asOf()).isEqualTo(SupportAnalyticsFactSnapshotTest.AT.plusSeconds(6));
        assertThat(clock.now).isEqualTo(SupportAnalyticsFactSnapshotTest.AT.plusSeconds(7));
    }
}

package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.mapper.SupportAnalyticsFactMapper;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.NetSnapshot;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SupportAnalyticsFactSamplerTest {
    static final class Fixture {
        final SupportAnalyticsFactMapper mapper=mock(SupportAnalyticsFactMapper.class);
        final FinanceSupportPaymentFactsFacade payments=mock(FinanceSupportPaymentFactsFacade.class);
        final SupportFundsReadFacade funds=mock(SupportFundsReadFacade.class);
        final ProductionSupportPathGuard guard=mock(ProductionSupportPathGuard.class);
        final SupportAnalyticsRedisStore store=mock(SupportAnalyticsRedisStore.class);
        final SupportAnalyticsService compiler=mock(SupportAnalyticsService.class);
        final AtomicReference<String> payload=new AtomicReference<>();
        final SupportAnalyticsFactSampler sampler;
        Fixture(){
            var tx=mock(PlatformTransactionManager.class);when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            ObjectProvider<SupportAnalyticsService> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(compiler);
            when(guard.productionSupportAutomationAllowed()).thenReturn(true);
            when(store.read()).thenAnswer(call->payload.get());when(store.tryLock()).thenReturn("token");
            when(store.publish(eq("token"),anyString())).thenAnswer(call->{payload.set(call.getArgument(1));return true;});
            when(mapper.nowUtc()).thenReturn(LocalDateTime.of(2026,10,29,0,0));when(mapper.productionCustomers()).thenReturn(List.of());
            when(mapper.attributions()).thenReturn(List.of());when(mapper.attributionProofs()).thenReturn(List.of());
            when(funds.readNetContributions(List.of())).thenReturn(new NetSnapshot(List.of(),List.of(),List.of()));
            when(compiler.compileForCache(any())).thenAnswer(call->SupportAnalyticsCacheFixtures.empty(((SupportAnalyticsFactSnapshot)call.getArgument(0)).asOf()));
            sampler=new SupportAnalyticsFactSampler(mapper,payments,funds,guard,tx,store,provider);
        }
    }
    @Test void missingCacheIs503WithoutHttpRecomputation(){var f=new Fixture();assertThatThrownBy(f.sampler::current).hasMessageContaining("NOT_READY");verifyNoInteractions(f.mapper,f.payments,f.funds,f.compiler);}
    @Test void startupPublishesBeforeListeningAndMinuteFailureRetainsCompleteOldAsOf(){
        var f=new Fixture();f.sampler.afterSingletonsInstantiated();var first=f.sampler.current();
        doThrow(new IllegalStateException("calculation failed")).when(f.compiler).compileForCache(any());
        f.sampler.poll();assertThat(f.sampler.current()).isSameAs(first);assertThat(first.asOf()).isEqualTo(Instant.parse("2026-10-29T00:00:00Z"));
        verify(f.store,times(1)).publish(eq("token"),anyString());verify(f.store,times(2)).release("token");
    }
    @Test void existingPersistentCacheNeedsNoColdSourceReadAndOldDataRemainsUsable(){
        var f=new Fixture();f.payload.set(new SupportAnalyticsCacheCodec().encode(SupportAnalyticsCacheFixtures.empty(Instant.EPOCH)));
        f.sampler.afterSingletonsInstantiated();assertThat(f.sampler.current().asOf()).isEqualTo(Instant.EPOCH);verifyNoInteractions(f.mapper,f.payments,f.funds,f.compiler);
    }
    @Test void malformedPersistentCacheIsRebuiltAtStartupNotDuringHttpRead(){
        var f=new Fixture();f.payload.set("{\"broken\":true}");assertThatThrownBy(f.sampler::current).hasMessageContaining("CACHE_INVALID");
        verifyNoInteractions(f.compiler);f.sampler.afterSingletonsInstantiated();assertThat(f.sampler.current().compiled()).isNotNull();verify(f.compiler).compileForCache(any());
    }
    @Test void scheduleRunsOncePerMinute(){
        var schedule=SupportAnalyticsFactSampler.class.getDeclaredMethods();
        var poll=Arrays.stream(schedule).filter(m->m.getName().equals("poll")).findFirst().orElseThrow().getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertThat(poll.fixedRate()).isEqualTo(60000);assertThat(poll.initialDelay()).isEqualTo(60000);
    }
    @Test void startupFailureKeepsHttpFailClosedAndMinutePollCanRecover(){
        var f=new Fixture();doThrow(new IllegalStateException("source unavailable")).when(f.compiler).compileForCache(any());
        assertThatCode(f.sampler::afterSingletonsInstantiated).doesNotThrowAnyException();
        assertThatThrownBy(f.sampler::current).hasMessageContaining("CACHE_NOT_READY");
        doReturn(SupportAnalyticsCacheFixtures.empty(Instant.EPOCH)).when(f.compiler).compileForCache(any());
        f.sampler.poll();assertThat(f.sampler.current().asOf()).isEqualTo(Instant.EPOCH);
    }
    @Test void redisStartupOutageDoesNotDisableTheRecoverySchedule(){
        var f=new Fixture();doThrow(new ffdd.opsconsole.shared.exception.BizException(503,"SUPPORT_ANALYTICS_CACHE_UNAVAILABLE")).when(f.store).read();
        assertThatCode(f.sampler::afterSingletonsInstantiated).doesNotThrowAnyException();
        assertThatThrownBy(f.sampler::current).hasMessageContaining("CACHE_UNAVAILABLE");
        doAnswer(call->f.payload.get()).when(f.store).read();f.sampler.poll();assertThat(f.sampler.current().compiled()).isNotNull();
    }
}

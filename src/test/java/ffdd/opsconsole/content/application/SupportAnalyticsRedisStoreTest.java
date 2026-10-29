package ffdd.opsconsole.content.application;

import ffdd.opsconsole.shared.exception.BizException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportAnalyticsRedisStoreTest {
    private final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") private final ValueOperations<String,String> values=mock(ValueOperations.class);
    private final SupportAnalyticsRedisStore store=new SupportAnalyticsRedisStore(redis);
    SupportAnalyticsRedisStoreTest() {when(redis.opsForValue()).thenReturn(values);}

    @Test void missingCacheIsNotARequestTimeRecompute() {
        assertThat(store.read()).isNull();
        verify(values).get(SupportAnalyticsRedisStore.SNAPSHOT_KEY);
        verifyNoMoreInteractions(values);verify(redis,never()).execute(any(),anyList(),any(Object[].class));
    }
    @Test void completeGenerationIsReadUnchanged() {
        when(values.get(SupportAnalyticsRedisStore.SNAPSHOT_KEY)).thenReturn("{\"schema\":1,\"asOf\":\"source-time\"}");
        assertThat(store.read()).isEqualTo("{\"schema\":1,\"asOf\":\"source-time\"}");
    }
    @Test void onlyOneLeaseHolderMayComputeAndPublish() {
        when(values.setIfAbsent(eq(SupportAnalyticsRedisStore.LOCK_KEY),anyString(),eq(SupportAnalyticsRedisStore.LOCK_LEASE))).thenReturn(true,false);
        String token=store.tryLock();assertThat(token).matches("[0-9a-f-]{36}");assertThat(store.tryLock()).isNull();
        when(redis.execute(SupportAnalyticsRedisStore.PUBLISH,List.of(SupportAnalyticsRedisStore.LOCK_KEY,SupportAnalyticsRedisStore.SNAPSHOT_KEY),token,"{\"whole\":true}")).thenReturn(1L);
        assertThat(store.publish(token,"{\"whole\":true}")).isTrue();
        verify(redis).execute(SupportAnalyticsRedisStore.PUBLISH,List.of(SupportAnalyticsRedisStore.LOCK_KEY,SupportAnalyticsRedisStore.SNAPSHOT_KEY),token,"{\"whole\":true}");
    }
    @Test void supersededLeaseDoesNotReportSuccessfulPublication() {
        when(redis.execute(SupportAnalyticsRedisStore.PUBLISH,List.of(SupportAnalyticsRedisStore.LOCK_KEY,SupportAnalyticsRedisStore.SNAPSHOT_KEY),"old-token","payload")).thenReturn(0L);
        assertThat(store.publish("old-token","payload")).isFalse();
        store.release("old-token");
        verify(redis).execute(SupportAnalyticsRedisStore.RELEASE,List.of(SupportAnalyticsRedisStore.LOCK_KEY),"old-token");
    }
    @Test void incompleteGenerationCannotReplacePrevious() {
        assertThatThrownBy(()->store.publish(null,"payload")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->store.publish("token","")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(redis,values);
    }
    @Test void readConnectionFailureIsUnavailableRatherThanMissingOrAnEmptyStatistic() {
        when(values.get(SupportAnalyticsRedisStore.SNAPSHOT_KEY)).thenThrow(new DataAccessResourceFailureException("offline"));
        assertThatThrownBy(store::read).isInstanceOfSatisfying(BizException.class,e->{
            assertThat(e.getCode()).isEqualTo(503);assertThat(e.getMessage()).isEqualTo("SUPPORT_ANALYTICS_CACHE_UNAVAILABLE");
        });
    }
    @Test void publishAndLockFailuresRemainFailures() {
        when(values.setIfAbsent(eq(SupportAnalyticsRedisStore.LOCK_KEY),anyString(),eq(SupportAnalyticsRedisStore.LOCK_LEASE)))
            .thenThrow(new DataAccessResourceFailureException("offline"));
        assertThatThrownBy(store::tryLock).isInstanceOf(BizException.class);
        when(redis.execute(SupportAnalyticsRedisStore.PUBLISH,List.of(SupportAnalyticsRedisStore.LOCK_KEY,SupportAnalyticsRedisStore.SNAPSHOT_KEY),"token","payload"))
            .thenThrow(new DataAccessResourceFailureException("offline"));
        assertThatThrownBy(()->store.publish("token","payload")).isInstanceOf(BizException.class);
    }
}

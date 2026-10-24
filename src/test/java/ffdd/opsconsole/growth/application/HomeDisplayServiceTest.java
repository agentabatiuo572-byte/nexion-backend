package ffdd.opsconsole.growth.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class HomeDisplayServiceTest {
    final ObjectMapper json = new ObjectMapper();
    final Map<String, String> database = new HashMap<>();
    final AtomicLong time = new AtomicLong(1_790_000_000_000L);
    final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final Clock clock = mock(Clock.class);
    final AbstractPlatformTransactionManager manager = new AbstractPlatformTransactionManager() {
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object tx, TransactionDefinition definition) { }
        protected void doCommit(DefaultTransactionStatus status) { }
        protected void doRollback(DefaultTransactionStatus status) { }
    };
    HomeDisplayService service;

    @BeforeEach void setup() throws Exception {
        when(clock.millis()).thenAnswer(a -> time.get());
        when(config.activeValue(anyString())).thenAnswer(a -> Optional.ofNullable(database.get(a.getArgument(0))));
        when(config.activeValueForUpdate(GrowthPublicStatsService.VERSION_KEY)).thenReturn(Optional.of("1"));
        doAnswer(a -> { database.put(a.getArgument(0), a.getArgument(1)); return null; })
                .when(config).upsertAdminValue(anyString(), anyString(), anyString(), anyString(), anyString());
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        database.put(GrowthPublicStatsService.VALUES_KEY, json.writeValueAsString(settings(false)));
        service = new HomeDisplayService(config, json, redis, clock, manager);
    }

    Map<String, Object> settings(boolean enabled) {
        return new LinkedHashMap<>(Map.of("homeOnlineCurrent", 28432, "homeOnlineStepMin", 1, "homeOnlineStepMax", 3,
                "homePaidCurrent", 130532, "homePaidStepMin", 1.10, "homePaidStepMax", 5.50, "homeDisplayEnabled", enabled));
    }

    Map<String, Object> change(Map<String, Object> before, boolean enabled) {
        Map<String, Object> next = new LinkedHashMap<>(before);
        next.put("homeDisplayEnabled", enabled);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            service.configure(before, next, time.get());
            try { database.put(GrowthPublicStatsService.VALUES_KEY, json.writeValueAsString(next)); }
            catch (Exception ex) { throw new RuntimeException(ex); }
        });
        return next;
    }

    @Test void startPauseRestartResumeKeepsSharedAmounts() {
        assertEquals(false, service.current().get("enabled"));
        time.addAndGet(120_000);
        assertEquals(28432, ((long[]) service.current().get("online"))[0]);
        Map<String, Object> running = change(settings(false), true);
        Map<String, Object> plan = service.current();
        time.addAndGet(17_000);
        long online = ((long[]) plan.get("online"))[17];
        long paid = ((long[]) plan.get("paidCents"))[17];
        assertTrue(online >= 28449 && online <= 28483);
        Map<String, Object> paused = change(running, false);
        time.addAndGet(900_000);
        service = new HomeDisplayService(config, json, redis, clock, manager);
        assertEquals(online, ((long[]) service.current().get("online"))[0]);
        assertEquals(paid, ((long[]) service.current().get("paidCents"))[0]);
        change(paused, true);
        Map<String, Object> resumed = service.current();
        assertEquals(online, ((long[]) resumed.get("online"))[0]);
        assertEquals(paid, ((long[]) resumed.get("paidCents"))[0]);
        assertEquals(121, ((long[]) resumed.get("online")).length);
        verify(config, atLeastOnce()).activeValueForUpdate(GrowthPublicStatsService.VERSION_KEY);
    }

    @Test void expiredControlCacheIsNotGrantedANewLifetime() throws Exception {
        HomeDisplayBatch old = new HomeDisplayBatch(1, false, time.get(), new long[]{5}, new long[]{600});
        when(redis.opsForValue().get(HomeDisplayService.CACHE_KEY)).thenReturn(
                json.writeValueAsString(new HomeDisplayService.CachedBatch(old, time.get() + 1000)));
        assertEquals(time.get() + 1000, service.current().get("validUntil"));
        time.addAndGet(1001);
        assertEquals(28432, ((long[]) service.current().get("online"))[0]);
    }

    @Test void cachePublicationFailureDoesNotUndoCommittedPause() {
        service.current();
        Map<String, Object> running = change(settings(false), true);
        time.addAndGet(4000);
        doThrow(new IllegalStateException("redis unavailable")).when(redis)
                .execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(Object[].class));
        assertDoesNotThrow(() -> change(running, false));
        HomeDisplayService restarted = new HomeDisplayService(config, json, redis, clock, manager);
        assertEquals(false, restarted.current().get("enabled"));
    }
}

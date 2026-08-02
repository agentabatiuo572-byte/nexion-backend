package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;

class OpsGrowthCommandBoundaryTest {
    private AdminIdempotencyService idempotency;
    private EventOutboxService outbox;
    private OpsGrowthCommandBoundary boundary;

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        idempotency = mock(AdminIdempotencyService.class);
        outbox = mock(EventOutboxService.class);
        boundary = new OpsGrowthCommandBoundary(idempotency, outbox);
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
    }

    @Test
    void successfulMutationIsIdempotentAndPublishesCanonicalGrowthEvent() {
        ApiResult<Map<String, Object>> result = boundary.execute(
                "H3", "QUEST_REWARD_UPDATE", "dayOne.tasks.0.reward", "idem-h3-1",
                Map.of("value", "12"), () -> ApiResult.ok(Map.of("updated", true)));

        assertThat(result.getCode()).isZero();
        verify(idempotency).execute(eq("GROWTH:H3:QUEST_REWARD_UPDATE:dayOne.tasks.0.reward"),
                eq("idem-h3-1"), anyString(), eq(ApiResult.class), any(Supplier.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(outbox).publish(eq("GROWTH_COMMAND"), eq("H3:dayOne.tasks.0.reward"),
                eq("admin.growth_config_changed"), payload.capture());
        assertThat(payload.getValue()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "module_id", "H3",
                "operation", "QUEST_REWARD_UPDATE",
                "target_id", "dayOne.tasks.0.reward",
                "idempotency_key", "idem-h3-1"));
    }

    @Test
    void rejectedMutationDoesNotPublishAnEvent() {
        ApiResult<Map<String, Object>> result = boundary.execute(
                "H4", "WHEEL_TIER_UPDATE", "tier-1", "idem-h4-1",
                Map.of("probability", 101), () -> ApiResult.fail(422, "WHEEL_PROBABILITY_TOTAL_EXCEEDS_100"));

        assertThat(result.getCode()).isEqualTo(422);
        verify(outbox, never()).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void h3QuestConfigDeadlockIsNormalizedToStaleWithoutPublishing() {
        ApiResult<Map<String, Object>> result = boundary.execute(
                "H3", "QUEST_CONFIG_UPDATE", "promoBanner.countdownDays", "idem-h3-deadlock",
                Map.of("value", "8", "expectedValue", "7"),
                () -> {
                    throw new DeadlockLoserDataAccessException("deadlock", null);
                });

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("QUEST_CONFIG_STALE");
        verify(outbox, never()).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void h3QuestConfigLockTimeoutIsNormalizedToStaleWithoutPublishing() {
        ApiResult<Map<String, Object>> result = boundary.execute(
                "H3", "QUEST_CONFIG_UPDATE", "promoBanner.countdownDays", "idem-h3-lock-timeout",
                Map.of("value", "8", "expectedValue", "7"),
                () -> {
                    throw new CannotAcquireLockException("lock timeout");
                });

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("QUEST_CONFIG_STALE");
        verify(outbox, never()).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void h3QuestConfigDoesNotSwallowNonLockFailures() {
        assertThatThrownBy(() -> boundary.execute(
                "H3", "QUEST_CONFIG_UPDATE", "promoBanner.countdownDays", "idem-h3-bug",
                Map.of("value", "8", "expectedValue", "7"),
                () -> {
                    throw new IllegalStateException("unexpected mapper failure");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("unexpected mapper failure");

        verify(outbox, never()).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void lockFailuresOutsideH3QuestConfigRemainExceptional() {
        assertThatThrownBy(() -> boundary.execute(
                "H4", "EVENT_CREATE", "regional-pk", "idem-h4-deadlock",
                Map.of("status", "draft"),
                () -> {
                    throw new DeadlockLoserDataAccessException("deadlock", null);
                }))
                .isInstanceOf(DeadlockLoserDataAccessException.class);

        verify(outbox, never()).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void concurrentH3QuestConfigCommandsProduceOneWinnerAndOneStaleLoser() throws Exception {
        CountDownLatch bothInsideAction = new CountDownLatch(2);
        AtomicInteger arrival = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ApiResult<Map<String, Object>>> first = executor.submit(() -> boundary.execute(
                    "H3", "QUEST_CONFIG_UPDATE", "promoBanner.countdownDays", "idem-h3-concurrent-a",
                    Map.of("value", "8", "expectedValue", "7"),
                    () -> concurrentResult(bothInsideAction, arrival)));
            Future<ApiResult<Map<String, Object>>> second = executor.submit(() -> boundary.execute(
                    "H3", "QUEST_CONFIG_UPDATE", "promoBanner.countdownDays", "idem-h3-concurrent-b",
                    Map.of("value", "9", "expectedValue", "7"),
                    () -> concurrentResult(bothInsideAction, arrival)));

            assertThat(List.of(first.get(), second.get()))
                    .extracting(ApiResult::getCode)
                    .containsExactlyInAnyOrder(0, 422);
            verify(outbox, times(1)).publish(
                    eq("GROWTH_COMMAND"),
                    eq("H3:promoBanner.countdownDays"),
                    eq("admin.growth_config_changed"),
                    any());
        } finally {
            executor.shutdownNow();
        }
    }

    private ApiResult<Map<String, Object>> concurrentResult(
            CountDownLatch bothInsideAction,
            AtomicInteger arrival) {
        int current = arrival.incrementAndGet();
        bothInsideAction.countDown();
        try {
            if (!bothInsideAction.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent H3 test did not release");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrent H3 test interrupted", ex);
        }
        if (current == 1) {
            return ApiResult.ok(Map.of("updated", true));
        }
        throw new DeadlockLoserDataAccessException("deadlock", null);
    }
}

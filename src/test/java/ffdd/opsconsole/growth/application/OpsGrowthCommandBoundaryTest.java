package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
}

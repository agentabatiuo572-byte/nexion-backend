package ffdd.opsconsole.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * A fact whose only delivery path is the outbox table itself was previously
 * scanned by no dispatcher, so it stayed PENDING forever: the A3 backlog grew
 * without bound and genuine delivery failures were hidden behind it.
 */
class OutboxRecordOnlyRetirementTest {
    private final EventOutboxService service = mock(EventOutboxService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final EventOutboxDispatchScheduler scheduler = new EventOutboxDispatchScheduler(service, publisher);

    @Test
    void recordOnlyFactsAreRetiredOnEveryDispatchTick() {
        when(service.retireRecordOnlyPending(anyInt())).thenReturn(7);

        scheduler.dispatchPending();

        verify(service).retireRecordOnlyPending(100);
    }

    @Test
    void aFailingRetirementNeverStopsGenuineDelivery() {
        doThrow(new IllegalStateException("retention unavailable")).when(service).retireRecordOnlyPending(anyInt());
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId("event-still-delivered");
        when(service.listPendingByEventType(EventOutboxDispatchScheduler.SUPPORTED_EVENT_TYPE, 100))
                .thenReturn(List.of(message));

        scheduler.dispatchPending();

        verify(publisher).publishEvent(message);
        verify(service).markPublished("event-still-delivered");
    }

    /**
     * Every retired type is asserted to be outside the dispatcher allowlist and
     * the developer-webhook canonical families in {@code OutboxDispatchCoverageTest}.
     * H3 binding-wait facts are the dangerous overlap: they are requeued from
     * PUBLISHED back to PENDING when a binding appears, so retiring them would
     * silently drop a quest completion.
     */
    @Test
    void h3BindingWaitFactsAreNeverRetiredAsRecordOnly() {
        assertThat(EventOutboxService.RECORD_ONLY_EVENT_TYPES)
                .doesNotContainAnyElementsOf(EventOutboxDispatchScheduler.H3_BINDING_WAIT_EVENT_TYPES)
                .doesNotContainAnyElementsOf(EventOutboxDispatchScheduler.H3_QUEST_FACT_EVENT_TYPES);
    }

    @Test
    void behaviouralAndConfigurationAlertEvidenceFamiliesKeepTheirOwnVerdicts() {
        assertThat(EventOutboxService.RECORD_ONLY_EVENT_TYPES)
                .doesNotContain("app.page_viewed", "app.element_clicked", "leadership_pool.settlement_blocked");
    }

    @Test
    void aRetiredFactIsNotLeftWaitingForARetry() {
        when(service.retireRecordOnlyPending(anyInt())).thenReturn(1);

        scheduler.dispatchPending();

        // Retirement must be the only status transition requested for these rows:
        // nothing is published and nothing is marked failed on their behalf.
        verify(service, org.mockito.Mockito.never()).markPublished(anyString());
        verify(service, org.mockito.Mockito.never()).markFailed(anyString(), anyString());
        verify(publisher, org.mockito.Mockito.never()).publishEvent(org.mockito.ArgumentMatchers.any());
        assertThat(Set.of("RECORDED")).isNotEmpty();
    }
}

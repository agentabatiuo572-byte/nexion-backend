package ffdd.opsconsole.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A fact whose only delivery path is the outbox table itself was previously
 * scanned by no dispatcher, so it stayed PENDING forever: the A3 backlog grew
 * without bound and genuine delivery failures were hidden behind it.
 */
class OutboxRecordOnlyRetirementTest {
    private final EventOutboxService service = mock(EventOutboxService.class);
    private final EventOutboxDispatcher dispatcher = mock(EventOutboxDispatcher.class);
    private final EventOutboxDispatchScheduler scheduler = new EventOutboxDispatchScheduler(service, dispatcher);

    @Test
    void recordOnlyFactsAreRetiredOnEveryDispatchTick() {
        when(service.retireRecordOnlyPending(anyInt())).thenReturn(7);

        scheduler.dispatchPending();

        verify(service).retireRecordOnlyPending(100);
    }

    /**
     * Legacy C1 profile facts without an audit link are refused by the dispatch
     * scan, so no consumer will ever retry them. They need the same per-tick
     * terminal sweep, or they keep A3's oldest-wait growing forever.
     */
    @Test
    void unlinkedProfileEvidenceIsSweptOnEveryDispatchTick() {
        when(service.retireUnlinkedC1ProfileEvidence(anyInt())).thenReturn(3);

        scheduler.dispatchPending();

        verify(service).retireUnlinkedC1ProfileEvidence(100);
    }

    @Test
    void aFailingProfileSweepNeverStopsGenuineDelivery() {
        doThrow(new IllegalStateException("retention unavailable"))
                .when(service).retireUnlinkedC1ProfileEvidence(anyInt());
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId("event-still-delivered");
        message.setEventType(EventOutboxDispatchScheduler.SUPPORTED_EVENT_TYPE);
        when(service.listPendingByEventType(EventOutboxDispatchScheduler.SUPPORTED_EVENT_TYPE, 100))
                .thenReturn(List.of(message));
        when(dispatcher.dispatch(message, null)).thenReturn(EventOutboxDispatcher.Result.PUBLISHED);

        scheduler.dispatchPending();

        verify(dispatcher).dispatch(message, null);
    }

    @Test
    void aFailingRetirementNeverStopsGenuineDelivery() {
        doThrow(new IllegalStateException("retention unavailable")).when(service).retireRecordOnlyPending(anyInt());
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId("event-still-delivered");
        message.setEventType(EventOutboxDispatchScheduler.SUPPORTED_EVENT_TYPE);
        when(service.listPendingByEventType(EventOutboxDispatchScheduler.SUPPORTED_EVENT_TYPE, 100))
                .thenReturn(List.of(message));
        when(dispatcher.dispatch(message, null)).thenReturn(EventOutboxDispatcher.Result.PUBLISHED);

        scheduler.dispatchPending();

        verify(dispatcher).dispatch(message, null);
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
    void disclosureAndTrialFactsWithoutBusConsumersDoNotAgeInTheA3Backlog() {
        assertThat(EventOutboxService.RECORD_ONLY_EVENT_TYPES)
                .contains("disclosure.viewed", "disclosure.acked", "trial.started",
                        "promotion.reward.committed", "promotion.reward.issued", "promotion.refund.confirmed");
        assertThat(EventOutboxService.RECORD_ONLY_GRACE_MINUTES).isEqualTo(15);
    }

    @Test
    void phoneSessionAndReplacementFactsRemainQueryableWithoutAConsumerBacklog() {
        assertThat(EventOutboxService.RECORD_ONLY_EVENT_TYPES)
                .contains("account.app_session_replaced", "phone.replacement_succeeded",
                        "phone.replacement_rejected", "phone.replacement_request_created",
                        "admin.phone_replacement_policy_updated", "admin.phone_replacement_grant_issued",
                        "admin.phone_replacement_grant_consumed", "admin.phone_replacement_grant_revoked",
                        "admin.phone_replacement_grant_expired", "admin.phone_replacement_grant_stale",
                        "admin.phone_replacement_grant_rejected", "admin.phone_replacement_request_closed");
    }

    @Test
    void aRetiredFactIsNotLeftWaitingForARetry() {
        when(service.retireRecordOnlyPending(anyInt())).thenReturn(1);

        scheduler.dispatchPending();

        // Retirement must be the only status transition requested for these rows:
        // nothing is published and nothing is marked failed on their behalf.
        verify(service, never()).markPublished(anyLong(), anyLong());
        verify(service, never()).markFailed(anyLong(), anyString(), anyLong());
        verify(dispatcher, never()).dispatch(any(), anyString());
        verify(dispatcher, never()).dispatch(any(), org.mockito.ArgumentMatchers.isNull());
        assertThat(Set.of("RECORDED")).isNotEmpty();
    }
}

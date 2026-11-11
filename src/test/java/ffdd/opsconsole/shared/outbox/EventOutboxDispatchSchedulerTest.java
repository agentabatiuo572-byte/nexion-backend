package ffdd.opsconsole.shared.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class EventOutboxDispatchSchedulerTest {
    private final EventOutboxService service = mock(EventOutboxService.class);
    private final EventOutboxDispatcher dispatcher = mock(EventOutboxDispatcher.class);
    private final EventOutboxDispatchScheduler scheduler = new EventOutboxDispatchScheduler(service, dispatcher);

    @Test
    void activeLateBindingRequeuesOnlyKnownH3WaitEventTypesBeforeTheNormalScan() {
        scheduler.dispatchPending();

        for (String eventType : EventOutboxDispatchScheduler.H3_BINDING_WAIT_EVENT_TYPES) {
            verify(service).requeuePublishedPendingBinding(eventType, "h3-quest-completion");
        }
        verify(service, org.mockito.Mockito.times(EventOutboxDispatchScheduler.H3_BINDING_WAIT_EVENT_TYPES.size()))
                .requeuePublishedPendingBinding(anyString(), org.mockito.ArgumentMatchers.eq("h3-quest-completion"));
    }

    @Test
    void derivedExchangeAndReferralSourceFactsReachTheirVerifyingConsumer() {
        scheduler.dispatchPending();

        verify(service).listPendingByEventType("exchange.swapped", 100);
        verify(service).listPendingByEventType("referral.bound", 100);
    }

    @Test
    void deliveredMessagesAreHandedToTheDispatcher() {
        EventOutboxMessage message = message("event-ok");
        when(service.listPendingByEventType(EventOutboxDispatchScheduler.SUPPORTED_EVENT_TYPE, 100))
                .thenReturn(List.of(message));
        when(dispatcher.dispatch(message, null)).thenReturn(EventOutboxDispatcher.Result.PUBLISHED);

        scheduler.dispatchPending();

        verify(dispatcher).dispatch(message, null);
    }

    @Test
    void failedDeliveryIsReportedByTheDispatcherAndDoesNotStopTheTick() {
        EventOutboxMessage message = message("event-fail");
        when(service.listPendingByEventType(EventOutboxDispatchScheduler.SUPPORTED_EVENT_TYPE, 100))
                .thenReturn(List.of(message));
        when(dispatcher.dispatch(message, null)).thenReturn(EventOutboxDispatcher.Result.FAILED);

        scheduler.dispatchPending();

        verify(dispatcher).dispatch(message, null);
    }

    @Test
    void skippedDeliveryDoesNotStopTheTick() {
        EventOutboxMessage message = message("event-skipped");
        when(service.listPendingByEventType(EventOutboxDispatchScheduler.SUPPORTED_EVENT_TYPE, 100))
                .thenReturn(List.of(message));
        when(dispatcher.dispatch(message, null)).thenReturn(EventOutboxDispatcher.Result.SKIPPED);

        scheduler.dispatchPending();

        verify(dispatcher).dispatch(message, null);
    }

    @Test
    void tamperMessagesUseTheSameDurableDispatch() {
        EventOutboxMessage message = message("event-tamper");
        message.setEventType(EventOutboxDispatchScheduler.TAMPER_EVENT_TYPE);
        when(service.listPendingByEventType(EventOutboxDispatchScheduler.TAMPER_EVENT_TYPE, 100))
                .thenReturn(List.of(message));
        when(dispatcher.dispatch(message, null)).thenReturn(EventOutboxDispatcher.Result.PUBLISHED);

        scheduler.dispatchPending();

        verify(dispatcher).dispatch(message, null);
    }

    @Test
    void c2HighRiskMessagesUseTheDurableDispatch() {
        EventOutboxMessage message = message("event-c2");
        String eventType = "admin.user_impersonation_ended";
        message.setEventType(eventType);
        when(service.listPendingByEventType(eventType, 100)).thenReturn(List.of(message));
        when(dispatcher.dispatch(message, null)).thenReturn(EventOutboxDispatcher.Result.PUBLISHED);

        scheduler.dispatchPending();

        verify(dispatcher).dispatch(message, null);
    }

    @Test
    void c5SecurityMessagesUseTheDurableDispatch() {
        EventOutboxMessage message = message("event-c5");
        String eventType = "auth.refresh_token_reuse_detected";
        message.setEventType(eventType);
        when(service.listPendingByEventType(eventType, 100)).thenReturn(List.of(message));
        when(dispatcher.dispatch(message, null)).thenReturn(EventOutboxDispatcher.Result.PUBLISHED);

        scheduler.dispatchPending();

        verify(dispatcher).dispatch(message, null);
    }

    @Test
    void h3CanonicalQuestFactsUseTheDurableDispatch() {
        EventOutboxMessage message = message("event-h3");
        String eventType = "H8_REFERRAL_REWARD_SETTLED";
        message.setEventType(eventType);
        when(service.listPendingByEventType(eventType, 100)).thenReturn(List.of(message));
        when(dispatcher.dispatch(message, null)).thenReturn(EventOutboxDispatcher.Result.PUBLISHED);

        scheduler.dispatchPending();

        verify(dispatcher).dispatch(message, null);
    }

    @Test
    void developerWebhookCanonicalFactsAreIsolatedFromTheGlobalDispatcher() {
        EventOutboxMessage message = message("event-webhook-order");
        message.setEventType("order.completed");
        when(service.listPendingByEventType("order.completed", 100)).thenReturn(List.of(message));

        scheduler.dispatchPending();

        verify(service, never()).listPendingByEventType("order.completed", 100);
        verify(dispatcher, never()).dispatch(any(), anyString());
        verify(dispatcher, never()).dispatch(any(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void d6QuoteChangesUseTheDurableDispatch() {
        EventOutboxMessage message = message("event-d6");
        message.setEventType(EventOutboxDispatchScheduler.D6_FX_QUOTE_CHANGED_EVENT_TYPE);
        when(service.listPendingByEventType(
                EventOutboxDispatchScheduler.D6_FX_QUOTE_CHANGED_EVENT_TYPE, 100))
                .thenReturn(List.of(message));
        when(dispatcher.dispatch(message, null)).thenReturn(EventOutboxDispatcher.Result.PUBLISHED);

        scheduler.dispatchPending();

        verify(dispatcher).dispatch(message, null);
    }

    @Test
    void confirmedWithdrawalUsesTheDurableD2Dispatch() {
        EventOutboxMessage message = message("event-withdraw-confirmed");
        String eventType = "withdraw.confirmed";
        message.setEventType(eventType);
        when(service.listPendingByEventType(eventType, 100)).thenReturn(List.of(message));
        when(dispatcher.dispatch(message, null)).thenReturn(EventOutboxDispatcher.Result.PUBLISHED);

        scheduler.dispatchPending();

        verify(dispatcher).dispatch(message, null);
    }

    private EventOutboxMessage message(String eventId) {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(eventId);
        message.setEventType(EventOutboxDispatchScheduler.SUPPORTED_EVENT_TYPE);
        return message;
    }
}

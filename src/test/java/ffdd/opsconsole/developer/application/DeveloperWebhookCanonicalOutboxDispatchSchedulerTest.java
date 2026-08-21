package ffdd.opsconsole.developer.application;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class DeveloperWebhookCanonicalOutboxDispatchSchedulerTest {
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final DeveloperWebhookCanonicalOutboxDispatchScheduler scheduler =
            new DeveloperWebhookCanonicalOutboxDispatchScheduler(outbox, publisher);

    @Test
    void canonicalWebhookEventIsPublishedAndMarkedWithoutWaitingForBroadOutboxScan() {
        EventOutboxMessage message = message("evt-order");
        when(outbox.listPendingByCanonicalType("order.completed", 0L, 50)).thenReturn(List.of(message));

        scheduler.dispatchPending();

        verify(publisher).publishEvent(message);
        verify(outbox).markPublished("evt-order");
    }

    @Test
    void oneCanonicalEventTypeReadFailureDoesNotBlockOtherTypes() {
        EventOutboxMessage message = message("evt-order");
        when(outbox.listPendingByCanonicalType("checkout.started", 0L, 50))
                .thenThrow(new IllegalStateException("slow event type"));
        when(outbox.listPendingByCanonicalType("order.completed", 0L, 50)).thenReturn(List.of(message));

        scheduler.dispatchPending();

        verify(publisher).publishEvent(message);
        verify(outbox).markPublished("evt-order");
    }

    @Test
    void failedBridgeIsReturnedToDurableOutboxRetryState() {
        EventOutboxMessage message = message("evt-fail");
        when(outbox.listPendingByCanonicalType("order.completed", 0L, 50)).thenReturn(List.of(message));
        doThrow(new IllegalStateException("bridge unavailable")).when(publisher).publishEvent(message);

        scheduler.dispatchPending();

        verify(outbox).markFailed("evt-fail", "bridge unavailable");
    }

    @Test
    void cursorAdvancesAndWrapsAfterCanonicalBacklogBatch() {
        EventOutboxMessage old = message("evt-old");
        old.setId(10L);
        EventOutboxMessage fresh = message("evt-fresh");
        fresh.setId(20L);
        when(outbox.listPendingByCanonicalType("order.completed", 0L, 50)).thenReturn(List.of(old));
        when(outbox.listPendingByCanonicalType("order.completed", 10L, 50)).thenReturn(List.of(fresh));

        scheduler.dispatchPending();
        scheduler.dispatchPending();

        verify(publisher, times(2)).publishEvent(old);
        verify(publisher).publishEvent(fresh);
        verify(outbox).listPendingByCanonicalType("order.completed", 10L, 50);
    }

    private EventOutboxMessage message(String eventId) {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(eventId);
        message.setEventType("order.completed");
        message.setEventName("order.completed");
        message.setId(1L);
        return message;
    }
}

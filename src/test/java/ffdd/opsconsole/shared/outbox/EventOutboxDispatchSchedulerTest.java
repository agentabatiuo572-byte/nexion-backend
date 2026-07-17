package ffdd.opsconsole.shared.outbox;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class EventOutboxDispatchSchedulerTest {
    private final EventOutboxService service = mock(EventOutboxService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final EventOutboxDispatchScheduler scheduler = new EventOutboxDispatchScheduler(service, publisher);

    @Test
    void deliveredMessagesAreMarkedPublished() {
        EventOutboxMessage message = message("event-ok");
        when(service.listPendingByEventType(EventOutboxDispatchScheduler.SUPPORTED_EVENT_TYPE, 100))
                .thenReturn(List.of(message));

        scheduler.dispatchPending();

        verify(publisher).publishEvent(message);
        verify(service).markPublished("event-ok");
    }

    @Test
    void failedDeliveryIsRetriedThroughOutboxState() {
        EventOutboxMessage message = message("event-fail");
        when(service.listPendingByEventType(EventOutboxDispatchScheduler.SUPPORTED_EVENT_TYPE, 100))
                .thenReturn(List.of(message));
        doThrow(new IllegalStateException("consumer unavailable")).when(publisher).publishEvent(message);

        scheduler.dispatchPending();

        verify(service).markFailed("event-fail", "consumer unavailable");
    }

    @Test
    void tamperMessagesUseTheSameDurableDispatchAndPublicationState() {
        EventOutboxMessage message = message("event-tamper");
        message.setEventType(EventOutboxDispatchScheduler.TAMPER_EVENT_TYPE);
        when(service.listPendingByEventType(EventOutboxDispatchScheduler.TAMPER_EVENT_TYPE, 100))
                .thenReturn(List.of(message));

        scheduler.dispatchPending();

        verify(publisher).publishEvent(message);
        verify(service).markPublished("event-tamper");
    }

    private EventOutboxMessage message(String eventId) {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(eventId);
        message.setEventType(EventOutboxDispatchScheduler.SUPPORTED_EVENT_TYPE);
        return message;
    }
}

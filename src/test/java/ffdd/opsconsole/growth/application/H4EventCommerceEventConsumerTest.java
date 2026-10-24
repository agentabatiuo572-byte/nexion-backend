package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class H4EventCommerceEventConsumerTest {
    private final H4EventEngine engine = mock(H4EventEngine.class);
    private final EventConsumerDeliveryService deliveries = mock(EventConsumerDeliveryService.class);
    private final H4EventCommerceEventConsumer consumer = new H4EventCommerceEventConsumer(engine, deliveries, new ObjectMapper());

    private EventOutboxMessage event(String type, String aggregate) {
        var event = new EventOutboxMessage();
        event.setEventId("EVT-1"); event.setAggregateType(aggregate); event.setAggregateId("ORDER-1");
        event.setEventType(type); event.setServerAuthoritative(true); event.setPayload("{\"user_id\":7}");
        when(deliveries.claim(event, H4EventCommerceEventConsumer.GROUP, H4EventCommerceEventConsumer.TOPIC, "EVT-1", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "EVT-1", "PROCESSING", 1));
        return event;
    }

    @Test
    void canonicalPaidOrderAndReinvestmentReachTheirOwnTransactions() {
        consumer.onOutboxMessage(event("checkout.completed", "ORDER"));
        verify(engine).purchaseCompleted(7L, "ORDER-1");
        consumer.onOutboxMessage(event("wallet.reinvest", "REPURCHASE_ORDER"));
        verify(engine).reinvestCompleted(7L, "ORDER-1");
        verify(deliveries, times(2)).markSuccess(H4EventCommerceEventConsumer.GROUP, "EVT-1", 1);
    }

    @Test
    void unverifiedEventsAndCompletedDeliveryCannotGrantAgain() {
        var event = event("checkout.completed", "ORDER"); event.setServerAuthoritative(false);
        consumer.onOutboxMessage(event); verifyNoInteractions(engine);
        verify(deliveries, never()).claim(any(), anyString(), anyString(), anyString(), anyInt());
        event.setServerAuthoritative(true);
        when(deliveries.claim(event, H4EventCommerceEventConsumer.GROUP, H4EventCommerceEventConsumer.TOPIC, "EVT-1", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(false, "EVT-1", "SUCCESS", 1));
        consumer.onOutboxMessage(event); verifyNoInteractions(engine);
    }

    @Test
    void budgetFailureRemainsRetryableAndDoesNotMarkDeliverySuccessful() {
        var event = event("checkout.completed", "ORDER");
        doThrow(new IllegalStateException("EVENT_BUDGET_EXHAUSTED")).when(engine).purchaseCompleted(7L, "ORDER-1");
        assertThatThrownBy(() -> consumer.onOutboxMessage(event)).hasMessage("EVENT_BUDGET_EXHAUSTED");
        verify(deliveries).markFailure(H4EventCommerceEventConsumer.GROUP, "EVT-1", 0, "EVENT_BUDGET_EXHAUSTED");
        verify(deliveries, never()).markSuccess(anyString(), anyString(), anyInt());
    }
}

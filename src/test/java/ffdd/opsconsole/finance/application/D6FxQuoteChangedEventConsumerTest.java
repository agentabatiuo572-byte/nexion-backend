package ffdd.opsconsole.finance.application;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class D6FxQuoteChangedEventConsumerTest {
    private final EventConsumerDeliveryService deliveries = Mockito.mock(EventConsumerDeliveryService.class);
    private final D6FxQuoteChangedEventConsumer consumer = new D6FxQuoteChangedEventConsumer(deliveries);

    @Test
    void acknowledgesCanonicalQuoteChange() {
        EventOutboxMessage message = message("d6-event", "admin.fx_quote_updated", "FX_QUOTE_CONFIG", "VND_USDT");
        when(deliveries.claim(message, D6FxQuoteChangedEventConsumer.CONSUMER_GROUP,
                D6FxQuoteChangedEventConsumer.TOPIC, "d6-event", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "d6-event", "PROCESSING", 1));

        consumer.onOutboxMessage(message);

        verify(deliveries).markSuccess(D6FxQuoteChangedEventConsumer.CONSUMER_GROUP, "d6-event", 1);
    }

    @Test
    void ignoresWrongAggregateOrPair() {
        consumer.onOutboxMessage(message("bad-aggregate", "admin.fx_quote_updated", "USER", "VND_USDT"));
        consumer.onOutboxMessage(message("bad-pair", "admin.fx_quote_updated", "FX_QUOTE_CONFIG", "USD_USDT"));
        verifyNoInteractions(deliveries);
    }

    private static EventOutboxMessage message(String id, String type, String aggregate, String aggregateId) {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(id);
        message.setEventType(type);
        message.setAggregateType(aggregate);
        message.setAggregateId(aggregateId);
        message.setPayload("{}");
        return message;
    }
}

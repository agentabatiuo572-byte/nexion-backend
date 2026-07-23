package ffdd.opsconsole.finance.application;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class D5WithdrawalLimitChangedConsumerTest {
    @Mock private EventConsumerDeliveryService deliveryService;
    private D5WithdrawalLimitChangedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new D5WithdrawalLimitChangedConsumer(deliveryService);
    }

    @Test
    void persistsARealDeliveryReceiptBeforeTheOutboxCanBePublished() {
        EventOutboxMessage message = message("evt-d5-1", "admin.withdraw_limit_changed", "WITHDRAWAL_PARAM");
        when(deliveryService.claim(
                message,
                D5WithdrawalLimitChangedConsumer.CONSUMER_GROUP,
                D5WithdrawalLimitChangedConsumer.TOPIC,
                "evt-d5-1",
                0)).thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "evt-d5-1", "PROCESSING", 1));

        consumer.onOutboxMessage(message);

        verify(deliveryService).markSuccess(D5WithdrawalLimitChangedConsumer.CONSUMER_GROUP, "evt-d5-1", 1);
    }

    @Test
    void ignoresUnrelatedEvents() {
        consumer.onOutboxMessage(message("evt-other", "admin.user_frozen", "USER"));
        verify(deliveryService, never()).claim(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    private EventOutboxMessage message(String eventId, String eventType, String aggregateType) {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(eventId);
        message.setEventType(eventType);
        message.setAggregateType(aggregateType);
        message.setAggregateId("withdrawal.daily_count_limit");
        return message;
    }
}

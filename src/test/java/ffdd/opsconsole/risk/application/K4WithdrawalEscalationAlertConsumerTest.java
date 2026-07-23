package ffdd.opsconsole.risk.application;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ffdd.opsconsole.risk.mapper.K4WithdrawalAlertMapper;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class K4WithdrawalEscalationAlertConsumerTest {

    @Test
    void createsOneDurableReceiptPerAuthorizedAdminAndDuplicateDeliveryIsIdempotent() {
        EventConsumerDeliveryService deliveries = mock(EventConsumerDeliveryService.class);
        K4WithdrawalAlertMapper mapper = mock(K4WithdrawalAlertMapper.class);
        K4WithdrawalEscalationAlertConsumer consumer =
                new K4WithdrawalEscalationAlertConsumer(deliveries, mapper);
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId("evt-k4-1");
        message.setEventType("risk.withdraw_escalated");
        message.setAggregateType("WITHDRAWAL");
        message.setAggregateId("WD-1");
        message.setPayload("{\"risk_score\":91,\"priority\":\"ESCALATED\"}");
        when(mapper.activeAlertRecipientIds()).thenReturn(List.of(11L, 12L));
        when(mapper.countReceipt("evt-k4-1", 11L)).thenReturn(1);
        when(mapper.countReceipt("evt-k4-1", 12L)).thenReturn(1);
        when(deliveries.claim(eq(message), anyString(), anyString(), eq("evt-k4-1"), eq(0)))
                .thenReturn(
                        new EventConsumerDeliveryService.ConsumerClaim(true, "evt-k4-1", "PROCESSING", 1),
                        new EventConsumerDeliveryService.ConsumerClaim(true, "evt-k4-1", "PROCESSING", 1),
                        new EventConsumerDeliveryService.ConsumerClaim(false, "evt-k4-1", "SUCCESS", 1),
                        new EventConsumerDeliveryService.ConsumerClaim(false, "evt-k4-1", "SUCCESS", 1));

        consumer.onOutboxMessage(message);
        consumer.onOutboxMessage(message);

        verify(mapper, times(1)).insertReceipt("evt-k4-1", 11L, "WD-1", message.getPayload());
        verify(mapper, times(1)).insertReceipt("evt-k4-1", 12L, "WD-1", message.getPayload());
        verify(deliveries, times(1)).markSuccess("k4-withdrawal-escalation-admin-11", "evt-k4-1", 1);
        verify(deliveries, times(1)).markSuccess("k4-withdrawal-escalation-admin-12", "evt-k4-1", 1);
    }

    @Test
    void failsClosedWhenNoActiveAuthorizedRecipientExists() {
        EventConsumerDeliveryService deliveries = mock(EventConsumerDeliveryService.class);
        K4WithdrawalAlertMapper mapper = mock(K4WithdrawalAlertMapper.class);
        K4WithdrawalEscalationAlertConsumer consumer =
                new K4WithdrawalEscalationAlertConsumer(deliveries, mapper);
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId("evt-k4-no-recipient");
        message.setEventType("risk.withdraw_escalated");
        message.setAggregateType("WITHDRAWAL");
        when(mapper.activeAlertRecipientIds()).thenReturn(List.of());

        assertThatThrownBy(() -> consumer.onOutboxMessage(message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("K4_WITHDRAWAL_ALERT_RECIPIENT_UNAVAILABLE");
    }
}

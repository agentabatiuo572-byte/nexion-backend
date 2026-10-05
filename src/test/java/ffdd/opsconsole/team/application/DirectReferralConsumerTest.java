package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService.ConsumerClaim;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DirectReferralConsumerTest {
    private final EventConsumerDeliveryService delivery = mock(EventConsumerDeliveryService.class);
    private final DirectReferralService service = mock(DirectReferralService.class);
    private final DirectReferralConsumer consumer = new DirectReferralConsumer(delivery, service, new ObjectMapper());

    @Test void checkoutUsesAuthoritativeAggregateInsteadOfPayloadAmountOrRecipient() {
        var message = message("checkout.completed", "ORDER", "paid-order", "{\"user_id\":2,\"order_no\":\"forged\",\"amount_usdt\":999999,\"sponsor_id\":999}");
        claim(message);
        when(service.settle("direct_purchase", "paid-order", 2L)).thenReturn(1);
        consumer.consume(message);
        verify(service).settle("direct_purchase", "paid-order", 2L);
        verify(delivery).markSuccess(DirectReferralConsumer.GROUP, "delivery-1", 1);
    }

    @Test void earningsUseReceiptIdentityAndRefundUsesOriginalOrderIdentity() {
        var earning = message("earnings.credited", "COMPUTE_TASK", "task-1", "{\"receipt_no\":\"receipt-1\",\"user_id\":2,\"amount_usdt\":999999}");
        claim(earning);
        consumer.consume(earning);
        verify(service).settle("direct_device_earning", "receipt-1", 2L);
        var refund = message("order.refunded", "E4_ORDER", "original-order", "{\"orderId\":\"forged-order\"}");
        claim(refund);
        consumer.consume(refund);
        verify(service).refund("original-order");
    }

    @ParameterizedTest
    @ValueSource(strings = {"device.purchase_completed", "commission.paid", "H8_REFERRAL_REWARD_SETTLED", "trial.redeemed"})
    void parallelOrRewardEventsCannotCascadeIntoAnotherDirectReward(String event) {
        consumer.consume(message(event, "ORDER", "order-1", "{\"user_id\":2}"));
        verifyNoInteractions(delivery, service);
    }

    @Test void financialFailureIsDurableAndPropagatesToTheOutboxRetry() {
        var message = message("checkout.completed", "ORDER", "order-1", "{\"user_id\":2}");
        claim(message);
        var failure = new IllegalStateException("second currency unavailable");
        when(service.settle("direct_purchase", "order-1", 2L)).thenThrow(failure);
        assertThatThrownBy(() -> consumer.consume(message)).isSameAs(failure);
        verify(delivery).markFailure(DirectReferralConsumer.GROUP, "delivery-1", 0, failure.getMessage());
        verify(delivery, never()).markSuccess(anyString(), anyString(), anyInt());
    }

    @Test void invalidEnvelopeAndMalformedJsonCannotBeAcknowledged() {
        var wrong = message("earnings.credited", "ORDER", "order-1", "{\"receipt_no\":\"r\",\"user_id\":2}");
        claim(wrong);
        assertThatThrownBy(() -> consumer.consume(wrong)).hasMessageContaining("ENVELOPE_INVALID");
        verify(delivery).markFailure(eq(DirectReferralConsumer.GROUP), eq("delivery-1"), eq(0), contains("ENVELOPE_INVALID"));
        verifyNoInteractions(service);
        reset(delivery);
        var malformed = message("checkout.completed", "ORDER", "order-1", "{");
        claim(malformed);
        assertThatThrownBy(() -> consumer.consume(malformed)).hasMessage("DIRECT_REFERRAL_PAYLOAD_INVALID");
        verify(delivery).markFailure(eq(DirectReferralConsumer.GROUP), eq("delivery-1"), eq(0), anyString());
        verify(delivery, never()).markSuccess(anyString(), anyString(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "SKIPPED"})
    void completedDeliveryDoesNotPayAgain(String status) {
        var message = message("checkout.completed", "ORDER", "order-1", "{\"user_id\":2}");
        when(delivery.claim(any(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new ConsumerClaim(false, "delivery-1", status, 1));
        consumer.consume(message);
        verifyNoInteractions(service);
        verify(delivery, never()).markSuccess(anyString(), anyString(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCESSING", "FAILED", "DEAD", "MISSING"})
    void unfinishedDeliveryRemainsAnErrorInsteadOfFalseSuccess(String status) {
        var message = message("checkout.completed", "ORDER", "order-1", "{\"user_id\":2}");
        when(delivery.claim(any(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new ConsumerClaim(false, "delivery-1", status, 1));
        assertThatThrownBy(() -> consumer.consume(message)).hasMessageContaining("DELIVERY_NOT_COMPLETE");
        verifyNoInteractions(service);
    }

    private void claim(EventOutboxMessage message) {
        when(delivery.claim(eq(message), eq(DirectReferralConsumer.GROUP), anyString(), eq("delivery-1"), eq(0)))
                .thenReturn(new ConsumerClaim(true, "delivery-1", "PROCESSING", 1));
    }

    private EventOutboxMessage message(String type, String aggregateType, String aggregateId, String payload) {
        var message = new EventOutboxMessage();
        message.setEventId("delivery-1");
        message.setEventType(type);
        message.setAggregateType(aggregateType);
        message.setAggregateId(aggregateId);
        message.setPayload(payload);
        return message;
    }
}

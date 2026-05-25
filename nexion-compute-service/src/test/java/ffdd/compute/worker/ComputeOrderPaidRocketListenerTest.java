package ffdd.compute.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.outbox.EventConsumerDeliveryService;
import ffdd.common.outbox.EventOutboxMessage;
import ffdd.common.rocketmq.RocketMqAclProperties;
import ffdd.compute.domain.UserDevice;
import ffdd.compute.dto.OrderPaidPayload;
import ffdd.compute.service.OrderPaidActivationService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

class ComputeOrderPaidRocketListenerTest {
    private final OrderPaidActivationService activationService = mock(OrderPaidActivationService.class);
    private final EventConsumerDeliveryService deliveryService = mock(EventConsumerDeliveryService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ComputeOrderPaidRocketListener listener = new ComputeOrderPaidRocketListener(
            activationService, objectMapper, deliveryService, "127.0.0.1:9876", "nexion-order-paid", "compute-group", 5,
            new RocketMqAclProperties());

    @Test
    void recordsSuccessfulDeliveryAfterActivatingDevices() throws Exception {
        EventOutboxMessage message = orderPaidMessage("EVT-1", "ORD-1");
        when(deliveryService.claim(any(EventOutboxMessage.class), eq("compute-group"), eq("nexion-order-paid"), eq("MSG-1"), eq(0)))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "EVT-1", "PROCESSING", 1));
        when(activationService.activate(any(OrderPaidPayload.class))).thenReturn(List.of(new UserDevice(), new UserDevice()));

        boolean retry = listener.consumeMessage(rocketMessage("MSG-1", 0, objectMapper.writeValueAsBytes(message)));

        assertThat(retry).isFalse();
        verify(deliveryService).markSuccess("compute-group", "EVT-1", 2);
    }

    @Test
    void duplicateDeliveryDoesNotActivateAgain() throws Exception {
        EventOutboxMessage message = orderPaidMessage("EVT-DUP", "ORD-DUP");
        when(deliveryService.claim(any(EventOutboxMessage.class), eq("compute-group"), eq("nexion-order-paid"), eq("MSG-DUP"), eq(1)))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(false, "EVT-DUP", "SUCCESS", 1));

        boolean retry = listener.consumeMessage(rocketMessage("MSG-DUP", 1, objectMapper.writeValueAsBytes(message)));

        assertThat(retry).isFalse();
        verify(activationService, never()).activate(any(OrderPaidPayload.class));
    }

    @Test
    void malformedMessageIsRecordedAndAckedWhenLocalDeliveryIsDead() throws Exception {
        when(deliveryService.claim(any(EventOutboxMessage.class), eq("compute-group"), eq("nexion-order-paid"), eq("MSG-BAD"), eq(4)))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "MSG-BAD", "PROCESSING", 5));
        when(deliveryService.markFailure(eq("compute-group"), eq("MSG-BAD"), eq(4), any(String.class)))
                .thenReturn(new EventConsumerDeliveryService.ConsumerFailure(true, "MSG-BAD", "DEAD", 5));

        boolean retry = listener.consumeMessage(rocketMessage("MSG-BAD", 4, "{bad-json".getBytes(StandardCharsets.UTF_8)));

        assertThat(retry).isFalse();
        verify(deliveryService).markFailure(eq("compute-group"), eq("MSG-BAD"), eq(4), any(String.class));
    }

    private EventOutboxMessage orderPaidMessage(String eventId, String orderNo) throws Exception {
        OrderPaidPayload payload = new OrderPaidPayload();
        payload.setOrderNo(orderNo);
        payload.setUserId(10001L);
        payload.setQuantity(2);

        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(eventId);
        message.setEventType("OrderPaid");
        message.setAggregateType("ORDER");
        message.setAggregateId(orderNo);
        message.setPayload(objectMapper.writeValueAsString(payload));
        return message;
    }

    private MessageExt rocketMessage(String msgId, int reconsumeTimes, byte[] body) {
        MessageExt message = new MessageExt();
        message.setMsgId(msgId);
        message.setReconsumeTimes(reconsumeTimes);
        message.setBody(body);
        return message;
    }
}

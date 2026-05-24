package ffdd.earnings.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.outbox.EventConsumerDeliveryService;
import ffdd.common.outbox.EventOutboxMessage;
import ffdd.earnings.domain.EarningEvent;
import ffdd.earnings.dto.ComputeTaskCompletedPayload;
import ffdd.earnings.dto.ReceiptSettleResponse;
import ffdd.earnings.service.ComputeTaskCompletedSettlementService;
import java.math.BigDecimal;
import java.util.List;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

class EarningsComputeTaskCompletedRocketListenerTest {
    private final ComputeTaskCompletedSettlementService settlementService = mock(ComputeTaskCompletedSettlementService.class);
    private final EventConsumerDeliveryService deliveryService = mock(EventConsumerDeliveryService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EarningsComputeTaskCompletedRocketListener listener = new EarningsComputeTaskCompletedRocketListener(
            settlementService, objectMapper, deliveryService, "127.0.0.1:9876",
            "nexion-compute-task-completed", "earnings-group", 5);

    @Test
    void recordsSuccessfulDeliveryAfterSettlingReceipt() throws Exception {
        EventOutboxMessage message = computeTaskCompletedMessage("EVT-2", "POC-1");
        when(deliveryService.claim(any(EventOutboxMessage.class), eq("earnings-group"), eq("nexion-compute-task-completed"), eq("MSG-2"), eq(0)))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "EVT-2", "PROCESSING", 1));
        when(settlementService.settle(any(ComputeTaskCompletedPayload.class)))
                .thenReturn(new ReceiptSettleResponse(List.of(new EarningEvent(), new EarningEvent()), null));

        boolean retry = listener.consumeMessage(rocketMessage("MSG-2", 0, objectMapper.writeValueAsBytes(message)));

        assertThat(retry).isFalse();
        verify(deliveryService).markSuccess("earnings-group", "EVT-2", 2);
    }

    @Test
    void retryableFailureIsRecordedAsFailed() throws Exception {
        EventOutboxMessage message = computeTaskCompletedMessage("EVT-FAIL", "POC-FAIL");
        when(deliveryService.claim(any(EventOutboxMessage.class), eq("earnings-group"), eq("nexion-compute-task-completed"), eq("MSG-FAIL"), eq(1)))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "EVT-FAIL", "PROCESSING", 2));
        when(settlementService.settle(any(ComputeTaskCompletedPayload.class))).thenThrow(new IllegalStateException("settle down"));
        when(deliveryService.markFailure(eq("earnings-group"), eq("EVT-FAIL"), eq(1), any(String.class)))
                .thenReturn(new EventConsumerDeliveryService.ConsumerFailure(false, "EVT-FAIL", "FAILED", 2));

        boolean retry = listener.consumeMessage(rocketMessage("MSG-FAIL", 1, objectMapper.writeValueAsBytes(message)));

        assertThat(retry).isTrue();
        verify(deliveryService).markFailure(eq("earnings-group"), eq("EVT-FAIL"), eq(1), any(String.class));
    }

    private EventOutboxMessage computeTaskCompletedMessage(String eventId, String receiptNo) throws Exception {
        ComputeTaskCompletedPayload payload = new ComputeTaskCompletedPayload();
        payload.setUserId(10001L);
        payload.setUserDeviceId(7L);
        payload.setReceiptNo(receiptNo);
        payload.setRewardUsdt(new BigDecimal("0.01"));

        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(eventId);
        message.setEventType("ComputeTaskCompleted");
        message.setAggregateType("COMPUTE_RECEIPT");
        message.setAggregateId(receiptNo);
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

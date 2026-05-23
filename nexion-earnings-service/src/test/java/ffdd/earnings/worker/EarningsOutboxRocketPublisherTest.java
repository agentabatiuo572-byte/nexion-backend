package ffdd.earnings.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.outbox.EventOutboxMessage;
import ffdd.common.outbox.EventOutboxService;
import ffdd.earnings.dto.OutboxPublishResult;
import java.util.List;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EarningsOutboxRocketPublisherTest {
    private final EventOutboxService outboxService = mock(EventOutboxService.class);
    private final DefaultMQProducer producer = mock(DefaultMQProducer.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void publishesOnlyEarningGeneratedOutboxMessages() throws Exception {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId("evt-earning-1");
        message.setAggregateType("EARNING_EVENT");
        message.setAggregateId("EARN-POC1-USDT");
        message.setEventType(EarningsOutboxRocketPublisher.EVENT_EARNING_GENERATED);
        message.setPayload("{\"eventNo\":\"EARN-POC1-USDT\"}");

        SendResult sendResult = mock(SendResult.class);
        when(sendResult.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        when(producer.send(any(Message.class))).thenReturn(sendResult);
        when(outboxService.listPendingByEventType(EarningsOutboxRocketPublisher.EVENT_EARNING_GENERATED, 25))
                .thenReturn(List.of(message));
        when(outboxService.markPublished("evt-earning-1")).thenReturn(true);

        EarningsOutboxRocketPublisher publisher =
                new EarningsOutboxRocketPublisher(outboxService, producer, objectMapper, "nexion-earning-generated", 50);

        OutboxPublishResult result = publisher.publishPending(25);

        assertThat(result.getScanned()).isEqualTo(1);
        assertThat(result.getPublished()).isEqualTo(1);
        assertThat(result.getEventIds()).containsExactly("evt-earning-1");
        verify(outboxService).listPendingByEventType(EarningsOutboxRocketPublisher.EVENT_EARNING_GENERATED, 25);
        verify(outboxService, never()).listPending(anyInt());

        ArgumentCaptor<Message> rocketMessage = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(rocketMessage.capture());
        assertThat(rocketMessage.getValue().getTopic()).isEqualTo("nexion-earning-generated");
        assertThat(rocketMessage.getValue().getTags()).isEqualTo(EarningsOutboxRocketPublisher.EVENT_EARNING_GENERATED);
        assertThat(rocketMessage.getValue().getKeys()).isEqualTo("evt-earning-1");
    }
}

package ffdd.compute.worker;

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
import ffdd.compute.dto.OutboxPublishResult;
import java.util.List;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ComputeOutboxRocketPublisherTest {
    private final EventOutboxService outboxService = mock(EventOutboxService.class);
    private final DefaultMQProducer producer = mock(DefaultMQProducer.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void publishesOnlyComputeTaskCompletedOutboxMessages() throws Exception {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId("evt-compute-1");
        message.setAggregateType("COMPUTE_RECEIPT");
        message.setAggregateId("POC-1");
        message.setEventType(ComputeOutboxRocketPublisher.EVENT_COMPUTE_TASK_COMPLETED);
        message.setPayload("{\"receiptNo\":\"POC-1\"}");

        SendResult sendResult = mock(SendResult.class);
        when(sendResult.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        when(producer.send(any(Message.class))).thenReturn(sendResult);
        when(outboxService.listPendingByEventType(
                ComputeOutboxRocketPublisher.EVENT_COMPUTE_TASK_COMPLETED, 25))
                .thenReturn(List.of(message));
        when(outboxService.markPublished("evt-compute-1")).thenReturn(true);

        ComputeOutboxRocketPublisher publisher =
                new ComputeOutboxRocketPublisher(outboxService, producer, objectMapper, "nexion-compute-task-completed", 50);

        OutboxPublishResult result = publisher.publishPending(25);

        assertThat(result.getScanned()).isEqualTo(1);
        assertThat(result.getPublished()).isEqualTo(1);
        assertThat(result.getEventIds()).containsExactly("evt-compute-1");
        verify(outboxService).listPendingByEventType(
                ComputeOutboxRocketPublisher.EVENT_COMPUTE_TASK_COMPLETED, 25);
        verify(outboxService, never()).listPending(anyInt());

        ArgumentCaptor<Message> rocketMessage = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(rocketMessage.capture());
        assertThat(rocketMessage.getValue().getTopic()).isEqualTo("nexion-compute-task-completed");
        assertThat(rocketMessage.getValue().getTags())
                .isEqualTo(ComputeOutboxRocketPublisher.EVENT_COMPUTE_TASK_COMPLETED);
        assertThat(rocketMessage.getValue().getKeys()).isEqualTo("evt-compute-1");
        EventOutboxMessage sent = objectMapper.readValue(rocketMessage.getValue().getBody(), EventOutboxMessage.class);
        assertThat(sent.getEventType()).isEqualTo(ComputeOutboxRocketPublisher.EVENT_COMPUTE_TASK_COMPLETED);
    }
}

package ffdd.compliance.worker;

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
import ffdd.compliance.dto.OutboxPublishResult;
import java.util.List;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ComplianceOutboxRocketPublisherTest {
    private final EventOutboxService outboxService = mock(EventOutboxService.class);
    private final DefaultMQProducer producer = mock(DefaultMQProducer.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void publishesOnlyRiskDecisionFinalizedOutboxMessages() throws Exception {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId("evt-risk-1");
        message.setAggregateType("RISK_DECISION");
        message.setAggregateId("RISK-WITHDRAWAL-WD-1");
        message.setEventType(ComplianceOutboxRocketPublisher.EVENT_RISK_DECISION_FINALIZED);
        message.setPayload("{\"decisionNo\":\"RISK-WITHDRAWAL-WD-1\"}");

        SendResult sendResult = mock(SendResult.class);
        when(sendResult.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        when(producer.send(any(Message.class))).thenReturn(sendResult);
        when(outboxService.listPendingByEventType(
                ComplianceOutboxRocketPublisher.EVENT_RISK_DECISION_FINALIZED, 25))
                .thenReturn(List.of(message));
        when(outboxService.markPublished("evt-risk-1")).thenReturn(true);

        ComplianceOutboxRocketPublisher publisher = new ComplianceOutboxRocketPublisher(
                outboxService, producer, objectMapper, "nexion-risk-decision-finalized", 50);

        OutboxPublishResult result = publisher.publishPending(25);

        assertThat(result.getScanned()).isEqualTo(1);
        assertThat(result.getPublished()).isEqualTo(1);
        assertThat(result.getEventIds()).containsExactly("evt-risk-1");
        verify(outboxService).listPendingByEventType(
                ComplianceOutboxRocketPublisher.EVENT_RISK_DECISION_FINALIZED, 25);
        verify(outboxService, never()).listPending(anyInt());

        ArgumentCaptor<Message> rocketMessage = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(rocketMessage.capture());
        assertThat(rocketMessage.getValue().getTopic()).isEqualTo("nexion-risk-decision-finalized");
        assertThat(rocketMessage.getValue().getTags())
                .isEqualTo(ComplianceOutboxRocketPublisher.EVENT_RISK_DECISION_FINALIZED);
        assertThat(rocketMessage.getValue().getKeys()).isEqualTo("evt-risk-1");
    }
}

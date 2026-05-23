package ffdd.earnings.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.outbox.EventOutboxMessage;
import ffdd.common.outbox.EventOutboxService;
import ffdd.earnings.dto.OutboxPublishResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "nexion.outbox.rocketmq", name = "enabled", havingValue = "true")
public class EarningsOutboxRocketPublisher {
    public static final String EVENT_EARNING_GENERATED = "EarningGenerated";
    private static final Logger log = LoggerFactory.getLogger(EarningsOutboxRocketPublisher.class);

    private final EventOutboxService outboxService;
    private final DefaultMQProducer producer;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final int batchSize;

    public EarningsOutboxRocketPublisher(
            EventOutboxService outboxService,
            DefaultMQProducer earningsOutboxRocketProducer,
            ObjectMapper objectMapper,
            @Value("${nexion.outbox.rocketmq.earning-generated-topic:nexion-earning-generated}") String topic,
            @Value("${nexion.outbox.rocketmq.publisher.batch-size:50}") int batchSize) {
        this.outboxService = outboxService;
        this.producer = earningsOutboxRocketProducer;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
    }

    @Scheduled(
            initialDelayString = "${nexion.outbox.rocketmq.publisher.initial-delay-ms:5000}",
            fixedDelayString = "${nexion.outbox.rocketmq.publisher.fixed-delay-ms:5000}")
    public void publishScheduled() {
        try {
            OutboxPublishResult result = publishPending(batchSize);
            if (result.getScanned() > 0 || result.getFailed() > 0) {
                log.info("Earnings outbox RocketMQ publisher scanned={}, published={}, skipped={}, failed={}",
                        result.getScanned(), result.getPublished(), result.getSkipped(), result.getFailed());
            }
        } catch (RuntimeException ex) {
            log.warn("Earnings outbox RocketMQ publisher failed: {}", ex.getMessage());
        }
    }

    public OutboxPublishResult publishPending(int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, 100));
        List<EventOutboxMessage> messages =
                outboxService.listPendingByEventType(EVENT_EARNING_GENERATED, normalizedLimit);
        OutboxPublishResult result = new OutboxPublishResult();
        result.setScanned(messages.size());

        for (EventOutboxMessage message : messages) {
            try {
                publish(message);
                if (outboxService.markPublished(message.getEventId())) {
                    result.setPublished(result.getPublished() + 1);
                    result.getEventIds().add(message.getEventId());
                } else {
                    result.setSkipped(result.getSkipped() + 1);
                }
            } catch (RuntimeException ex) {
                result.setFailed(result.getFailed() + 1);
                outboxService.markFailed(message.getEventId(), ex.getMessage());
            }
        }
        return result;
    }

    private void publish(EventOutboxMessage message) {
        try {
            byte[] body = objectMapper.writeValueAsString(message).getBytes(StandardCharsets.UTF_8);
            Message rocketMessage = new Message(topic, message.getEventType(), message.getEventId(), body);
            SendResult sendResult = producer.send(rocketMessage);
            if (sendResult.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("RocketMQ send status " + sendResult.getSendStatus());
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize RocketMQ outbox message", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("RocketMQ send failed: " + ex.getMessage(), ex);
        }
    }
}

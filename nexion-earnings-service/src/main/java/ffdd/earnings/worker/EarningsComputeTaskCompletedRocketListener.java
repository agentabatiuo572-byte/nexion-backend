package ffdd.earnings.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.outbox.EventOutboxMessage;
import ffdd.earnings.dto.ComputeTaskCompletedPayload;
import ffdd.earnings.dto.ReceiptSettleResponse;
import ffdd.earnings.service.ComputeTaskCompletedSettlementService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "nexion.outbox.rocketmq", name = "enabled", havingValue = "true")
public class EarningsComputeTaskCompletedRocketListener {
    private static final Logger log = LoggerFactory.getLogger(EarningsComputeTaskCompletedRocketListener.class);
    private static final String EVENT_COMPUTE_TASK_COMPLETED = "ComputeTaskCompleted";

    private final ComputeTaskCompletedSettlementService settlementService;
    private final ObjectMapper objectMapper;
    private final String nameServer;
    private final String topic;
    private final String consumerGroup;
    private final int maxReconsumeTimes;
    private DefaultMQPushConsumer consumer;

    public EarningsComputeTaskCompletedRocketListener(
            ComputeTaskCompletedSettlementService settlementService,
            ObjectMapper objectMapper,
            @Value("${nexion.outbox.rocketmq.name-server:127.0.0.1:9876}") String nameServer,
            @Value("${nexion.outbox.rocketmq.compute-task-completed-topic:nexion-compute-task-completed}")
                    String topic,
            @Value("${nexion.outbox.rocketmq.earnings-consumer-group:nexion-earnings-compute-task-completed}")
                    String consumerGroup,
            @Value("${nexion.outbox.rocketmq.consumer.max-retries:5}") int maxReconsumeTimes) {
        this.settlementService = settlementService;
        this.objectMapper = objectMapper;
        this.nameServer = nameServer;
        this.topic = topic;
        this.consumerGroup = consumerGroup;
        this.maxReconsumeTimes = Math.max(1, maxReconsumeTimes);
    }

    @PostConstruct
    public void start() throws Exception {
        consumer = new DefaultMQPushConsumer(consumerGroup);
        consumer.setNamesrvAddr(nameServer);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        consumer.setMaxReconsumeTimes(maxReconsumeTimes);
        consumer.subscribe(topic, EVENT_COMPUTE_TASK_COMPLETED);
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            boolean retry = false;
            for (MessageExt message : messages) {
                retry = consumeMessage(message) || retry;
            }
            return retry ? ConsumeConcurrentlyStatus.RECONSUME_LATER : ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        log.info("Earnings RocketMQ listener started topic={}, group={}, nameServer={}, maxReconsumeTimes={}",
                topic, consumerGroup, nameServer, maxReconsumeTimes);
    }

    @PreDestroy
    public void stop() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }

    private boolean consumeMessage(MessageExt rocketMessage) {
        try {
            EventOutboxMessage message = objectMapper.readValue(rocketMessage.getBody(), EventOutboxMessage.class);
            if (!EVENT_COMPUTE_TASK_COMPLETED.equals(message.getEventType())) {
                log.info("Earnings RocketMQ listener skipped eventType={}, msgId={}",
                        message.getEventType(), rocketMessage.getMsgId());
                return false;
            }
            ComputeTaskCompletedPayload payload = objectMapper.readValue(
                    message.getPayload(), ComputeTaskCompletedPayload.class);
            ReceiptSettleResponse response = settlementService.settle(payload);
            int events = response == null || response.getEvents() == null ? 0 : response.getEvents().size();
            log.info("Earnings RocketMQ listener settled receiptNo={}, events={}, eventId={}, msgId={}, reconsumeTimes={}",
                    payload.getReceiptNo(), events, message.getEventId(), rocketMessage.getMsgId(),
                    rocketMessage.getReconsumeTimes());
            return false;
        } catch (Exception ex) {
            if (rocketMessage.getReconsumeTimes() >= maxReconsumeTimes) {
                log.warn("Earnings RocketMQ listener dropped poison msgId={}, reconsumeTimes={}, error={}",
                        rocketMessage.getMsgId(), rocketMessage.getReconsumeTimes(), ex.getMessage());
                return false;
            }
            log.warn("Earnings RocketMQ listener will retry msgId={}, reconsumeTimes={}, error={}",
                    rocketMessage.getMsgId(), rocketMessage.getReconsumeTimes(), ex.getMessage());
            return true;
        }
    }
}

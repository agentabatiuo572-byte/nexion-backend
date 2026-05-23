package ffdd.notification.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.outbox.EventOutboxMessage;
import ffdd.notification.domain.Notification;
import ffdd.notification.dto.EarningGeneratedPayload;
import ffdd.notification.service.EarningGeneratedNotificationService;
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
public class NotificationEarningGeneratedRocketListener {
    private static final Logger log = LoggerFactory.getLogger(NotificationEarningGeneratedRocketListener.class);
    private static final String EVENT_EARNING_GENERATED = "EarningGenerated";

    private final EarningGeneratedNotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final String nameServer;
    private final String topic;
    private final String consumerGroup;
    private final int maxReconsumeTimes;
    private DefaultMQPushConsumer consumer;

    public NotificationEarningGeneratedRocketListener(
            EarningGeneratedNotificationService notificationService,
            ObjectMapper objectMapper,
            @Value("${nexion.outbox.rocketmq.name-server:127.0.0.1:9876}") String nameServer,
            @Value("${nexion.outbox.rocketmq.earning-generated-topic:nexion-earning-generated}") String topic,
            @Value("${nexion.outbox.rocketmq.notification-consumer-group:nexion-notification-earning-generated}")
                    String consumerGroup,
            @Value("${nexion.outbox.rocketmq.consumer.max-retries:5}") int maxReconsumeTimes) {
        this.notificationService = notificationService;
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
        consumer.subscribe(topic, EVENT_EARNING_GENERATED);
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            boolean retry = false;
            for (MessageExt message : messages) {
                retry = consumeMessage(message) || retry;
            }
            return retry ? ConsumeConcurrentlyStatus.RECONSUME_LATER : ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        log.info("Notification RocketMQ listener started topic={}, group={}, nameServer={}, maxReconsumeTimes={}",
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
            if (!EVENT_EARNING_GENERATED.equals(message.getEventType())) {
                log.info("Notification RocketMQ listener skipped eventType={}, msgId={}",
                        message.getEventType(), rocketMessage.getMsgId());
                return false;
            }
            EarningGeneratedPayload payload = objectMapper.readValue(message.getPayload(), EarningGeneratedPayload.class);
            Notification notification = notificationService.create(payload);
            log.info("Notification RocketMQ listener created earning notification id={}, eventNo={}, eventId={}, msgId={}, reconsumeTimes={}",
                    notification == null ? null : notification.getId(), payload.getEventNo(), message.getEventId(),
                    rocketMessage.getMsgId(), rocketMessage.getReconsumeTimes());
            return false;
        } catch (Exception ex) {
            if (rocketMessage.getReconsumeTimes() >= maxReconsumeTimes) {
                log.warn("Notification RocketMQ listener dropped poison msgId={}, reconsumeTimes={}, error={}",
                        rocketMessage.getMsgId(), rocketMessage.getReconsumeTimes(), ex.getMessage());
                return false;
            }
            log.warn("Notification RocketMQ listener will retry msgId={}, reconsumeTimes={}, error={}",
                    rocketMessage.getMsgId(), rocketMessage.getReconsumeTimes(), ex.getMessage());
            return true;
        }
    }
}

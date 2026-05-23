package ffdd.team.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.outbox.EventOutboxMessage;
import ffdd.team.service.TeamCommissionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "nexion.outbox.rocketmq", name = "enabled", havingValue = "true")
public class TeamOrderPaidRocketListener {
    private static final Logger log = LoggerFactory.getLogger(TeamOrderPaidRocketListener.class);

    private final TeamCommissionService commissionService;
    private final ObjectMapper objectMapper;
    private final String nameServer;
    private final String topic;
    private final String consumerGroup;
    private DefaultMQPushConsumer consumer;

    public TeamOrderPaidRocketListener(
            TeamCommissionService commissionService,
            ObjectMapper objectMapper,
            @Value("${nexion.outbox.rocketmq.name-server:127.0.0.1:9876}") String nameServer,
            @Value("${nexion.outbox.rocketmq.order-paid-topic:nexion-order-paid}") String topic,
            @Value("${nexion.outbox.rocketmq.consumer-group:nexion-team-order-paid}") String consumerGroup) {
        this.commissionService = commissionService;
        this.objectMapper = objectMapper;
        this.nameServer = nameServer;
        this.topic = topic;
        this.consumerGroup = consumerGroup;
    }

    @PostConstruct
    public void start() throws Exception {
        consumer = new DefaultMQPushConsumer(consumerGroup);
        consumer.setNamesrvAddr(nameServer);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        consumer.subscribe(topic, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            try {
                for (var rocketMessage : messages) {
                    EventOutboxMessage message = objectMapper.readValue(rocketMessage.getBody(), EventOutboxMessage.class);
                    int created = commissionService.consumeBrokerOrderPaid(message);
                    log.info("Team RocketMQ listener consumed msgId={}, eventId={}, eventType={}, created={}",
                            rocketMessage.getMsgId(), message.getEventId(), message.getEventType(), created);
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            } catch (RuntimeException ex) {
                log.warn("Team RocketMQ listener failed: {}", ex.getMessage());
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            } catch (Exception ex) {
                log.warn("Team RocketMQ listener failed: {}", ex.getMessage());
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        });
        consumer.start();
        log.info("Team RocketMQ listener started topic={}, group={}, nameServer={}", topic, consumerGroup, nameServer);
    }

    @PreDestroy
    public void stop() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}

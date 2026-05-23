package ffdd.team.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.outbox.EventOutboxMessage;
import ffdd.team.service.TeamCommissionService;
import ffdd.team.service.TeamCommissionService.BrokerConsumeDecision;
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
public class TeamOrderPaidRocketListener {
    private static final Logger log = LoggerFactory.getLogger(TeamOrderPaidRocketListener.class);

    private final TeamCommissionService commissionService;
    private final ObjectMapper objectMapper;
    private final String nameServer;
    private final String topic;
    private final String consumerGroup;
    private final int maxReconsumeTimes;
    private DefaultMQPushConsumer consumer;

    public TeamOrderPaidRocketListener(
            TeamCommissionService commissionService,
            ObjectMapper objectMapper,
            @Value("${nexion.outbox.rocketmq.name-server:127.0.0.1:9876}") String nameServer,
            @Value("${nexion.outbox.rocketmq.order-paid-topic:nexion-order-paid}") String topic,
            @Value("${nexion.outbox.rocketmq.consumer-group:nexion-team-order-paid}") String consumerGroup,
            @Value("${nexion.outbox.rocketmq.consumer.max-retries:5}") int maxReconsumeTimes) {
        this.commissionService = commissionService;
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
        consumer.subscribe(topic, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            try {
                boolean retry = false;
                for (var rocketMessage : messages) {
                    retry = consumeMessage(rocketMessage) || retry;
                }
                return retry ? ConsumeConcurrentlyStatus.RECONSUME_LATER : ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            } catch (RuntimeException ex) {
                log.warn("Team RocketMQ listener failed: {}", ex.getMessage());
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            } catch (Exception ex) {
                log.warn("Team RocketMQ listener failed: {}", ex.getMessage());
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        });
        consumer.start();
        log.info("Team RocketMQ listener started topic={}, group={}, nameServer={}, maxReconsumeTimes={}",
                topic, consumerGroup, nameServer, maxReconsumeTimes);
    }

    @PreDestroy
    public void stop() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }

    private boolean consumeMessage(MessageExt rocketMessage) throws Exception {
        try {
            EventOutboxMessage message = objectMapper.readValue(rocketMessage.getBody(), EventOutboxMessage.class);
            BrokerConsumeDecision decision = commissionService.consumeBrokerOrderPaid(
                    message,
                    consumerGroup,
                    topic,
                    rocketMessage.getMsgId(),
                    rocketMessage.getReconsumeTimes());
            logDecision(rocketMessage, message.getEventType(), decision);
            return decision.retry();
        } catch (Exception ex) {
            BrokerConsumeDecision decision = commissionService.recordBrokerOrderPaidFailure(
                    rocketMessage.getKeys(),
                    consumerGroup,
                    topic,
                    rocketMessage.getMsgId(),
                    rocketMessage.getReconsumeTimes(),
                    ex.getMessage());
            logDecision(rocketMessage, "UNKNOWN", decision);
            return decision.retry();
        }
    }

    private void logDecision(MessageExt rocketMessage, String eventType, BrokerConsumeDecision decision) {
        if (decision.dead()) {
            log.warn("Team RocketMQ listener dead-lettered msgId={}, eventId={}, eventType={}, attempts={}, status={}",
                    rocketMessage.getMsgId(), decision.eventId(), eventType, decision.attemptCount(), decision.status());
            return;
        }
        if (decision.retry()) {
            log.warn("Team RocketMQ listener will retry msgId={}, eventId={}, eventType={}, attempts={}, status={}",
                    rocketMessage.getMsgId(), decision.eventId(), eventType, decision.attemptCount(), decision.status());
            return;
        }
        log.info("Team RocketMQ listener consumed msgId={}, eventId={}, eventType={}, created={}, duplicate={}, attempts={}, status={}",
                rocketMessage.getMsgId(), decision.eventId(), eventType, decision.created(),
                decision.duplicate(), decision.attemptCount(), decision.status());
    }
}

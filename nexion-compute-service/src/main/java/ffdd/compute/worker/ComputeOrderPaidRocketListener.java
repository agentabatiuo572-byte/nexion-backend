package ffdd.compute.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.outbox.EventOutboxMessage;
import ffdd.compute.dto.OrderPaidPayload;
import ffdd.compute.service.OrderPaidActivationService;
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
public class ComputeOrderPaidRocketListener {
    private static final Logger log = LoggerFactory.getLogger(ComputeOrderPaidRocketListener.class);
    private static final String EVENT_ORDER_PAID = "OrderPaid";

    private final OrderPaidActivationService activationService;
    private final ObjectMapper objectMapper;
    private final String nameServer;
    private final String topic;
    private final String consumerGroup;
    private final int maxReconsumeTimes;
    private DefaultMQPushConsumer consumer;

    public ComputeOrderPaidRocketListener(
            OrderPaidActivationService activationService,
            ObjectMapper objectMapper,
            @Value("${nexion.outbox.rocketmq.name-server:127.0.0.1:9876}") String nameServer,
            @Value("${nexion.outbox.rocketmq.order-paid-topic:nexion-order-paid}") String topic,
            @Value("${nexion.outbox.rocketmq.compute-consumer-group:nexion-compute-order-paid}") String consumerGroup,
            @Value("${nexion.outbox.rocketmq.consumer.max-retries:5}") int maxReconsumeTimes) {
        this.activationService = activationService;
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
            boolean retry = false;
            for (MessageExt message : messages) {
                retry = consumeMessage(message) || retry;
            }
            return retry ? ConsumeConcurrentlyStatus.RECONSUME_LATER : ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        log.info("Compute RocketMQ listener started topic={}, group={}, nameServer={}, maxReconsumeTimes={}",
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
            if (!EVENT_ORDER_PAID.equals(message.getEventType())) {
                log.info("Compute RocketMQ listener skipped eventType={}, msgId={}",
                        message.getEventType(), rocketMessage.getMsgId());
                return false;
            }
            OrderPaidPayload payload = objectMapper.readValue(message.getPayload(), OrderPaidPayload.class);
            int activated = activationService.activate(payload).size();
            log.info("Compute RocketMQ listener activated orderNo={}, devices={}, eventId={}, msgId={}, reconsumeTimes={}",
                    payload.getOrderNo(), activated, message.getEventId(), rocketMessage.getMsgId(),
                    rocketMessage.getReconsumeTimes());
            return false;
        } catch (Exception ex) {
            if (rocketMessage.getReconsumeTimes() >= maxReconsumeTimes) {
                log.warn("Compute RocketMQ listener dropped poison msgId={}, reconsumeTimes={}, error={}",
                        rocketMessage.getMsgId(), rocketMessage.getReconsumeTimes(), ex.getMessage());
                return false;
            }
            log.warn("Compute RocketMQ listener will retry msgId={}, reconsumeTimes={}, error={}",
                    rocketMessage.getMsgId(), rocketMessage.getReconsumeTimes(), ex.getMessage());
            return true;
        }
    }
}

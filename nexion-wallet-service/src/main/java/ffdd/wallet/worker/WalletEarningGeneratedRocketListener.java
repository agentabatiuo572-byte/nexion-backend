package ffdd.wallet.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.outbox.EventConsumerDeliveryService;
import ffdd.common.outbox.EventOutboxMessage;
import ffdd.wallet.domain.WalletLedger;
import ffdd.wallet.dto.EarningGeneratedPayload;
import ffdd.wallet.service.EarningGeneratedPostingService;
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
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(prefix = "nexion.outbox.rocketmq", name = "enabled", havingValue = "true")
public class WalletEarningGeneratedRocketListener {
    private static final Logger log = LoggerFactory.getLogger(WalletEarningGeneratedRocketListener.class);
    private static final String EVENT_EARNING_GENERATED = "EarningGenerated";

    private final EarningGeneratedPostingService postingService;
    private final ObjectMapper objectMapper;
    private final EventConsumerDeliveryService deliveryService;
    private final String nameServer;
    private final String topic;
    private final String consumerGroup;
    private final int maxReconsumeTimes;
    private DefaultMQPushConsumer consumer;

    public WalletEarningGeneratedRocketListener(
            EarningGeneratedPostingService postingService,
            ObjectMapper objectMapper,
            EventConsumerDeliveryService deliveryService,
            @Value("${nexion.outbox.rocketmq.name-server:127.0.0.1:9876}") String nameServer,
            @Value("${nexion.outbox.rocketmq.earning-generated-topic:nexion-earning-generated}") String topic,
            @Value("${nexion.outbox.rocketmq.wallet-consumer-group:nexion-wallet-earning-generated}")
                    String consumerGroup,
            @Value("${nexion.outbox.rocketmq.consumer.max-retries:5}") int maxReconsumeTimes) {
        this.postingService = postingService;
        this.objectMapper = objectMapper;
        this.deliveryService = deliveryService;
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
        log.info("Wallet RocketMQ listener started topic={}, group={}, nameServer={}, maxReconsumeTimes={}",
                topic, consumerGroup, nameServer, maxReconsumeTimes);
    }

    @PreDestroy
    public void stop() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }

    boolean consumeMessage(MessageExt rocketMessage) {
        EventOutboxMessage message;
        try {
            message = objectMapper.readValue(rocketMessage.getBody(), EventOutboxMessage.class);
        } catch (Exception ex) {
            return recordMalformedFailure(rocketMessage, ex);
        }

        EventConsumerDeliveryService.ConsumerClaim claim;
        try {
            claim = deliveryService.claim(
                    message, consumerGroup, topic, rocketMessage.getMsgId(), rocketMessage.getReconsumeTimes());
        } catch (RuntimeException ex) {
            return fallbackRetry(rocketMessage, ex);
        }
        if (!claim.claimed()) {
            log.info("Wallet RocketMQ listener skipped duplicate eventId={}, status={}, attempts={}, msgId={}",
                    claim.eventId(), claim.status(), claim.attemptCount(), rocketMessage.getMsgId());
            return false;
        }
        if (!EVENT_EARNING_GENERATED.equals(message.getEventType())) {
            deliveryService.markSkipped(
                    consumerGroup, claim.eventId(), "Unsupported event type " + message.getEventType());
            log.info("Wallet RocketMQ listener skipped eventType={}, eventId={}, msgId={}",
                    message.getEventType(), claim.eventId(), rocketMessage.getMsgId());
            return false;
        }

        try {
            EarningGeneratedPayload payload = objectMapper.readValue(message.getPayload(), EarningGeneratedPayload.class);
            WalletLedger ledger = postingService.post(payload);
            deliveryService.markSuccess(consumerGroup, claim.eventId(), ledger == null ? 0 : 1);
            log.info("Wallet RocketMQ listener posted earning eventNo={}, ledgerId={}, eventId={}, msgId={}, reconsumeTimes={}",
                    payload.getEventNo(), ledger == null ? null : ledger.getId(), claim.eventId(),
                    rocketMessage.getMsgId(), rocketMessage.getReconsumeTimes());
            return false;
        } catch (Exception ex) {
            return markFailure(claim.eventId(), rocketMessage, ex);
        }
    }

    private boolean recordMalformedFailure(MessageExt rocketMessage, Exception ex) {
        try {
            EventConsumerDeliveryService.ConsumerClaim claim = deliveryService.claim(
                    unknownMessage(rocketMessage),
                    consumerGroup,
                    topic,
                    rocketMessage.getMsgId(),
                    rocketMessage.getReconsumeTimes());
            if (!claim.claimed()) {
                log.info("Wallet RocketMQ listener skipped malformed duplicate eventId={}, status={}, msgId={}",
                        claim.eventId(), claim.status(), rocketMessage.getMsgId());
                return false;
            }
            return markFailure(claim.eventId(), rocketMessage, ex);
        } catch (RuntimeException deliveryEx) {
            return fallbackRetry(rocketMessage, deliveryEx);
        }
    }

    private boolean markFailure(String eventId, MessageExt rocketMessage, Exception ex) {
        try {
            EventConsumerDeliveryService.ConsumerFailure failure = deliveryService.markFailure(
                    consumerGroup, eventId, rocketMessage.getReconsumeTimes(), errorMessage(ex));
            if (failure.dead()) {
                log.warn("Wallet RocketMQ listener moved poison delivery to DEAD eventId={}, msgId={}, attempts={}, reconsumeTimes={}, error={}",
                        failure.eventId(), rocketMessage.getMsgId(), failure.attemptCount(),
                        rocketMessage.getReconsumeTimes(), errorMessage(ex));
                return false;
            }
            log.warn("Wallet RocketMQ listener will retry eventId={}, msgId={}, attempts={}, reconsumeTimes={}, error={}",
                    failure.eventId(), rocketMessage.getMsgId(), failure.attemptCount(),
                    rocketMessage.getReconsumeTimes(), errorMessage(ex));
            return true;
        } catch (RuntimeException deliveryEx) {
            return fallbackRetry(rocketMessage, deliveryEx);
        }
    }

    private boolean fallbackRetry(MessageExt rocketMessage, RuntimeException ex) {
        boolean retry = rocketMessage.getReconsumeTimes() < maxReconsumeTimes;
        log.warn("Wallet RocketMQ listener delivery-state error msgId={}, reconsumeTimes={}, retry={}, error={}",
                rocketMessage.getMsgId(), rocketMessage.getReconsumeTimes(), retry, errorMessage(ex));
        return retry;
    }

    private EventOutboxMessage unknownMessage(MessageExt rocketMessage) {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(rocketMessage.getMsgId());
        message.setEventType("UNKNOWN");
        message.setAggregateType("ROCKETMQ");
        message.setAggregateId(rocketMessage.getMsgId());
        return message;
    }

    private String errorMessage(Exception ex) {
        return StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
    }
}

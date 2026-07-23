package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.mapper.QuestCanonicalEventBindingMapper;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Durable H3 intake for canonical facts emitted by trusted backend domains.
 * User HTTP requests never enter this boundary directly.
 */
@Component
@RequiredArgsConstructor
public class QuestCanonicalEventConsumer {
    static final String CONSUMER_GROUP = "h3-quest-completion";
    static final String TOPIC = "spring-local-h3-quest-completion";

    private final QuestCanonicalEventBindingMapper bindingMapper;
    private final EventConsumerDeliveryService deliveryService;
    private final QuestCanonicalEventProjector projector;

    @EventListener
    public void onOutboxMessage(EventOutboxMessage message) {
        if (message == null || !StringUtils.hasText(message.getEventType())
                || bindingMapper.countActiveBindings(message.getEventType()) <= 0) {
            return;
        }
        EventConsumerDeliveryService.ConsumerClaim claim = deliveryService.claim(
                message, CONSUMER_GROUP, TOPIC, message.getEventId(), 0);
        if (!claim.claimed()) {
            if (!"SUCCESS".equals(claim.status()) && !"SKIPPED".equals(claim.status())) {
                throw new IllegalStateException("QUEST_CANONICAL_DELIVERY_NOT_COMPLETE:" + claim.status());
            }
            return;
        }
        try {
            projector.project(message, claim.eventId());
        } catch (RuntimeException ex) {
            deliveryService.markFailure(CONSUMER_GROUP, claim.eventId(), 0, ex.getMessage());
            throw ex;
        }
    }
}

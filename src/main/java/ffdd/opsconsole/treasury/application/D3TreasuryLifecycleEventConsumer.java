package ffdd.opsconsole.treasury.application;

import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Durable receipt for D3 configuration and reserve-injection lifecycle events. */
@Component
@RequiredArgsConstructor
public class D3TreasuryLifecycleEventConsumer {
    static final String CONSUMER_GROUP = "d3-treasury-lifecycle-observer";
    static final String TOPIC = "spring-local-d3-treasury-lifecycle";
    private static final Set<String> EVENT_TYPES = Set.of(
            "admin.treasury_forecast_config_changed",
            "admin.treasury_reserve_injected");

    private final EventConsumerDeliveryService deliveryService;

    @EventListener
    public void onOutboxMessage(EventOutboxMessage message) {
        if (message == null
                || !EVENT_TYPES.contains(message.getEventType())
                || !Set.of("TREASURY_CONFIG", "TREASURY_RESERVE").contains(message.getAggregateType())) {
            return;
        }
        EventConsumerDeliveryService.ConsumerClaim claim = deliveryService.claim(
                message, CONSUMER_GROUP, TOPIC, message.getEventId(), 0);
        if (claim.claimed()) {
            try {
                deliveryService.markSuccess(CONSUMER_GROUP, claim.eventId(), 1);
            } catch (RuntimeException ex) {
                deliveryService.markFailure(CONSUMER_GROUP, claim.eventId(), 0, ex.getMessage());
                throw ex;
            }
            return;
        }
        if (!"SUCCESS".equals(claim.status()) && !"SKIPPED".equals(claim.status())) {
            throw new IllegalStateException("D3_TREASURY_LIFECYCLE_DELIVERY_NOT_COMPLETE:" + claim.status());
        }
    }
}

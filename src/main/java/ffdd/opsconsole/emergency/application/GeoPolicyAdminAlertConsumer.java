package ffdd.opsconsole.emergency.application;

import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Durable consumer receipt for J2 events exposed to the superadmin alert feed. */
@Component
@RequiredArgsConstructor
public class GeoPolicyAdminAlertConsumer {
    static final String CONSUMER_GROUP = "admin-geo-policy-alert";
    private static final String EVENT_TYPE = "ADMIN_KILLSWITCH_TOGGLED";

    private final EventConsumerDeliveryService deliveryService;

    @EventListener
    public void onOutboxMessage(EventOutboxMessage message) {
        if (message == null
                || !EVENT_TYPE.equals(message.getEventType())
                || !"KILL_SWITCH".equals(message.getAggregateType())
                || !"geo-block".equals(message.getAggregateId())) {
            return;
        }
        EventConsumerDeliveryService.ConsumerClaim claim = deliveryService.claim(
                message, CONSUMER_GROUP, "spring-local-admin-alert", message.getEventId(), 0);
        if (claim.claimed()) {
            try {
                deliveryService.markSuccess(CONSUMER_GROUP, claim.eventId(), 1);
            } catch (RuntimeException ex) {
                deliveryService.markFailure(CONSUMER_GROUP, claim.eventId(), 0, ex.getMessage());
                throw ex;
            }
            return;
        }
        // A duplicate SUCCESS is an idempotent replay. Every other state must fail
        // the synchronous event publication so the outbox is not falsely marked PUBLISHED.
        if (!"SUCCESS".equals(claim.status()) && !"SKIPPED".equals(claim.status())) {
            throw new IllegalStateException("GEO_POLICY_ALERT_DELIVERY_NOT_COMPLETE:" + claim.status());
        }
    }
}

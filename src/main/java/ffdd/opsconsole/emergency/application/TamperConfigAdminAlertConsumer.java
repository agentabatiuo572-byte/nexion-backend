package ffdd.opsconsole.emergency.application;

import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Durable receipt consumed by the superadmin alert feed after a J3 monitoring-config change. */
@Component
@RequiredArgsConstructor
public class TamperConfigAdminAlertConsumer {
    static final String CONSUMER_GROUP = "admin-j3-config-alert";
    private static final String EVENT_TYPE = "ADMIN_J3_TAMPER_CONFIG_CHANGED";
    private final EventConsumerDeliveryService deliveryService;

    @EventListener
    public void onOutboxMessage(EventOutboxMessage message) {
        if (message == null || !EVENT_TYPE.equals(message.getEventType())) {
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
        if (!"SUCCESS".equals(claim.status()) && !"SKIPPED".equals(claim.status())) {
            throw new IllegalStateException("J3_CONFIG_ALERT_DELIVERY_NOT_COMPLETE:" + claim.status());
        }
    }
}

package ffdd.opsconsole.user.application;

import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Durable receipt for C2/C5 account-security events consumed by the high-risk admin alert lane. */
@Component
@RequiredArgsConstructor
public class C2HighRiskAdminAlertConsumer {
    static final String CONSUMER_GROUP = "c2-high-risk-admin-alert";
    static final String TOPIC = "spring-local-c2-admin-alert";
    private static final Set<String> EVENT_TYPES = Set.of(
            "admin.user_frozen",
            "admin.user_unfrozen",
            "admin.user_impersonation_started",
            "admin.user_impersonation_ended",
            "admin.2fa_disabled",
            "admin.password_reset_requested",
            "admin.user_unlocked",
            "admin.session_revoked",
            "auth.login_locked",
            "auth.refresh_token_reuse_detected");

    private final EventConsumerDeliveryService deliveryService;

    @EventListener
    public void onOutboxMessage(EventOutboxMessage message) {
        if (message == null
                || !EVENT_TYPES.contains(message.getEventType())
                || !("USER".equals(message.getAggregateType())
                    || "USER_IMPERSONATION".equals(message.getAggregateType())
                    || "USER_SECURITY".equals(message.getAggregateType()))) {
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
            throw new IllegalStateException("C2_HIGH_RISK_ALERT_DELIVERY_NOT_COMPLETE:" + claim.status());
        }
    }
}

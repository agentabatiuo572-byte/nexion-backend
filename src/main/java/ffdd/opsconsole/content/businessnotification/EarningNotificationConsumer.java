package ffdd.opsconsole.content.businessnotification;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * B79: committed earnings.credited events create the per-receipt station message right away, from
 * this account's own positive credit rows. The business-notification scan stays as the backfill
 * path and both paths share the EARNING:receiptNo fact key, so a receipt never appears twice.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "nexion.business-notifications.enabled", havingValue = "true")
public class EarningNotificationConsumer {
    static final String GROUP = "earning-credited-notification";
    private static final String TOPIC = "spring-earning-notification";

    private final EventConsumerDeliveryService delivery;
    private final BusinessNotificationService notifications;
    private final ObjectMapper json;

    @EventListener
    public void consume(EventOutboxMessage message) {
        if (message == null || !"earnings.credited".equals(message.getEventType())
                || !"COMPUTE_TASK".equals(message.getAggregateType())) return;
        var payload = payload(message.getPayload());
        Long userId = userId(payload);
        String receiptNo = receiptNo(payload);
        if (receiptNo == null || userId == null) return;
        var claim = delivery.claim(message, GROUP, TOPIC, message.getEventId(), 0);
        if (!claim.claimed()) {
            if (!Set.of("SUCCESS", "SKIPPED").contains(claim.status())) {
                throw new IllegalStateException("EARNING_NOTIFICATION_DELIVERY_NOT_COMPLETE:" + claim.status());
            }
            return;
        }
        final int written;
        try {
            written = notifications.emitEarning(receiptNo, userId);
        } catch (RuntimeException ex) {
            // Follow the existing consumer failure rhythm so the dispatcher retries on its schedule.
            delivery.markFailure(GROUP, claim.eventId(), 0, ex.getMessage());
            throw ex;
        }
        delivery.markSuccess(GROUP, claim.eventId(), written);
    }

    private Long userId(com.fasterxml.jackson.databind.JsonNode payload) {
        var node = payload;
        return node != null && node.path("user_id").isIntegralNumber() && node.path("user_id").canConvertToLong()
                && node.path("user_id").longValue() > 0 ? node.path("user_id").longValue() : null;
    }

    private String receiptNo(com.fasterxml.jackson.databind.JsonNode payload) {
        var node = payload;
        if (node == null || !node.path("receipt_no").isTextual()) return null;
        String receipt = node.path("receipt_no").asText();
        return receipt.isBlank() || receipt.length() > 96 ? null : receipt;
    }

    private com.fasterxml.jackson.databind.JsonNode payload(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try {
            var node = json.readTree(payload);
            return node != null && node.isObject() ? node : null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return null;
        }
    }
}

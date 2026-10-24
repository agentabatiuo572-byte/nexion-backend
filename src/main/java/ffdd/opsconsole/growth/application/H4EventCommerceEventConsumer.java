package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import java.math.BigDecimal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Durable canonical payment events drive H4 supplements, with canonical facts rechecked in the engine. */
@Component
@RequiredArgsConstructor
public class H4EventCommerceEventConsumer {
    static final String GROUP = "h4-commerce-ticket-rewards";
    static final String TOPIC = "spring-local-h4-commerce";
    private final H4EventEngine engine;
    private final EventConsumerDeliveryService deliveries;
    private final ObjectMapper json;

    @EventListener
    public void onOutboxMessage(EventOutboxMessage message) {
        if (message == null || !Boolean.TRUE.equals(message.getServerAuthoritative())
                || !StringUtils.hasText(message.getAggregateId()) || !StringUtils.hasText(message.getEventId())) return;
        boolean purchase = "checkout.completed".equals(message.getEventType()) && "ORDER".equals(message.getAggregateType());
        boolean reinvest = "wallet.reinvest".equals(message.getEventType()) && "REPURCHASE_ORDER".equals(message.getAggregateType());
        if (!purchase && !reinvest) return;
        var claim = deliveries.claim(message, GROUP, TOPIC, message.getEventId(), 0);
        if (!claim.claimed()) {
            if (!"SUCCESS".equals(claim.status()) && !"SKIPPED".equals(claim.status()))
                throw new IllegalStateException("H4_EVENT_DELIVERY_PENDING");
            return;
        }
        try {
            Map<String, Object> payload = json.readValue(message.getPayload(), new TypeReference<>() { });
            long userId = new BigDecimal(String.valueOf(payload.get("user_id"))).longValueExact();
            if (userId <= 0) throw new IllegalArgumentException("H4_EVENT_USER_REQUIRED");
            if (purchase) engine.purchaseCompleted(userId, message.getAggregateId());
            else engine.reinvestCompleted(userId, message.getAggregateId());
            deliveries.markSuccess(GROUP, claim.eventId(), 1);
        } catch (RuntimeException ex) {
            deliveries.markFailure(GROUP, claim.eventId(), 0, ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            deliveries.markFailure(GROUP, claim.eventId(), 0, "H4_EVENT_PAYLOAD_INVALID");
            throw new IllegalArgumentException("H4_EVENT_PAYLOAD_INVALID", ex);
        }
    }
}

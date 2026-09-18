package ffdd.opsconsole.user.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import ffdd.opsconsole.user.mapper.C1AuditEvidenceMapper;
import java.util.Map;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Confirms existing A2 evidence for C1. L5 reads that original evidence, not short-lived outbox payloads. */
@Component
@RequiredArgsConstructor
public class C1AuditEvidenceConsumer {
    static final String CONSUMER_GROUP = "c1-audit-evidence";
    private static final Map<String, String> EVENTS = Map.of(
            "ADMIN_USER_PROFILE_VIEWED", "admin.user_profile_viewed",
            "ADMIN_USER_LIST_EXPORTED", "admin.user_list_exported");
    private final C1AuditEvidenceMapper evidence;
    private final EventConsumerDeliveryService deliveries;
    private final ObjectMapper json;

    @EventListener
    public void onOutboxMessage(EventOutboxMessage message) {
        if (message == null || message.getEventType() == null) return;
        // MySQL's event_type comparison may be case-insensitive. A malformed C1 alias
        // must fail here instead of falling through as an unhandled successful event.
        String family = message.getEventType().toUpperCase(Locale.ROOT);
        if (!EVENTS.containsKey(family)) return;
        require(EVENTS.containsKey(message.getEventType()), "C1_AUDIT_ENVELOPE_INVALID");
        boolean profile = "ADMIN_USER_PROFILE_VIEWED".equals(message.getEventType());
        require(EVENTS.get(message.getEventType()).equals(message.getEventName())
                && (profile ? "USER_PROFILE" : "USER_PROFILE_EXPORT").equals(message.getAggregateType())
                && Boolean.TRUE.equals(message.getServerAuthoritative())
                && Boolean.TRUE.equals(message.getAnalyticsEvent())
                && Boolean.TRUE.equals(message.getSchemaRegistered()), "C1_AUDIT_ENVELOPE_INVALID");
        JsonNode payload = payload(message.getPayload());
        require(text(payload, "event_id").equals(message.getEventId())
                && text(payload, "event_name").equals(message.getEventName())
                && payload.path("is_server_authoritative").isBoolean()
                && payload.path("is_server_authoritative").booleanValue(), "C1_AUDIT_ENVELOPE_INVALID");
        require(message.getAggregateId() != null && !message.getAggregateId().isBlank(), "C1_AUDIT_SOURCE_INVALID");
        int matches;
        if (profile) {
            long userId = integer(payload, "target_user_id");
            require(userId > 0, "C1_AUDIT_SOURCE_INVALID");
            JsonNode cards = payload.path("cards_viewed");
            require(cards.isArray() && !cards.isEmpty(), "C1_AUDIT_SOURCE_INVALID");
            cards.forEach(card -> require(card.isTextual() && !card.textValue().isBlank(), "C1_AUDIT_SOURCE_INVALID"));
            matches = evidence.countProfileEvidence(message.getEventId(), message.getAggregateId(), userId,
                    text(payload, "viewer_operator"), text(payload, "viewer_role"), cards.toString());
        } else {
            long rows = integer(payload, "row_count");
            String hash = text(payload, "filter_hash");
            require(rows >= 0 && hash.matches("[a-fA-F0-9]{64}"), "C1_AUDIT_SOURCE_INVALID");
            matches = evidence.countExportEvidence(message.getAggregateId(), text(payload, "exporter_operator"), hash, rows);
        }
        require(matches == 1, "C1_AUDIT_EVIDENCE_NOT_UNIQUE");
        var claim = deliveries.claim(message, CONSUMER_GROUP, "spring-local-c1-audit-evidence", message.getEventId(), 0);
        if (!claim.claimed()) {
            require("SUCCESS".equals(claim.status()), "C1_AUDIT_DELIVERY_NOT_COMPLETE");
            return;
        }
        try {
            deliveries.markSuccess(CONSUMER_GROUP, claim.eventId(), 1);
            var receipt = deliveries.getByEvent(CONSUMER_GROUP, claim.eventId());
            require(receipt != null && "SUCCESS".equals(receipt.getStatus()), "C1_AUDIT_RECEIPT_NOT_PERSISTED");
        } catch (RuntimeException ex) {
            deliveries.markFailure(CONSUMER_GROUP, claim.eventId(), 0, ex.getMessage());
            throw ex;
        }
    }

    private JsonNode payload(String raw) {
        try {
            JsonNode node = json.readTree(raw);
            require(node != null && node.isObject(), "C1_AUDIT_PAYLOAD_INVALID");
            return node;
        } catch (Exception ex) {
            throw new IllegalStateException("C1_AUDIT_PAYLOAD_INVALID");
        }
    }

    private static String text(JsonNode payload, String name) {
        JsonNode value = payload.path(name);
        require(value.isTextual() && !value.textValue().isBlank(), "C1_AUDIT_PAYLOAD_INVALID");
        return value.textValue();
    }

    private static long integer(JsonNode payload, String name) {
        JsonNode value = payload.path(name);
        require(value.isIntegralNumber() && value.canConvertToLong(), "C1_AUDIT_PAYLOAD_INVALID");
        return value.longValue();
    }

    private static void require(boolean valid, String code) {
        if (!valid) throw new IllegalStateException(code);
    }
}

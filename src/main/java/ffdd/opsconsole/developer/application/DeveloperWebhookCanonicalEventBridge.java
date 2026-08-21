package ffdd.opsconsole.developer.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.developer.mapper.AppDeveloperAccessMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Bridges already-committed canonical outbox facts into the developer webhook durable delivery outbox.
 *
 * <p>This listener intentionally does not publish a second business fact. The canonical event id remains the
 * idempotency key and the delivery service creates one row per matching endpoint. As a result, a dispatch retry
 * after a successful bridge is harmless and a failed bridge is retried by the existing canonical outbox worker.
 */
@Component
public class DeveloperWebhookCanonicalEventBridge {
    private static final Set<String> SUPPORTED_CANONICAL_EVENTS = Set.of(
            "checkout.started", "checkout.completed", "order.created", "order.updated", "order.paid",
            "order.completed", "order.refunded", "task.completed", "task.failed", "compute.job.completed",
            "compute.job.failed", "earnings.credited", "earnings.updated", "billing.invoice.created",
            "market.curve_advanced", "market.updated", "account.updated");
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i)(password|secret|token|authorization|cookie|credential|private[_-]?key|api[_-]?key|email|phone|mobile|address)");

    private final DeveloperWebhookDeliveryService delivery;
    private final AppDeveloperAccessMapper access;
    private final Environment environment;
    private final ObjectMapper objectMapper;

    public DeveloperWebhookCanonicalEventBridge(DeveloperWebhookDeliveryService delivery,
                                                AppDeveloperAccessMapper access,
                                                Environment environment,
                                                ObjectMapper objectMapper) {
        this.delivery = delivery;
        this.access = access;
        this.environment = environment;
        this.objectMapper = objectMapper;
    }

    /** Receives only messages emitted by the existing canonical outbox dispatch scheduler. */
    @EventListener
    public void onOutboxMessage(EventOutboxMessage message) {
        bridge(message);
    }

    /** Returns the number of durable endpoint deliveries created for this canonical fact. */
    public int bridge(EventOutboxMessage message) {
        if (message == null || message.getEventId() == null || message.getEventId().isBlank()) return 0;
        String eventType = mapEvent(message.getEventType(), message.getAggregateType())
                .or(() -> mapEvent(message.getEventName(), message.getAggregateType()))
                .orElse(null);
        if (eventType == null) return 0;

        JsonNode raw = parse(message.getPayload());
        if (raw == null || !raw.isObject()) return 0;
        Long userId = longValue(raw.get("user_id"));
        if (userId == null) userId = longValue(raw.get("userId"));
        if (userId == null || userId <= 0) return 0;

        DeveloperAccountGuard.Scope current;
        try {
            current = new DeveloperAccountGuard(access, environment).scope(userId, false);
        } catch (RuntimeException ex) {
            // A deleted/inactive user or an unsupported profile is not a webhook delivery failure. There is no
            // valid endpoint scope for this event, so skip it without exposing the canonical outbox to retries.
            return 0;
        }
        if (!scopeMatches(raw, current)) return 0;

        String payload = sanitize(raw);
        return delivery.enqueue(userId, current.sourceEnvironment(), current.runId(), message.getEventId(), eventType,
                payload, null);
    }

    static Optional<String> mapEvent(String eventType, String aggregateType) {
        String event = normalize(eventType);
        if (!SUPPORTED_CANONICAL_EVENTS.contains(event)) return Optional.empty();
        return switch (event) {
            case "order.completed", "checkout.completed" -> Optional.of("order.completed");
            case "checkout.started", "order.created", "order.updated", "order.paid", "order.refunded" ->
                    Optional.of("order.updated");
            case "task.completed", "compute.job.completed" -> Optional.of("compute.job.completed");
            case "task.failed", "compute.job.failed" -> Optional.of("compute.job.failed");
            case "earnings.credited", "earnings.updated" -> Optional.of("earnings.updated");
            case "billing.invoice.created" -> Optional.of("billing.invoice.created");
            case "market.curve_advanced", "market.updated" -> Optional.of("market.updated");
            case "account.updated" -> Optional.of("account.updated");
            default -> Optional.empty();
        };
    }

    public static List<String> supportedCanonicalEventTypes() {
        return new ArrayList<>(SUPPORTED_CANONICAL_EVENTS);
    }

    private boolean scopeMatches(JsonNode payload, DeveloperAccountGuard.Scope current) {
        String suppliedEnvironment = text(payload, "source_environment", "sourceEnvironment");
        String suppliedRunId = text(payload, "run_id", "runId");
        if ("SANDBOX".equals(current.sourceEnvironment())
                && (suppliedEnvironment == null || suppliedRunId == null)) return false;
        if (suppliedEnvironment != null && !current.sourceEnvironment().equalsIgnoreCase(suppliedEnvironment)) return false;
        if (suppliedRunId != null && !current.runId().equals(suppliedRunId)) return false;
        return true;
    }

    private String sanitize(JsonNode raw) {
        JsonNode copy = raw.deepCopy();
        sanitizeNode(copy);
        try {
            return objectMapper.writeValueAsString(copy);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private void sanitizeNode(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> remove = new ArrayList<>();
            object.fieldNames().forEachRemaining(name -> {
                if (isSensitive(name)) remove.add(name);
                else sanitizeNode(object.get(name));
            });
            remove.forEach(object::remove);
        } else if (node instanceof ArrayNode array) {
            array.forEach(this::sanitizeNode);
        }
    }

    private boolean isSensitive(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (normalized.endsWith("_hash") || normalized.endsWith("hash")) return false;
        return SENSITIVE_FIELD.matcher(name == null ? "" : name).find();
    }

    private JsonNode parse(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try { return objectMapper.readTree(payload); }
        catch (Exception ex) { return null; }
    }

    private Long longValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isIntegralNumber()) return node.longValue();
        if (node.isTextual()) {
            try { return Long.parseLong(node.textValue()); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isTextual() && !value.textValue().isBlank()) return value.textValue().trim();
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

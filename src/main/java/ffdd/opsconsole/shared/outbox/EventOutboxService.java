package ffdd.opsconsole.shared.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EventOutboxService {
    private static final int MAX_LIMIT = 200;
    private static final int MAX_ERROR_LENGTH = 512;
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_PUBLISHED = "PUBLISHED";
    private static final String STATUS_DEAD = "DEAD";
    private static final Set<String> COMMON_FIELDS = Set.of(
            "event_id", "event_name", "ts", "user_id", "anon_id", "session_id", "phase",
            "account_age_months", "cohort", "ref", "source", "platform", "app_version", "locale",
            "is_server_authoritative", "schema_revision");

    private final EventOutboxMapper mapper;
    private final ObjectMapper objectMapper;
    private final OutboxProperties properties;

    public String publish(String aggregateType, String aggregateId, String eventType, Object payload) {
        return publishInternal(
                aggregateType, aggregateId, eventType, null, "SYSTEM", 0, currentCohort(), payload);
    }

    /**
     * Publishes a schema-governed, non-authoritative client analytics fact.
     * The caller must pass only server-sanitized pseudonyms and allowlisted fields.
     */
    public String publishClientAnalyticsEvent(String aggregateId, String eventType, Object payload) {
        return publishInternal(
                "APP_BEHAVIOR", aggregateId, eventType, null, "SYSTEM", 0, currentCohort(), payload);
    }

    public String publishUserEvent(
            String aggregateType,
            String aggregateId,
            String eventType,
            Long userId,
            String phase,
            Integer accountAgeMonths,
            String cohort,
            Object payload) {
        String normalizedPhase = phase == null ? "" : phase.trim().toUpperCase(Locale.ROOT);
        String normalizedCohort = cohort == null ? "" : cohort.trim();
        int cohortWeek = normalizedCohort.matches("^\\d{4}-W\\d{2}$")
                ? Integer.parseInt(normalizedCohort.substring(6))
                : 0;
        if (userId == null || userId <= 0
                || !normalizedPhase.matches("^P[1-6]$")
                || accountAgeMonths == null || accountAgeMonths < 0
                || cohortWeek < 1 || cohortWeek > 53) {
            throw validation("A4_USER_ATTRIBUTION_INVALID");
        }
        return publishInternal(
                aggregateType, aggregateId, eventType, userId, normalizedPhase,
                accountAgeMonths, normalizedCohort, payload);
    }

    private String publishInternal(
            String aggregateType,
            String aggregateId,
            String eventType,
            Long canonicalUserId,
            String phase,
            int accountAgeMonths,
            String cohort,
            Object payload) {
        String eventId = UUID.randomUUID().toString().replace("-", "");
        EventMetadata metadata = metadata(eventType);
        EventOutboxMapper.SchemaGateRow schema = metadata.analyticsEvent()
                ? mapper.findActiveSchema(metadata.eventName())
                : null;
        if (metadata.analyticsEvent() && schema == null) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "A4_SCHEMA_NOT_REGISTERED");
        }
        boolean serverAuthoritative = schema == null || schema.serverAuthoritative();
        int revision = schema == null ? 0 : schema.revision();
        String familyKey = schema == null ? metadata.familyKey() : schema.familyKey();
        ObjectNode payloadObject = payloadObject(payload);
        addCanonicalAliases(payloadObject);
        if (canonicalUserId != null) {
            payloadObject.remove("userId");
            payloadObject.remove("accountAgeMonths");
            payloadObject.put("user_id", canonicalUserId);
        }
        if (metadata.analyticsEvent()) {
            validateAnalyticsPayload(metadata.eventName(), payloadObject);
        }
        String payloadJson = toEnvelopeJson(
                eventId, metadata.eventName(), phase, accountAgeMonths, cohort,
                serverAuthoritative, revision, payloadObject);
        mapper.insertEvent(
                eventId, aggregateType, aggregateId, eventType,
                metadata.eventName(), familyKey, phase, accountAgeMonths, cohort,
                serverAuthoritative, schema == null ? null : revision,
                schema != null, metadata.analyticsEvent(), payloadJson);
        return eventId;
    }

    public List<EventOutboxMessage> listPending(int limit) {
        return mapper.listPending(normalizeLimit(limit));
    }

    public List<EventOutboxMessage> listPendingByEventType(String eventType, int limit) {
        return mapper.listPendingByEventType(eventType, normalizeLimit(limit));
    }

    public List<EventOutboxMessage> listByAggregate(String aggregateType, String aggregateId, int limit) {
        return mapper.listByAggregate(aggregateType, aggregateId, normalizeLimit(limit));
    }

    public List<EventOutboxMessage> listDead(int limit) {
        return mapper.listByStatus(STATUS_DEAD, normalizeLimit(limit));
    }

    public boolean markPublished(String eventId) {
        return mapper.markPublished(eventId, STATUS_PUBLISHED) > 0;
    }

    public boolean markFailed(String eventId, String errorMessage) {
        String clippedError = clip(errorMessage);
        return mapper.markFailed(eventId, clippedError, properties.maxRetries(), STATUS_DEAD, STATUS_FAILED, STATUS_PENDING) > 0;
    }

    private String toEnvelopeJson(
            String eventId,
            String eventName,
            String phase,
            int accountAgeMonths,
            String cohort,
            boolean serverAuthoritative,
            int schemaRevision,
            ObjectNode payload) {
        try {
            ObjectNode envelope = payload.deepCopy();
            envelope.put("event_id", eventId);
            envelope.put("event_name", eventName);
            envelope.put("ts", System.currentTimeMillis());
            if (!envelope.has("user_id")) envelope.putNull("user_id");
            if (!envelope.has("anon_id")) envelope.putNull("anon_id");
            if (!envelope.has("session_id")) envelope.putNull("session_id");
            envelope.put("phase", phase);
            envelope.put("account_age_months", accountAgeMonths);
            envelope.put("cohort", cohort);
            if (!envelope.has("ref")) envelope.putNull("ref");
            if (!envelope.has("source")) envelope.putNull("source");
            if (!envelope.hasNonNull("platform")) envelope.put("platform", "server");
            if (!envelope.hasNonNull("app_version")) envelope.put("app_version", "backend");
            if (!envelope.hasNonNull("locale")) envelope.put("locale", "und");
            envelope.put("is_server_authoritative", serverAuthoritative);
            if (schemaRevision > 0) envelope.put("schema_revision", schemaRevision);
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            throw new BizException("Failed to serialize outbox payload");
        }
    }

    private ObjectNode payloadObject(Object payload) {
        JsonNode converted = objectMapper.valueToTree(payload);
        if (converted != null && converted.isObject()) {
            return (ObjectNode) converted.deepCopy();
        }
        ObjectNode object = objectMapper.createObjectNode();
        object.set("value", converted == null ? objectMapper.nullNode() : converted);
        return object;
    }

    private void addCanonicalAliases(ObjectNode payload) {
        List<String> fields = new java.util.ArrayList<>();
        payload.fieldNames().forEachRemaining(fields::add);
        for (String field : fields) {
            String canonical = snakeCase(field);
            if (!canonical.equals(field) && !payload.has(canonical)) {
                payload.set(canonical, payload.get(field));
            }
        }
    }

    private void validateAnalyticsPayload(String eventName, ObjectNode payload) {
        List<EventOutboxMapper.SchemaPropertyGateRow> propertyRows = mapper.listActiveProperties(eventName);
        Map<String, EventOutboxMapper.SchemaPropertyGateRow> properties = new HashMap<>();
        for (EventOutboxMapper.SchemaPropertyGateRow row : propertyRows) {
            properties.put(row.propertyName(), row);
        }

        Set<String> checked = new HashSet<>();
        payload.fields().forEachRemaining(entry -> {
            String propertyName = snakeCase(entry.getKey());
            if (!checked.add(propertyName) || COMMON_FIELDS.contains(propertyName)) {
                return;
            }
            if (containsRawPii(propertyName)) {
                throw validation("A4_EVENT_PAYLOAD_PII_REJECTED");
            }
            EventOutboxMapper.SchemaPropertyGateRow schemaProperty = properties.get(propertyName);
            if (schemaProperty == null) {
                throw validation("A4_SCHEMA_PROPERTY_NOT_REGISTERED");
            }
            if (!matchesType(entry.getValue(), schemaProperty.propertyType())) {
                throw validation("A4_SCHEMA_PROPERTY_TYPE_MISMATCH");
            }
        });

        for (EventOutboxMapper.SchemaPropertyGateRow row : propertyRows) {
            if (!row.requiredField() || COMMON_FIELDS.contains(row.propertyName())) {
                continue;
            }
            JsonNode value = payload.get(row.propertyName());
            if (value == null || value.isNull()) {
                throw validation("A4_SCHEMA_REQUIRED_PROPERTY_MISSING");
            }
        }
    }

    private boolean matchesType(JsonNode value, String propertyType) {
        if (value == null || value.isNull()) {
            return true;
        }
        return switch (propertyType == null ? "" : propertyType.toLowerCase(Locale.ROOT)) {
            case "string", "enum" -> value.isTextual();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "timestamp" -> value.isTextual() || value.isIntegralNumber();
            case "id" -> value.isTextual() || value.isIntegralNumber();
            case "json" -> true;
            default -> false;
        };
    }

    private boolean containsRawPii(String propertyName) {
        boolean identifier = propertyName.contains("phone") || propertyName.contains("mobile")
                || propertyName.contains("address") || propertyName.contains("email");
        boolean pseudonymized = propertyName.endsWith("_hash") || propertyName.endsWith("_id");
        return identifier && !pseudonymized;
    }

    private BizException validation(String message) {
        return new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), message);
    }

    private EventMetadata metadata(String eventType) {
        String canonical = eventType == null ? "" : eventType.trim().toLowerCase(Locale.ROOT);
        if (canonical.matches("^[a-z][a-z0-9_]*\\.[a-z0-9]+(?:_[a-z0-9]+)*$")) {
            return analytics(canonical, "registry");
        }
        String normalized = eventType == null ? "UNKNOWN" : eventType.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            // Keep the stable delivery type consumed by H3 while governing the same
            // durable fact under A4's canonical learn-domain schema.
            case "LEARNING_COURSE_COMPLETED" -> analytics("learn.course_completed", "learn");
            case "ADMIN_J3_TAMPER_CONFIG_CHANGED" -> analytics("admin.tamper_config_changed", "phase_admin");
            case "ADMIN_KILLSWITCH_TOGGLED", "J1_KILLSWITCH_CHANGED" -> analytics("admin.killswitch_toggled", "phase_admin");
            case "J2_GEO_POLICY_CHANGED" -> analytics("admin.geo_policy_changed", "phase_admin");
            case "RISK_TAMPER_DETECTED" -> analytics("risk.tamper_detected", "risk");
            case "RISK_MULTI_ACCOUNT_FLAGGED" -> analytics("risk.multi_account_flagged", "risk");
            case "RISK_MULTI_ACCOUNT_INCIDENT_CREATED" -> analytics("risk.multi_account_incident_created", "risk");
            default -> {
                if (normalized.startsWith("ADMIN_")) {
                    yield analytics("admin." + snake(normalized.substring("ADMIN_".length())), "phase_admin");
                }
                if (normalized.startsWith("RISK_")) {
                    yield analytics("risk." + snake(normalized.substring("RISK_".length())), "risk");
                }
                if (normalized.startsWith("J1_") || normalized.startsWith("J2_")) {
                    yield analytics("admin." + snake(normalized.substring(3)), "phase_admin");
                }
                String domain = normalized.startsWith("JANUS_") ? "device" : "internal";
                yield new EventMetadata(domain + "." + snake(normalized), "internal", false);
            }
        };
    }

    private EventMetadata analytics(String eventName, String familyKey) {
        return new EventMetadata(eventName, familyKey, true);
    }

    private String snake(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private String snakeCase(String value) {
        return snake(value.replaceAll("([a-z0-9])([A-Z])", "$1_$2"));
    }

    private String currentCohort() {
        LocalDate today = LocalDate.now();
        WeekFields weekFields = WeekFields.ISO;
        return String.format(Locale.ROOT, "%d-W%02d",
                today.get(weekFields.weekBasedYear()), today.get(weekFields.weekOfWeekBasedYear()));
    }

    private record EventMetadata(String eventName, String familyKey, boolean analyticsEvent) {
    }

    private String clip(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "Unknown outbox delivery error";
        }
        return errorMessage.length() <= MAX_ERROR_LENGTH ? errorMessage : errorMessage.substring(0, MAX_ERROR_LENGTH);
    }

    private int normalizeLimit(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }
}

package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.application.QuestCompletionFactConsumer.QuestCompletionCommand;
import ffdd.opsconsole.growth.mapper.QuestCanonicalEventBindingMapper;
import ffdd.opsconsole.growth.mapper.QuestCanonicalEventBindingMapper.CanonicalQuestEventBinding;
import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper;
import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper.DayOneSnapshotBinding;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import ffdd.opsconsole.shared.outbox.EventOutboxDispatchScheduler;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Atomically completes all H3 missions bound to one canonical outbox fact. */
@Component
public class QuestCanonicalEventProjector {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    /** PC only permits these canonical identity slots.  Never scan every instance. */
    static final List<String> SNAPSHOT_USER_ID_FIELDS = List.of("user_id", "inviter_user_id");

    private final QuestCanonicalEventBindingMapper bindingMapper;
    private final DayOneInstanceMapper dayOneInstanceMapper;
    private final QuestCompletionFactConsumer factConsumer;
    private final EventConsumerDeliveryService deliveryService;
    private final ObjectMapper objectMapper;

    @Autowired
    public QuestCanonicalEventProjector(
            QuestCanonicalEventBindingMapper bindingMapper,
            DayOneInstanceMapper dayOneInstanceMapper,
            QuestCompletionFactConsumer factConsumer,
            EventConsumerDeliveryService deliveryService,
            ObjectMapper objectMapper) {
        this.bindingMapper = bindingMapper;
        this.dayOneInstanceMapper = dayOneInstanceMapper;
        this.factConsumer = factConsumer;
        this.deliveryService = deliveryService;
        this.objectMapper = objectMapper;
    }

    QuestCanonicalEventProjector(
            QuestCanonicalEventBindingMapper bindingMapper,
            QuestCompletionFactConsumer factConsumer,
            EventConsumerDeliveryService deliveryService,
            ObjectMapper objectMapper) {
        this(bindingMapper, null, factConsumer, deliveryService, objectMapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public void project(EventOutboxMessage message, String deliveryEventId) {
        if (message == null || !StringUtils.hasText(message.getEventId())
                || !StringUtils.hasText(message.getEventType())) {
            throw new IllegalArgumentException("QUEST_CANONICAL_EVENT_INVALID");
        }
        if (!Boolean.TRUE.equals(message.getServerAuthoritative())) {
            throw new IllegalArgumentException("QUEST_CANONICAL_EVENT_NOT_SERVER_AUTHORITATIVE");
        }
        if (message.getEventTs() == null) {
            throw new IllegalArgumentException("QUEST_CANONICAL_EVENT_TIMESTAMP_REQUIRED");
        }
        Map<String, Object> payload = readPayload(message.getPayload());
        List<CanonicalQuestEventBinding> bindings = bindingMapper.listActiveBindings(message.getEventType());
        if (bindings == null) bindings = List.of();
        List<DayOneSnapshotBinding> snapshotBindings = matchingSnapshotBindings(message, payload);
        boolean dayOnePageObservation = H3DayOnePageObservationContract.forEventType(message.getEventType()) != null
                || H3DayOneBusinessFactContract.forEventType(message.getEventType()) != null;
        List<CanonicalQuestEventBinding> effectiveBindings = dayOnePageObservation
                ? bindings.stream()
                        .filter(binding -> fixedDayOneRuleMatches(binding.producer(), message.getEventType(), binding.questCode(), binding.userIdField()))
                        .toList()
                : bindings;
        // A stale wrong-slot/quest binding is not a completion route. Keep the
        // source fact pending so a later exact PC binding can project it.
        if (effectiveBindings.isEmpty() && snapshotBindings.isEmpty()) {
            if (EventOutboxDispatchScheduler.H3_BINDING_WAIT_EVENT_TYPES.contains(message.getEventType())) {
                deliveryService.markPendingBinding(
                        QuestCanonicalEventConsumer.CONSUMER_GROUP, deliveryEventId);
            } else {
                deliveryService.markSuccess(
                        QuestCanonicalEventConsumer.CONSUMER_GROUP, deliveryEventId, 0);
            }
            return;
        }
        int processed = 0;
        Set<Long> snapshotUsers = new LinkedHashSet<>();
        for (DayOneSnapshotBinding binding : snapshotBindings) {
            validateSnapshotBinding(binding, message.getEventType());
            Long userId = positiveLong(payload.get(binding.userIdField()));
            if (userId == null || !userId.equals(binding.userId())) {
                continue;
            }
            if (!matchesFrozenRule(binding)) {
                throw new IllegalArgumentException("DAY_ONE_SNAPSHOT_BINDING_RULE_INVALID");
            }
            String factEventId = snapshotFactEventId(message.getEventId(), binding);
            factConsumer.consume(new QuestCompletionCommand(
                    binding.producer(), factEventId, userId, binding.questCode(), message.getEventTs(),
                    binding.sourceMissionId(), binding.instanceKey()));
            snapshotUsers.add(userId);
            processed += 1;
        }
        for (CanonicalQuestEventBinding binding : effectiveBindings) {
            validateBinding(binding, message.getEventType());
            Long userId = positiveLong(payload.get(binding.userIdField()));
            if (userId == null) {
                throw new IllegalArgumentException("QUEST_CANONICAL_USER_ID_REQUIRED");
            }
            // A frozen Day-One snapshot is the authoritative route for this user.
            // Never let a later PC edit re-map the old source event to a current
            // Day-One mission; non-Day-One bindings on the same event stay intact.
            if (snapshotUsers.contains(userId) && "DAY_ONE".equals(binding.missionType())) {
                continue;
            }
            String factEventId = message.getEventId() + ":" + binding.bindingCode();
            if (factEventId.length() > 96) {
                throw new IllegalArgumentException("QUEST_CANONICAL_FACT_EVENT_ID_TOO_LONG");
            }
            factConsumer.consume(new QuestCompletionCommand(
                    binding.producer(), factEventId, userId, binding.questCode(),
                    message.getEventTs()));
            processed += 1;
        }
        deliveryService.markSuccess(
                QuestCanonicalEventConsumer.CONSUMER_GROUP, deliveryEventId, processed);
    }

    private List<DayOneSnapshotBinding> matchingSnapshotBindings(
            EventOutboxMessage message, Map<String, Object> payload) {
        if (dayOneInstanceMapper == null || message.getEventTs() == null) return List.of();
        List<Long> userIds = snapshotCandidateUserIds(payload);
        if (userIds.isEmpty()) return List.of();
        List<DayOneSnapshotBinding> candidates = dayOneInstanceMapper.listInWindowSnapshotBindings(
                userIds, message.getEventType(), message.getEventTs());
        if (candidates == null || candidates.isEmpty()) return List.of();
        return candidates.stream().filter(binding -> {
            validateSnapshotBinding(binding, message.getEventType());
            Long userId = positiveLong(payload.get(binding.userIdField()));
            if (userId == null || !userId.equals(binding.userId())) return false;
            if (!matchesFrozenRule(binding)) {
                throw new IllegalArgumentException("DAY_ONE_SNAPSHOT_BINDING_RULE_INVALID");
            }
            return fixedDayOneRuleMatches(binding.producer(), binding.eventType(), binding.questCode(), binding.userIdField());
        }).toList();
    }

    static List<Long> snapshotCandidateUserIds(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return List.of();
        LinkedHashSet<Long> userIds = new LinkedHashSet<>();
        for (String field : SNAPSHOT_USER_ID_FIELDS) {
            Long userId = positiveLongStatic(payload.get(field));
            if (userId != null) userIds.add(userId);
        }
        return List.copyOf(userIds);
    }

    private Map<String, Object> readPayload(String payload) {
        try {
            return objectMapper.readValue(payload, MAP_TYPE);
        } catch (Exception ex) {
            throw new IllegalArgumentException("QUEST_CANONICAL_PAYLOAD_INVALID", ex);
        }
    }

    private boolean fixedDayOneRuleMatches(String producer, String eventType, String questCode, String userIdField) {
        var businessRule = H3DayOneBusinessFactContract.forEventType(eventType);
        if (businessRule != null) return businessRule.matches(producer, questCode, userIdField);
        if (H3DayOnePageObservationContract.forEventType(eventType) != null)
            return H3DayOnePageObservationContract.matches(producer, eventType, questCode, userIdField);
        return true;
    }

    private void validateBinding(CanonicalQuestEventBinding binding, String eventType) {
        if (binding == null
                || !StringUtils.hasText(binding.bindingCode())
                || !StringUtils.hasText(binding.producer())
                || !eventType.equals(binding.eventType())
                || !StringUtils.hasText(binding.questCode())
                || !StringUtils.hasText(binding.userIdField())) {
            throw new IllegalStateException("QUEST_CANONICAL_BINDING_INVALID");
        }
    }

    private void validateSnapshotBinding(DayOneSnapshotBinding binding, String eventType) {
        if (binding == null || binding.instanceId() == null || binding.instanceId() <= 0
                || binding.userId() == null || binding.userId() <= 0 || binding.sourceMissionId() == null
                || binding.sourceMissionId() <= 0 || !StringUtils.hasText(binding.instanceKey())
                || !StringUtils.hasText(binding.bindingCode()) || !StringUtils.hasText(binding.producer())
                || !eventType.equals(binding.eventType()) || !StringUtils.hasText(binding.questCode())
                || !StringUtils.hasText(binding.userIdField())) {
            throw new IllegalStateException("DAY_ONE_SNAPSHOT_BINDING_INVALID");
        }
    }

    private boolean matchesFrozenRule(DayOneSnapshotBinding binding) {
        try {
            Map<String, Object> rule = objectMapper.readValue(binding.ruleJson(), MAP_TYPE);
            return rule.size() == 4
                    && binding.bindingCode().equals(String.valueOf(rule.get("bindingCode")))
                    && binding.producer().equals(String.valueOf(rule.get("producer")))
                    && binding.eventType().equals(String.valueOf(rule.get("eventType")))
                    && binding.userIdField().equals(String.valueOf(rule.get("userIdField")));
        } catch (Exception ex) {
            return false;
        }
    }

    private String snapshotFactEventId(String eventId, DayOneSnapshotBinding binding) {
        String value = eventId + ":I" + binding.instanceId() + ":M" + binding.sourceMissionId();
        if (value.length() <= 96) return value;
        try {
            String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
            return "D1:" + hash;
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private Long positiveLong(Object value) {
        return positiveLongStatic(value);
    }

    private static Long positiveLongStatic(Object value) {
        try {
            long parsed = value instanceof Number number
                    ? number.longValue()
                    : Long.parseLong(value == null ? "" : String.valueOf(value).trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}

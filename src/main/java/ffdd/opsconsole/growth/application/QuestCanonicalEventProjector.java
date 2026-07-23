package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.application.QuestCompletionFactConsumer.QuestCompletionCommand;
import ffdd.opsconsole.growth.mapper.QuestCanonicalEventBindingMapper;
import ffdd.opsconsole.growth.mapper.QuestCanonicalEventBindingMapper.CanonicalQuestEventBinding;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Atomically completes all H3 missions bound to one canonical outbox fact. */
@Component
@RequiredArgsConstructor
public class QuestCanonicalEventProjector {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final QuestCanonicalEventBindingMapper bindingMapper;
    private final QuestCompletionFactConsumer factConsumer;
    private final EventConsumerDeliveryService deliveryService;
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public void project(EventOutboxMessage message, String deliveryEventId) {
        if (message == null || !StringUtils.hasText(message.getEventId())
                || !StringUtils.hasText(message.getEventType())) {
            throw new IllegalArgumentException("QUEST_CANONICAL_EVENT_INVALID");
        }
        if (!Boolean.TRUE.equals(message.getServerAuthoritative())) {
            throw new IllegalArgumentException("QUEST_CANONICAL_EVENT_NOT_SERVER_AUTHORITATIVE");
        }
        List<CanonicalQuestEventBinding> bindings = bindingMapper.listActiveBindings(message.getEventType());
        if (bindings == null || bindings.isEmpty()) {
            throw new IllegalStateException("QUEST_CANONICAL_BINDING_DISAPPEARED");
        }
        Map<String, Object> payload = readPayload(message.getPayload());
        int processed = 0;
        for (CanonicalQuestEventBinding binding : bindings) {
            validateBinding(binding, message.getEventType());
            Long userId = positiveLong(payload.get(binding.userIdField()));
            if (userId == null) {
                throw new IllegalArgumentException("QUEST_CANONICAL_USER_ID_REQUIRED");
            }
            String factEventId = message.getEventId() + ":" + binding.bindingCode();
            if (factEventId.length() > 96) {
                throw new IllegalArgumentException("QUEST_CANONICAL_FACT_EVENT_ID_TOO_LONG");
            }
            factConsumer.consume(new QuestCompletionCommand(
                    binding.producer(), factEventId, userId, binding.questCode()));
            processed += 1;
        }
        deliveryService.markSuccess(
                QuestCanonicalEventConsumer.CONSUMER_GROUP, deliveryEventId, processed);
    }

    private Map<String, Object> readPayload(String payload) {
        try {
            return objectMapper.readValue(payload, MAP_TYPE);
        } catch (Exception ex) {
            throw new IllegalArgumentException("QUEST_CANONICAL_PAYLOAD_INVALID", ex);
        }
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

    private Long positiveLong(Object value) {
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

package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.mapper.QuestCanonicalEventBindingMapper;
import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import ffdd.opsconsole.shared.outbox.EventOutboxDispatchScheduler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Durable H3 intake for canonical facts emitted by trusted backend domains.
 * User HTTP requests never enter this boundary directly.
 */
@Component
public class QuestCanonicalEventConsumer {
    static final String CONSUMER_GROUP = "h3-quest-completion";
    static final String TOPIC = "spring-local-h3-quest-completion";
    private static final ZoneId WEEK_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> WEEKLY_THRESHOLD_EVENTS = Set.of(
            "H3_STOREFRONT_THREE_PRODUCTS_VIEWED", "H3_GENESIS_SECONDARY_MARKET_VIEWED",
            "H3_COMPUTE_COMPLETED_50", "H3_REFERRAL_REGISTERED", "H3_EXCHANGE_COMPLETED");
    private static final String EXPIRED_WEEK_REASON = "H3_WEEKLY_FACT_OUTSIDE_CURRENT_WEEK";

    private final QuestCanonicalEventBindingMapper bindingMapper;
    private final DayOneInstanceMapper dayOneInstanceMapper;
    private final EventConsumerDeliveryService deliveryService;
    private final QuestCanonicalEventProjector projector;
    private final ObjectMapper objectMapper;

    @Autowired
    public QuestCanonicalEventConsumer(
            QuestCanonicalEventBindingMapper bindingMapper,
            DayOneInstanceMapper dayOneInstanceMapper,
            EventConsumerDeliveryService deliveryService,
            QuestCanonicalEventProjector projector,
            ObjectMapper objectMapper) {
        this.bindingMapper = bindingMapper;
        this.dayOneInstanceMapper = dayOneInstanceMapper;
        this.deliveryService = deliveryService;
        this.projector = projector;
        this.objectMapper = objectMapper;
    }

    QuestCanonicalEventConsumer(
            QuestCanonicalEventBindingMapper bindingMapper,
            EventConsumerDeliveryService deliveryService,
            QuestCanonicalEventProjector projector) {
        this(bindingMapper, null, deliveryService, projector, null);
    }

    @EventListener
    public void onOutboxMessage(EventOutboxMessage message) {
        if (message == null || !StringUtils.hasText(message.getEventType())) {
            return;
        }
        String eventType = message.getEventType();
        // The current product window never backfills expired weeks. A late
        // authoritative threshold fact must terminate once, before it can wait
        // for a binding or repeatedly fail the current-week instance check.
        if (WEEKLY_THRESHOLD_EVENTS.contains(eventType)
                && Boolean.TRUE.equals(message.getServerAuthoritative())
                && message.getEventTs() != null
                && !sameIsoWeek(message.getEventTs(), LocalDateTime.now(WEEK_ZONE))) {
            EventConsumerDeliveryService.ConsumerClaim expired = deliveryService.claim(
                    message, CONSUMER_GROUP, TOPIC, message.getEventId(), 0);
            boolean claimed = expired.claimed();
            if (!claimed && "PENDING_BINDING".equals(expired.status())) {
                claimed = deliveryService.resumePendingBinding(CONSUMER_GROUP, expired.eventId());
            }
            if (claimed) {
                deliveryService.markSkipped(CONSUMER_GROUP, expired.eventId(), EXPIRED_WEEK_REASON);
            } else if (!"SUCCESS".equals(expired.status()) && !"SKIPPED".equals(expired.status())) {
                throw new IllegalStateException("QUEST_CANONICAL_DELIVERY_NOT_COMPLETE:" + expired.status());
            }
            return;
        }
        boolean activeBinding = bindingMapper.countActiveBindings(eventType) > 0;
        boolean frozenDayOneBinding = false;
        if (dayOneInstanceMapper != null && objectMapper != null && message.getEventTs() != null) {
            List<Long> userIds = snapshotCandidateUserIds(message.getPayload());
            var bindings = userIds.isEmpty() ? List.of()
                    : dayOneInstanceMapper.listInWindowSnapshotBindings(userIds, eventType, message.getEventTs());
            frozenDayOneBinding = bindings != null && !bindings.isEmpty();
        }
        if (!activeBinding && !frozenDayOneBinding) {
            if (!EventOutboxDispatchScheduler.H3_BINDING_WAIT_EVENT_TYPES.contains(eventType)) {
                return;
            }
            EventConsumerDeliveryService.ConsumerClaim claim = deliveryService.claimPendingBinding(
                    message, CONSUMER_GROUP, TOPIC, message.getEventId(), 0);
            if (!claim.claimed()
                    && !"PENDING_BINDING".equals(claim.status())
                    && !"SUCCESS".equals(claim.status())
                    && !"SKIPPED".equals(claim.status())) {
                throw new IllegalStateException("QUEST_CANONICAL_DELIVERY_NOT_COMPLETE:" + claim.status());
            }
            return;
        }

        EventConsumerDeliveryService.ConsumerClaim claim = deliveryService.claim(
                message, CONSUMER_GROUP, TOPIC, message.getEventId(), 0);
        boolean claimed = claim.claimed();
        if (!claimed && "PENDING_BINDING".equals(claim.status())) {
            claimed = deliveryService.resumePendingBinding(CONSUMER_GROUP, claim.eventId());
        }
        if (!claimed) {
            if (!"SUCCESS".equals(claim.status()) && !"SKIPPED".equals(claim.status())) {
                throw new IllegalStateException("QUEST_CANONICAL_DELIVERY_NOT_COMPLETE:" + claim.status());
            }
            return;
        }
        try {
            projector.project(message, claim.eventId());
        } catch (RuntimeException ex) {
            deliveryService.markFailure(CONSUMER_GROUP, claim.eventId(), 0, ex.getMessage());
            throw ex;
        }
    }

    static boolean sameIsoWeek(LocalDateTime source, LocalDateTime current) {
        WeekFields iso = WeekFields.ISO;
        return source.toLocalDate().get(iso.weekBasedYear()) == current.toLocalDate().get(iso.weekBasedYear())
                && source.toLocalDate().get(iso.weekOfWeekBasedYear())
                == current.toLocalDate().get(iso.weekOfWeekBasedYear());
    }

    private List<Long> snapshotCandidateUserIds(String rawPayload) {
        try {
            Map<String, Object> payload = objectMapper.readValue(rawPayload, new TypeReference<>() { });
            return QuestCanonicalEventProjector.snapshotCandidateUserIds(payload);
        } catch (Exception ex) {
            return List.of();
        }
    }
}

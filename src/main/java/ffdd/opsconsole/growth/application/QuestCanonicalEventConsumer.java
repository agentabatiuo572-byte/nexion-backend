package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.shared.config.VietnamBusinessPeriod;
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
    private final WeeklyQuestSnapshotService weeklySnapshots;

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

    /** Legacy direct construction; Spring uses the complete constructor below. */
    public QuestCanonicalEventConsumer(
            QuestCanonicalEventBindingMapper bindingMapper,
            DayOneInstanceMapper dayOneInstanceMapper,
            EventConsumerDeliveryService deliveryService,
            QuestCanonicalEventProjector projector,
            ObjectMapper objectMapper) {
        this(bindingMapper, dayOneInstanceMapper, deliveryService, projector, objectMapper, null);
    }

    @Autowired
    public QuestCanonicalEventConsumer(
            QuestCanonicalEventBindingMapper bindingMapper,
            DayOneInstanceMapper dayOneInstanceMapper,
            EventConsumerDeliveryService deliveryService,
            QuestCanonicalEventProjector projector,
            ObjectMapper objectMapper,
            WeeklyQuestSnapshotService weeklySnapshots) {
        this.bindingMapper = bindingMapper;
        this.dayOneInstanceMapper = dayOneInstanceMapper;
        this.deliveryService = deliveryService;
        this.projector = projector;
        this.objectMapper = objectMapper;
        this.weeklySnapshots = weeklySnapshots;
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
        Map<String,Object> payload = readPayload(message.getPayload());
        List<Long> users = QuestCanonicalEventProjector.weeklyCandidateUserIds(eventType,payload);
        String expectedWeek = QuestCanonicalEventProjector.expectedWeeklyInstance(eventType,payload);
        boolean authoritative = Boolean.TRUE.equals(message.getServerAuthoritative());
        Long weeklyUser = QuestCanonicalEventProjector.positiveLongStatic(payload.get("user_id"));
        boolean missingRegionalKey=weeklySnapshots!=null && authoritative && WEEKLY_THRESHOLD_EVENTS.contains(eventType)
                && weeklyUser!=null && expectedWeek==null && weeklySnapshots.current(weeklyUser).calendarVersion()!=null;
        boolean expiredWeek = missingRegionalKey || (!authoritative || !WEEKLY_THRESHOLD_EVENTS.contains(eventType) ? false : weeklySnapshots == null
                ? message.getEventTs() != null && !sameIsoWeek(message.getEventTs(), LocalDateTime.now(WEEK_ZONE))
                : weeklyUser != null && message.getEventTs() != null
                    && !weeklySnapshots.publishedFor(weeklyUser, message.getEventTs(), expectedWeek));
        if (WEEKLY_THRESHOLD_EVENTS.contains(eventType)
                && Boolean.TRUE.equals(message.getServerAuthoritative())
                && message.getEventTs() != null
                && expiredWeek) {
            EventConsumerDeliveryService.ConsumerClaim expired = deliveryService.claim(
                    message, CONSUMER_GROUP, TOPIC, message.getEventId(), 0);
            boolean claimed = expired.claimed();
            if (!claimed && "PENDING_BINDING".equals(expired.status())) {
                claimed = deliveryService.resumePendingBinding(CONSUMER_GROUP, expired.eventId());
            }
            if (claimed) {
                deliveryService.markSkipped(CONSUMER_GROUP, expired.eventId(), missingRegionalKey?"H3_WEEKLY_INSTANCE_REQUIRED":EXPIRED_WEEK_REASON);
            } else if (!"SUCCESS".equals(expired.status()) && !"SKIPPED".equals(expired.status())) {
                throw new IllegalStateException("QUEST_CANONICAL_DELIVERY_NOT_COMPLETE:" + expired.status());
            }
            return;
        }
        boolean activeBinding = bindingMapper.countActiveBindings(eventType) > 0;
        boolean weeklyBound=weeklySnapshots != null && authoritative && weeklySnapshots.hasEventBindings(eventType);
        List<Long> activeWeeklyUsers=weeklyBound?weeklySnapshots.lockAccountsForProjection(users):List.of();
        boolean frozenWeeklyBinding = weeklyBound
                && activeWeeklyUsers.stream().anyMatch(user -> !weeklySnapshots.bindings(
                        user,eventType,message.getEventTs(),expectedWeek).isEmpty());
        boolean publishedWeeklyWindow = weeklySnapshots != null && Boolean.TRUE.equals(message.getServerAuthoritative())
                && WEEKLY_THRESHOLD_EVENTS.contains(eventType) && weeklyUser != null
                && weeklySnapshots.publishedFor(weeklyUser,message.getEventTs(),expectedWeek);
        boolean frozenDayOneBinding = false;
        if (dayOneInstanceMapper != null && objectMapper != null && message.getEventTs() != null) {
            List<Long> userIds = snapshotCandidateUserIds(message.getPayload());
            var bindings = userIds.isEmpty() ? List.of()
                    : dayOneInstanceMapper.listInWindowSnapshotBindings(userIds, eventType, message.getEventTs());
            frozenDayOneBinding = bindings != null && !bindings.isEmpty();
        }
        if (!activeBinding && !frozenDayOneBinding && !frozenWeeklyBinding && !publishedWeeklyWindow) {
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
        return VietnamBusinessPeriod.weekKey(source).equals(VietnamBusinessPeriod.weekKey(current));
    }

    private List<Long> snapshotCandidateUserIds(String rawPayload) {
        return QuestCanonicalEventProjector.snapshotCandidateUserIds(readPayload(rawPayload));
    }

    private Map<String,Object> readPayload(String rawPayload) {
        if (objectMapper == null) return Map.of();
        try {
            Map<String,Object> payload=objectMapper.readValue(rawPayload,new TypeReference<>() { });
            return payload==null?Map.of():payload;
        } catch (Exception ex) {
            return Map.of();
        }
    }
}

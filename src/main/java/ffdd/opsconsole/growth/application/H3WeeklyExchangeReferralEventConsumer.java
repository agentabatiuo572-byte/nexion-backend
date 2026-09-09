package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.mapper.H3WeeklyExchangeReferralMapper;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Narrows two completed server facts into H3 SYSTEM events only after re-reading
 * the canonical exchange or referral relationship. Raw finance/referral events
 * are deliberately never H3 bindable.
 */
@Component
@RequiredArgsConstructor
public class H3WeeklyExchangeReferralEventConsumer {
    static final String CONSUMER_GROUP = "h3-weekly-exchange-referral-evaluator";
    static final String TOPIC = "spring-local-h3-weekly-exchange-referral";
    static final String EXCHANGE_EVENT = "H3_EXCHANGE_COMPLETED";
    static final String REFERRAL_EVENT = "H3_REFERRAL_REGISTERED";
    private static final String SOURCE = "H3_WEEKLY_EXCHANGE_REFERRAL";
    private static final String SKIPPED_OUTSIDE_CURRENT_ROLLOUT_WEEK =
            "H3_WEEKLY_EXCHANGE_REFERRAL_OUTSIDE_CURRENT_ROLLOUT_WEEK";
    private static final ZoneId WEEK_ZONE = ZoneId.of("Asia/Shanghai");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final H3WeeklyExchangeReferralMapper mapper;
    private final EventConsumerDeliveryService deliveryService;
    private final EventOutboxService outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(rollbackFor = Exception.class)
    @EventListener
    public void onOutboxMessage(EventOutboxMessage message) {
        SourceKind source = sourceKind(message);
        if (source == null) return;
        EventConsumerDeliveryService.ConsumerClaim claim = deliveryService.claim(
                message, CONSUMER_GROUP, TOPIC, message.getEventId(), 0);
        if (!claim.claimed()) {
            if (!"SUCCESS".equals(claim.status()) && !"SKIPPED".equals(claim.status())) {
                throw new IllegalStateException("H3_WEEKLY_EXCHANGE_REFERRAL_DELIVERY_NOT_COMPLETE:" + claim.status());
            }
            return;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(message.getPayload(), MAP_TYPE);
            VerifiedFact fact = switch (source) {
                case EXCHANGE -> verifiedExchange(message, payload);
                case REFERRAL -> verifiedReferral(message, payload);
            };
            LocalDateTime rolloutEffectiveAt = mapper.rolloutEffectiveAt();
            if (rolloutEffectiveAt == null) {
                throw new IllegalStateException("H3_WEEKLY_EXCHANGE_REFERRAL_ROLLOUT_UNAVAILABLE");
            }
            if (fact.occurredAt().isBefore(rolloutEffectiveAt)
                    || !weeklyInstance(fact.occurredAt().atZone(WEEK_ZONE).toInstant())
                    .equals(weeklyInstance(clock.instant()))) {
                deliveryService.markSkipped(CONSUMER_GROUP, claim.eventId(), SKIPPED_OUTSIDE_CURRENT_ROLLOUT_WEEK);
                return;
            }
            H3WeeklyExchangeReferralMapper.UserAttribution attribution = mapper.userAttribution(fact.ownerUserId());
            Attribution checkedAttribution = Attribution.from(attribution);
            outbox.publishUserEventAt(
                    SOURCE,
                    source.name() + ":" + claim.eventId(),
                    fact.derivedEventType(),
                    fact.ownerUserId(),
                    checkedAttribution.phase(),
                    checkedAttribution.accountAgeMonths(),
                    checkedAttribution.cohort(), fact.occurredAt(),
                    Map.of(
                            "source", SOURCE,
                            "sourceEventId", claim.eventId(),
                            "subject", fact.subject(),
                            "sourceOccurredAt", fact.occurredAt().toString()));
            deliveryService.markSuccess(CONSUMER_GROUP, claim.eventId(), 1);
        } catch (RuntimeException ex) {
            deliveryService.markFailure(CONSUMER_GROUP, claim.eventId(), 0, ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            deliveryService.markFailure(CONSUMER_GROUP, claim.eventId(), 0,
                    "H3_WEEKLY_EXCHANGE_REFERRAL_PAYLOAD_INVALID");
            throw new IllegalArgumentException("H3_WEEKLY_EXCHANGE_REFERRAL_PAYLOAD_INVALID", ex);
        }
    }

    private VerifiedFact verifiedExchange(EventOutboxMessage message, Map<String, Object> payload) {
        Long userId = positiveLong(payload.get("user_id"));
        String exchangeNo = requiredAggregateId(message, "H3_EXCHANGE_COMPLETION_EXCHANGE_REQUIRED");
        if (userId == null) throw new IllegalArgumentException("H3_EXCHANGE_COMPLETION_USER_REQUIRED");
        H3WeeklyExchangeReferralMapper.VerifiedExchange verified = mapper.verifiedCompletedExchange(userId, exchangeNo);
        if (verified == null || !userId.equals(verified.userId()) || !exchangeNo.equals(verified.exchangeNo())
                || verified.completedAt() == null) {
            throw new IllegalArgumentException("H3_EXCHANGE_COMPLETION_NOT_VERIFIED");
        }
        return new VerifiedFact(verified.userId(), exchangeNo, verified.completedAt(), EXCHANGE_EVENT);
    }

    private VerifiedFact verifiedReferral(EventOutboxMessage message, Map<String, Object> payload) {
        Long memberUserId = positiveLong(payload.get("userId"));
        Long sponsorUserId = positiveLong(payload.get("sponsorUserId"));
        if (memberUserId == null || sponsorUserId == null
                || !String.valueOf(memberUserId).equals(requiredAggregateId(message, "H3_REFERRAL_REGISTRATION_MEMBER_REQUIRED"))) {
            throw new IllegalArgumentException("H3_REFERRAL_REGISTRATION_RELATION_REQUIRED");
        }
        H3WeeklyExchangeReferralMapper.VerifiedReferral verified =
                mapper.verifiedReferralRegistration(memberUserId, sponsorUserId);
        if (verified == null || !sponsorUserId.equals(verified.ownerUserId())
                || !memberUserId.equals(verified.memberUserId()) || verified.registeredAt() == null) {
            throw new IllegalArgumentException("H3_REFERRAL_REGISTRATION_NOT_VERIFIED");
        }
        return new VerifiedFact(verified.ownerUserId(), String.valueOf(memberUserId), verified.registeredAt(), REFERRAL_EVENT);
    }

    private SourceKind sourceKind(EventOutboxMessage message) {
        if (message == null || !Boolean.TRUE.equals(message.getServerAuthoritative())
                || !StringUtils.hasText(message.getEventId()) || !StringUtils.hasText(message.getPayload())
                || message.getEventTs() == null || !StringUtils.hasText(message.getAggregateId())) {
            return null;
        }
        if ("exchange.swapped".equals(message.getEventType())
                && "EXCHANGE_ORDER".equals(message.getAggregateType())) {
            return SourceKind.EXCHANGE;
        }
        if ("referral.bound".equals(message.getEventType())
                && "USER_REFERRAL".equals(message.getAggregateType())) {
            return SourceKind.REFERRAL;
        }
        return null;
    }

    private static String requiredAggregateId(EventOutboxMessage message, String error) {
        String aggregateId = message == null ? "" : message.getAggregateId();
        if (!StringUtils.hasText(aggregateId)) throw new IllegalArgumentException(error);
        return aggregateId.trim();
    }

    private static Long positiveLong(Object value) {
        try {
            long parsed = value instanceof Number number
                    ? number.longValue()
                    : Long.parseLong(value == null ? "" : String.valueOf(value).trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static String weeklyInstance(Instant observedAt) {
        LocalDate date = observedAt.atZone(WEEK_ZONE).toLocalDate();
        return String.format(Locale.ROOT, "WEEK:%d-W%02d",
                date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    private enum SourceKind { EXCHANGE, REFERRAL }

    private record VerifiedFact(Long ownerUserId, String subject, LocalDateTime occurredAt, String derivedEventType) { }

    private record Attribution(String phase, int accountAgeMonths, String cohort) {
        private static Attribution from(H3WeeklyExchangeReferralMapper.UserAttribution value) {
            if (value == null || !StringUtils.hasText(value.phase()) || !StringUtils.hasText(value.cohort())
                    || value.accountAgeMonths() == null) {
                throw new IllegalStateException("H3_WEEKLY_EXCHANGE_REFERRAL_ATTRIBUTION_INVALID");
            }
            String phase = value.phase().trim().toUpperCase(Locale.ROOT);
            String cohort = value.cohort().trim();
            if (!phase.matches("P[1-6]") || value.accountAgeMonths() < 0
                    || !cohort.matches("\\d{4}-W\\d{2}")) {
                throw new IllegalStateException("H3_WEEKLY_EXCHANGE_REFERRAL_ATTRIBUTION_INVALID");
            }
            return new Attribution(phase, value.accountAgeMonths(), cohort);
        }
    }
}

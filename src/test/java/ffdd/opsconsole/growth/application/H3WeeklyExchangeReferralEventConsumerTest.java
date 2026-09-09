package ffdd.opsconsole.growth.application;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.mapper.H3WeeklyExchangeReferralMapper;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class H3WeeklyExchangeReferralEventConsumerTest {
    private static final LocalDateTime ROLLOUT = LocalDateTime.of(2026, 9, 7, 7, 0);
    private static final LocalDateTime CURRENT_WEEK_FACT = LocalDateTime.of(2026, 9, 7, 8, 0);

    private final H3WeeklyExchangeReferralMapper mapper = org.mockito.Mockito.mock(H3WeeklyExchangeReferralMapper.class);
    private final EventConsumerDeliveryService deliveries = org.mockito.Mockito.mock(EventConsumerDeliveryService.class);
    private final EventOutboxService outbox = org.mockito.Mockito.mock(EventOutboxService.class);
    private final H3WeeklyExchangeReferralEventConsumer consumer = new H3WeeklyExchangeReferralEventConsumer(
            mapper, deliveries, outbox, new ObjectMapper(),
            Clock.fixed(Instant.parse("2026-09-07T01:00:00Z"), ZoneOffset.UTC));

    @Test
    void completedCanonicalExchangeEmitsOneBoundedSystemFactForTheOrderOwner() {
        EventOutboxMessage message = exchange("EX-SOURCE-1", "EX-900", 42L);
        claim(message);
        when(mapper.verifiedCompletedExchange(42L, "EX-900"))
                .thenReturn(new H3WeeklyExchangeReferralMapper.VerifiedExchange(42L, "EX-900", CURRENT_WEEK_FACT));
        readyAttribution(42L);

        consumer.onOutboxMessage(message);

        verify(outbox).publishUserEventAt(
                eq("H3_WEEKLY_EXCHANGE_REFERRAL"), eq("EXCHANGE:EX-SOURCE-1"), eq("H3_EXCHANGE_COMPLETED"), eq(42L),
                eq("P2"), eq(3), eq("2026-W20"), eq(CURRENT_WEEK_FACT),
                argThat(payload -> sourcePayload(payload, "EX-SOURCE-1", "EX-900")));
        verify(deliveries).markSuccess(H3WeeklyExchangeReferralEventConsumer.CONSUMER_GROUP, "EX-SOURCE-1", 1);
    }

    @Test
    void verifiedReferralCompletesForSponsorNeverForTheNewMember() {
        EventOutboxMessage message = referral("REF-SOURCE-1", 91L, 42L);
        claim(message);
        when(mapper.verifiedReferralRegistration(91L, 42L))
                .thenReturn(new H3WeeklyExchangeReferralMapper.VerifiedReferral(42L, 91L, CURRENT_WEEK_FACT));
        readyAttribution(42L);

        consumer.onOutboxMessage(message);

        verify(outbox).publishUserEventAt(
                eq("H3_WEEKLY_EXCHANGE_REFERRAL"), eq("REFERRAL:REF-SOURCE-1"), eq("H3_REFERRAL_REGISTERED"), eq(42L),
                eq("P2"), eq(3), eq("2026-W20"), eq(CURRENT_WEEK_FACT),
                argThat(payload -> sourcePayload(payload, "REF-SOURCE-1", "91")));
        verify(outbox, never()).publishUserEventAt(
                "H3_WEEKLY_EXCHANGE_REFERRAL", "REFERRAL:REF-SOURCE-1", "H3_REFERRAL_REGISTERED", 91L,
                "P2", 3, "2026-W20", CURRENT_WEEK_FACT, Map.of());
    }

    @Test
    void preBoundaryExchangeKeepsItsCanonicalOccurrenceForLaterOutboxDispatch() {
        EventOutboxMessage message = exchange("EX-SOURCE-SUNDAY", "EX-SUNDAY", 42L);
        H3WeeklyExchangeReferralEventConsumer sundayConsumer = new H3WeeklyExchangeReferralEventConsumer(
                mapper, deliveries, outbox, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-06T15:59:59Z"), ZoneOffset.UTC));
        LocalDateTime sundayOccurrence = LocalDateTime.of(2026, 9, 6, 23, 59, 59);
        claim(message);
        when(mapper.verifiedCompletedExchange(42L, "EX-SUNDAY"))
                .thenReturn(new H3WeeklyExchangeReferralMapper.VerifiedExchange(42L, "EX-SUNDAY", sundayOccurrence));
        when(mapper.rolloutEffectiveAt()).thenReturn(LocalDateTime.of(2026, 9, 6, 20, 0));
        readyAttribution(42L);

        sundayConsumer.onOutboxMessage(message);

        verify(outbox).publishUserEventAt(
                eq("H3_WEEKLY_EXCHANGE_REFERRAL"), eq("EXCHANGE:EX-SOURCE-SUNDAY"),
                eq("H3_EXCHANGE_COMPLETED"), eq(42L), eq("P2"), eq(3), eq("2026-W20"),
                eq(sundayOccurrence), argThat(payload -> sourcePayload(payload, "EX-SOURCE-SUNDAY", "EX-SUNDAY")));
    }

    @Test
    void untrustedOrWrongAggregateNeverClaimsOrEmits() {
        EventOutboxMessage untrusted = exchange("EX-SOURCE-2", "EX-901", 42L);
        untrusted.setServerAuthoritative(false);
        consumer.onOutboxMessage(untrusted);

        EventOutboxMessage wrongAggregate = exchange("EX-SOURCE-3", "EX-902", 42L);
        wrongAggregate.setAggregateType("WALLET");
        consumer.onOutboxMessage(wrongAggregate);

        verify(deliveries, never()).claim(untrusted, H3WeeklyExchangeReferralEventConsumer.CONSUMER_GROUP,
                H3WeeklyExchangeReferralEventConsumer.TOPIC, "EX-SOURCE-2", 0);
        verify(deliveries, never()).claim(wrongAggregate, H3WeeklyExchangeReferralEventConsumer.CONSUMER_GROUP,
                H3WeeklyExchangeReferralEventConsumer.TOPIC, "EX-SOURCE-3", 0);
        verify(outbox, never()).publishUserEventAt(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void canonicalOwnershipOrCompletedStateMismatchFailsClosed() {
        EventOutboxMessage message = exchange("EX-SOURCE-4", "EX-903", 42L);
        claim(message);
        when(mapper.verifiedCompletedExchange(42L, "EX-903")).thenReturn(null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.onOutboxMessage(message))
                .hasMessage("H3_EXCHANGE_COMPLETION_NOT_VERIFIED");

        verify(outbox, never()).publishUserEventAt(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(deliveries).markFailure(H3WeeklyExchangeReferralEventConsumer.CONSUMER_GROUP,
                "EX-SOURCE-4", 0, "H3_EXCHANGE_COMPLETION_NOT_VERIFIED");
    }

    @Test
    void preRolloutOrPreviousWeekSourceIsSkippedWithoutHistoricalBackfill() {
        EventOutboxMessage old = exchange("EX-SOURCE-OLD", "EX-OLD", 42L);
        claim(old);
        when(mapper.verifiedCompletedExchange(42L, "EX-OLD"))
                .thenReturn(new H3WeeklyExchangeReferralMapper.VerifiedExchange(42L, "EX-OLD", ROLLOUT.minusSeconds(1)));
        when(mapper.rolloutEffectiveAt()).thenReturn(ROLLOUT);

        consumer.onOutboxMessage(old);

        verify(deliveries).markSkipped(H3WeeklyExchangeReferralEventConsumer.CONSUMER_GROUP,
                "EX-SOURCE-OLD", "H3_WEEKLY_EXCHANGE_REFERRAL_OUTSIDE_CURRENT_ROLLOUT_WEEK");
        verify(outbox, never()).publishUserEventAt(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void previousWeekSourceIsSkippedEvenWhenItArrivesAfterTheFence() {
        EventOutboxMessage old = exchange("EX-SOURCE-PREVIOUS-WEEK", "EX-PREVIOUS-WEEK", 42L);
        claim(old);
        LocalDateTime previousWeekFact = LocalDateTime.of(2026, 9, 6, 20, 0);
        when(mapper.verifiedCompletedExchange(42L, "EX-PREVIOUS-WEEK"))
                .thenReturn(new H3WeeklyExchangeReferralMapper.VerifiedExchange(42L, "EX-PREVIOUS-WEEK", previousWeekFact));
        when(mapper.rolloutEffectiveAt()).thenReturn(LocalDateTime.of(2026, 9, 6, 16, 0));

        consumer.onOutboxMessage(old);

        verify(deliveries).markSkipped(H3WeeklyExchangeReferralEventConsumer.CONSUMER_GROUP,
                "EX-SOURCE-PREVIOUS-WEEK", "H3_WEEKLY_EXCHANGE_REFERRAL_OUTSIDE_CURRENT_ROLLOUT_WEEK");
        verify(outbox, never()).publishUserEventAt(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void completedDeliveryIsAnIdempotencyFenceAndDoesNotEmitAgain() {
        EventOutboxMessage message = exchange("EX-SOURCE-5", "EX-904", 42L);
        when(deliveries.claim(message, H3WeeklyExchangeReferralEventConsumer.CONSUMER_GROUP,
                H3WeeklyExchangeReferralEventConsumer.TOPIC, "EX-SOURCE-5", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(false, "EX-SOURCE-5", "SUCCESS", 1));

        consumer.onOutboxMessage(message);

        verify(mapper, never()).verifiedCompletedExchange(42L, "EX-904");
        verify(outbox, never()).publishUserEventAt(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private void claim(EventOutboxMessage message) {
        when(deliveries.claim(message, H3WeeklyExchangeReferralEventConsumer.CONSUMER_GROUP,
                H3WeeklyExchangeReferralEventConsumer.TOPIC, message.getEventId(), 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, message.getEventId(), "PROCESSING", 1));
        when(mapper.rolloutEffectiveAt()).thenReturn(ROLLOUT);
    }

    private void readyAttribution(long userId) {
        when(mapper.userAttribution(userId))
                .thenReturn(new H3WeeklyExchangeReferralMapper.UserAttribution("P2", 3, "2026-W20"));
    }

    private static boolean sourcePayload(Object value, String sourceEventId, String subject) {
        if (!(value instanceof Map<?, ?> payload)) return false;
        return sourceEventId.equals(payload.get("sourceEventId"))
                && subject.equals(String.valueOf(payload.get("subject")))
                && "H3_WEEKLY_EXCHANGE_REFERRAL".equals(payload.get("source"));
    }

    private static EventOutboxMessage exchange(String eventId, String exchangeNo, long userId) {
        EventOutboxMessage message = base(eventId, "EXCHANGE_ORDER", exchangeNo, "exchange.swapped");
        message.setPayload("{\"user_id\":" + userId + "}");
        return message;
    }

    private static EventOutboxMessage referral(String eventId, long memberUserId, long sponsorUserId) {
        EventOutboxMessage message = base(eventId, "USER_REFERRAL", String.valueOf(memberUserId), "referral.bound");
        message.setPayload("{\"userId\":" + memberUserId + ",\"sponsorUserId\":" + sponsorUserId + "}");
        return message;
    }

    private static EventOutboxMessage base(String eventId, String aggregateType, String aggregateId, String eventType) {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(eventId);
        message.setAggregateType(aggregateType);
        message.setAggregateId(aggregateId);
        message.setEventType(eventType);
        message.setEventTs(CURRENT_WEEK_FACT);
        message.setServerAuthoritative(true);
        return message;
    }
}

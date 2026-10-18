package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.mapper.QuestCanonicalEventBindingMapper;
import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper;
import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper.DayOneSnapshotBinding;
import ffdd.opsconsole.growth.mapper.WeeklyQuestSnapshotMapper;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.user.facade.UserWeeklyRegionFacade;
import ffdd.opsconsole.shared.config.VietnamBusinessPeriod;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class QuestCanonicalEventConsumerTest {
    private final QuestCanonicalEventBindingMapper bindingMapper = mock(QuestCanonicalEventBindingMapper.class);
    private final DayOneInstanceMapper dayOneInstances = mock(DayOneInstanceMapper.class);
    private final EventConsumerDeliveryService deliveryService = mock(EventConsumerDeliveryService.class);
    private final QuestCanonicalEventProjector projector = mock(QuestCanonicalEventProjector.class);
    private final QuestCanonicalEventConsumer consumer = new QuestCanonicalEventConsumer(
            bindingMapper, dayOneInstances, deliveryService, projector, new ObjectMapper());

    @Test
    void vietnamWeekFenceKeepsTheTransitionHourInOldWeekAndIsolatesNextWeek() {
        assertThat(QuestCanonicalEventConsumer.sameIsoWeek(java.time.LocalDateTime.parse("2026-10-18T23:59:59"),
                java.time.LocalDateTime.parse("2026-10-19T00:59:59"))).isTrue();
        assertThat(QuestCanonicalEventConsumer.sameIsoWeek(java.time.LocalDateTime.parse("2026-10-19T00:59:59"),
                java.time.LocalDateTime.parse("2026-10-19T01:00:00"))).isFalse();
    }

    @Test
    void publishedEmptyWeekReachesTerminalProjectorInsteadOfWaitingForLaterBindings() {
        var weekly = mock(WeeklyQuestSnapshotService.class);
        var consumer = new QuestCanonicalEventConsumer(bindingMapper, dayOneInstances, deliveryService, projector, new ObjectMapper(), weekly);
        EventOutboxMessage message = event("evt-empty-week", "H3_COMPUTE_COMPLETED_50");
        message.setServerAuthoritative(true);
        message.setEventTs(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        message.setPayload("{\"user_id\":42}");
        when(weekly.current(42L)).thenReturn(new ffdd.opsconsole.growth.mapper.WeeklyQuestSnapshotMapper.Header(
                "WEEK:2026-W42",message.getEventTs().minusDays(1),message.getEventTs().plusDays(1),java.math.BigDecimal.ONE,3,0));
        when(weekly.publishedFor(42L,message.getEventTs(),null)).thenReturn(true);
        when(deliveryService.claim(message,QuestCanonicalEventConsumer.CONSUMER_GROUP,
                QuestCanonicalEventConsumer.TOPIC,"evt-empty-week",0)).thenReturn(
                new EventConsumerDeliveryService.ConsumerClaim(true,"evt-empty-week","PROCESSING",1));
        consumer.onOutboxMessage(message);
        verify(projector).project(message,"evt-empty-week");
        verify(deliveryService,never()).claimPendingBinding(message,QuestCanonicalEventConsumer.CONSUMER_GROUP,
                QuestCanonicalEventConsumer.TOPIC,"evt-empty-week",0);
    }

    @Test
    void configuredCanonicalEventUsesDurableClaimBeforeProjection() {
        EventOutboxMessage message = event("evt-order-1", "checkout.started");
        when(bindingMapper.countActiveBindings("checkout.started")).thenReturn(1);
        when(deliveryService.claim(message, QuestCanonicalEventConsumer.CONSUMER_GROUP,
                QuestCanonicalEventConsumer.TOPIC, "evt-order-1", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(
                        true, "evt-order-1", "PROCESSING", 1));

        consumer.onOutboxMessage(message);

        verify(projector).project(message, "evt-order-1");
    }

    @Test
    void unconfiguredEventsAreIgnoredWithoutCreatingFalseDeliveryReceipts() {
        EventOutboxMessage message = event("evt-unknown", "unknown.event");
        when(bindingMapper.countActiveBindings("unknown.event")).thenReturn(0);

        consumer.onOutboxMessage(message);

        verify(deliveryService, never()).claim(
                message, QuestCanonicalEventConsumer.CONSUMER_GROUP,
                QuestCanonicalEventConsumer.TOPIC, "evt-unknown", 0);
        verify(projector, never()).project(message, "evt-unknown");
    }

    @Test
    void failedProjectionIsDurablyMarkedForRetry() {
        EventOutboxMessage message = event("evt-bad", "LEARNING_COURSE_COMPLETED");
        when(bindingMapper.countActiveBindings("LEARNING_COURSE_COMPLETED")).thenReturn(1);
        when(deliveryService.claim(message, QuestCanonicalEventConsumer.CONSUMER_GROUP,
                QuestCanonicalEventConsumer.TOPIC, "evt-bad", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(
                        true, "evt-bad", "PROCESSING", 1));
        doThrow(new IllegalArgumentException("QUEST_CANONICAL_USER_ID_REQUIRED"))
                .when(projector).project(message, "evt-bad");

        assertThatThrownBy(() -> consumer.onOutboxMessage(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("QUEST_CANONICAL_USER_ID_REQUIRED");
        verify(deliveryService).markFailure(
                QuestCanonicalEventConsumer.CONSUMER_GROUP,
                "evt-bad", 0, "QUEST_CANONICAL_USER_ID_REQUIRED");
    }

    @Test
    void thresholdFactWithoutBindingBecomesDurableWaitWithoutProjectionOrRetry() {
        EventOutboxMessage message = event("evt-wait", "H3_COMPUTE_COMPLETED_50");
        when(bindingMapper.countActiveBindings("H3_COMPUTE_COMPLETED_50")).thenReturn(0);
        when(deliveryService.claimPendingBinding(message, QuestCanonicalEventConsumer.CONSUMER_GROUP,
                QuestCanonicalEventConsumer.TOPIC, "evt-wait", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "evt-wait", "PROCESSING", 1));

        consumer.onOutboxMessage(message);

        verify(deliveryService).claimPendingBinding(message, QuestCanonicalEventConsumer.CONSUMER_GROUP,
                QuestCanonicalEventConsumer.TOPIC, "evt-wait", 0);
        verify(projector, never()).project(message, "evt-wait");
        verify(deliveryService, never()).markFailure(
                QuestCanonicalEventConsumer.CONSUMER_GROUP, "evt-wait", 0, "H3_BINDING_UNAVAILABLE");
    }

    @Test
    void lateBindingResumesTheSameWaitingDeliveryOnlyOnce() {
        EventOutboxMessage message = event("evt-late", "H3_COMPUTE_COMPLETED_50");
        when(bindingMapper.countActiveBindings("H3_COMPUTE_COMPLETED_50")).thenReturn(1);
        when(deliveryService.claim(message, QuestCanonicalEventConsumer.CONSUMER_GROUP,
                QuestCanonicalEventConsumer.TOPIC, "evt-late", 0))
                .thenReturn(
                        new EventConsumerDeliveryService.ConsumerClaim(false, "evt-late", "PENDING_BINDING", 1),
                        new EventConsumerDeliveryService.ConsumerClaim(false, "evt-late", "SUCCESS", 1));
        when(deliveryService.resumePendingBinding(QuestCanonicalEventConsumer.CONSUMER_GROUP, "evt-late"))
                .thenReturn(true);

        consumer.onOutboxMessage(message);
        consumer.onOutboxMessage(message);

        verify(deliveryService).resumePendingBinding(QuestCanonicalEventConsumer.CONSUMER_GROUP, "evt-late");
        verify(projector, org.mockito.Mockito.times(1)).project(message, "evt-late");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sundayThresholdDeliveredNextWeekIsTerminallySkippedAndNeverRetried(boolean accountPeriod) {
        LocalDateTime sunday = LocalDateTime.of(2026, 10, 18, 23, 59, 59);
        LocalDateTime monday = LocalDateTime.of(2026, 10, 19, 0, 0);
        var snapshots = mock(WeeklyQuestSnapshotMapper.class);
        var users = mock(UserWeeklyRegionFacade.class);
        var environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        var weekly = new WeeklyQuestSnapshotService(snapshots, mock(GrowthRhythmFacade.class),
                Clock.fixed(Instant.parse("2026-10-18T16:00:00Z"), ZoneOffset.UTC), null,
                mock(WeeklyRegionPolicyService.class), users, environment);
        var header = new WeeklyQuestSnapshotMapper.Header("WEEK:2026-W42", monday.minusWeeks(1), monday,
                BigDecimal.ONE, 3, 1);
        when(users.lockActiveRegisteredCallingCode(42L, false))
                .thenReturn(new UserWeeklyRegionFacade.RegisteredCallingCode(42L, "84", false));
        when(snapshots.lockMutex()).thenReturn("H3_CONFIG");
        when(snapshots.findPublished(header.instanceKey())).thenReturn(header);
        when(snapshots.assigned(42L, header.instanceKey())).thenReturn(1);
        var consumer = accountPeriod ? new QuestCanonicalEventConsumer(bindingMapper, dayOneInstances, deliveryService,
                projector, new ObjectMapper(), weekly) : this.consumer;

        EventOutboxMessage message = event("evt-expired-week", "H3_COMPUTE_COMPLETED_50");
        message.setServerAuthoritative(true);
        // The legacy fixture must choose the previous business week even during the +08/+07 transition hour.
        var currentMonday = VietnamBusinessPeriod.calendarTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")))
                .toLocalDate().with(DayOfWeek.MONDAY);
        message.setEventTs(accountPeriod ? sunday : VietnamBusinessPeriod.storageBoundary(currentMonday).minusSeconds(1));
        message.setPayload("{\"user_id\":42,\"instanceKey\":\"WEEK:2026-W42\"}");
        when(deliveryService.claim(message, QuestCanonicalEventConsumer.CONSUMER_GROUP,
                QuestCanonicalEventConsumer.TOPIC, "evt-expired-week", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(true, "evt-expired-week", "PROCESSING", 1),
                        new EventConsumerDeliveryService.ConsumerClaim(false, "evt-expired-week", "SKIPPED", 1));

        consumer.onOutboxMessage(message);
        consumer.onOutboxMessage(message);

        verify(deliveryService).markSkipped(QuestCanonicalEventConsumer.CONSUMER_GROUP,
                "evt-expired-week", "H3_WEEKLY_FACT_OUTSIDE_CURRENT_WEEK");
        verify(bindingMapper, never()).countActiveBindings("H3_COMPUTE_COMPLETED_50");
        verify(projector, never()).project(message, "evt-expired-week");
    }

    @Test
    void frozenDayOneSnapshotClaimsAnEventAfterItsCurrentPcBindingWasRemoved() {
        EventOutboxMessage message = event("evt-day-one-frozen", "H3_DAY_ONE_EARN_PAGE_VIEWED");
        message.setEventTs(LocalDateTime.of(2026, 9, 9, 10, 0));
        message.setPayload("{\"user_id\":42}");
        when(bindingMapper.countActiveBindings("H3_DAY_ONE_EARN_PAGE_VIEWED")).thenReturn(0);
        when(dayOneInstances.listInWindowSnapshotBindings(List.of(42L), "H3_DAY_ONE_EARN_PAGE_VIEWED", message.getEventTs()))
                .thenReturn(List.of(new DayOneSnapshotBinding(71L, 42L, "DAY_ONE:20260909T090000", 9L,
                        "visit_earn", "DAY_ONE_EARN", "SYSTEM", "H3_DAY_ONE_EARN_PAGE_VIEWED",
                        "user_id", "{}")));
        when(deliveryService.claim(message, QuestCanonicalEventConsumer.CONSUMER_GROUP,
                QuestCanonicalEventConsumer.TOPIC, "evt-day-one-frozen", 0))
                .thenReturn(new EventConsumerDeliveryService.ConsumerClaim(
                        true, "evt-day-one-frozen", "PROCESSING", 1));

        consumer.onOutboxMessage(message);

        verify(projector).project(message, "evt-day-one-frozen");
    }
    private EventOutboxMessage event(String eventId, String eventType) {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(eventId);
        message.setEventType(eventType);
        message.setAggregateType("TEST");
        message.setAggregateId(eventId);
        message.setPayload("{}");
        return message;
    }
}

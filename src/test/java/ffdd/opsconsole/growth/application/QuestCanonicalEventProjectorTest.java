package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.application.QuestCompletionFactConsumer.QuestCompletionCommand;
import ffdd.opsconsole.growth.mapper.QuestCanonicalEventBindingMapper;
import ffdd.opsconsole.growth.mapper.QuestCanonicalEventBindingMapper.CanonicalQuestEventBinding;
import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper;
import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper.DayOneSnapshotBinding;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class QuestCanonicalEventProjectorTest {
    private static final LocalDateTime EVENT_TS = LocalDateTime.of(2026, 9, 1, 9, 30);
    private final QuestCanonicalEventBindingMapper bindingMapper = mock(QuestCanonicalEventBindingMapper.class);
    private final DayOneInstanceMapper dayOneInstances = mock(DayOneInstanceMapper.class);
    private final QuestCompletionFactConsumer factConsumer = mock(QuestCompletionFactConsumer.class);
    private final EventConsumerDeliveryService deliveryService = mock(EventConsumerDeliveryService.class);
    private final QuestCanonicalEventProjector projector = new QuestCanonicalEventProjector(
            bindingMapper, dayOneInstances, factConsumer, deliveryService, new ObjectMapper());

    @Test
    void routesOrderReferralLearningDeviceAndCommissionFactsToStableQuestCodes() {
        List<TestRoute> routes = List.of(
                new TestRoute("ORDER_STARTED", "ORDER", "checkout.started", "H3_FIRST_ORDER_STARTED", "user_id"),
                new TestRoute("REFERRAL_SETTLED", "REFERRAL", "H8_REFERRAL_REWARD_SETTLED", "H3_REFERRAL_SETTLED", "inviter_user_id"),
                new TestRoute("LEARNING_COMPLETED", "LEARNING", "LEARNING_COURSE_COMPLETED", "H3_LEARNING_COMPLETED", "user_id"),
                new TestRoute("DEVICE_ACTIVATED", "DEVICE", "admin.device_activated", "H3_DEVICE_ACTIVATED", "user_id"),
                new TestRoute("COMMISSION_UNLOCKED", "COMMISSION", "COMMISSION_UNLOCKED", "H3_COMMISSION_UNLOCKED", "user_id"));

        int index = 0;
        for (TestRoute route : routes) {
            index += 1;
            String eventId = "evt-h3-" + index;
            when(bindingMapper.listActiveBindings(route.eventType())).thenReturn(List.of(
                    new CanonicalQuestEventBinding(route.bindingCode(), route.producer(), route.eventType(),
                            route.questCode(), route.userIdField())));
            projector.project(event(eventId, route.eventType(),
                    "{\"" + route.userIdField() + "\":990725}"), eventId);
        }

        ArgumentCaptor<QuestCompletionCommand> commands = ArgumentCaptor.forClass(QuestCompletionCommand.class);
        verify(factConsumer, org.mockito.Mockito.times(5)).consume(commands.capture());
        assertThat(commands.getAllValues()).containsExactly(
                new QuestCompletionCommand("ORDER", "evt-h3-1:ORDER_STARTED", 990725L, "H3_FIRST_ORDER_STARTED", EVENT_TS),
                new QuestCompletionCommand("REFERRAL", "evt-h3-2:REFERRAL_SETTLED", 990725L, "H3_REFERRAL_SETTLED", EVENT_TS),
                new QuestCompletionCommand("LEARNING", "evt-h3-3:LEARNING_COMPLETED", 990725L, "H3_LEARNING_COMPLETED", EVENT_TS),
                new QuestCompletionCommand("DEVICE", "evt-h3-4:DEVICE_ACTIVATED", 990725L, "H3_DEVICE_ACTIVATED", EVENT_TS),
                new QuestCompletionCommand("COMMISSION", "evt-h3-5:COMMISSION_UNLOCKED", 990725L, "H3_COMMISSION_UNLOCKED", EVENT_TS));
        for (int delivery = 1; delivery <= 5; delivery += 1) {
            verify(deliveryService).markSuccess(
                    QuestCanonicalEventConsumer.CONSUMER_GROUP, "evt-h3-" + delivery, 1);
        }
    }

    @Test
    void missingConfiguredUserFieldFailsClosedBeforeCompletionOrSuccessReceipt() {
        when(bindingMapper.listActiveBindings("COMMISSION_UNLOCKED")).thenReturn(List.of(
                new CanonicalQuestEventBinding("COMMISSION_UNLOCKED", "COMMISSION",
                        "COMMISSION_UNLOCKED", "H3_COMMISSION_UNLOCKED", "user_id")));
        EventOutboxMessage message = event("evt-no-user", "COMMISSION_UNLOCKED", "{\"amount\":10}");

        assertThatThrownBy(() -> projector.project(message, "evt-no-user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("QUEST_CANONICAL_USER_ID_REQUIRED");
        verify(factConsumer, never()).consume(any());
        verify(deliveryService, never()).markSuccess(any(), any(), any(Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"H3_DAY_ONE_EARN_PAGE_VIEWED", "H3_COMPUTE_COMPLETED_50"})
    void unboundDayOneAndWeeklyFactsWaitForLaterBinding(String eventType) {
        when(bindingMapper.listActiveBindings(eventType)).thenReturn(List.of());

        projector.project(event("evt-h3-race", eventType, "{\"user_id\":990725}"),
                "evt-h3-race");

        verify(factConsumer, never()).consume(any());
        verify(deliveryService).markPendingBinding(
                QuestCanonicalEventConsumer.CONSUMER_GROUP, "evt-h3-race");
        verify(deliveryService, never()).markSuccess(
                QuestCanonicalEventConsumer.CONSUMER_GROUP, "evt-h3-race", 0);
    }
    @Test
    void retiredEventWithoutAnActiveMissionBindingIsAcknowledgedWithoutCompletingAnything() {
        when(bindingMapper.listActiveBindings("checkout.started")).thenReturn(List.of());

        projector.project(event("evt-retired-route", "checkout.started", "{\"user_id\":990725}"),
                "evt-retired-route");

        verify(factConsumer, never()).consume(any());
        verify(deliveryService).markSuccess(
                QuestCanonicalEventConsumer.CONSUMER_GROUP, "evt-retired-route", 0);
    }

    @Test
    void nonServerAuthoritativeMessageCannotCompleteAQuest() {
        EventOutboxMessage message = event(
                "evt-client", "checkout.started", "{\"user_id\":990725}");
        message.setServerAuthoritative(false);

        assertThatThrownBy(() -> projector.project(message, "evt-client"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("QUEST_CANONICAL_EVENT_NOT_SERVER_AUTHORITATIVE");
        verify(factConsumer, never()).consume(any());
        verify(deliveryService, never()).markSuccess(any(), any(), any(Integer.class));
    }

    @Test
    void missingDatabaseEventTimestampCannotCompleteAQuest() {
        EventOutboxMessage message = event(
                "evt-no-time", "checkout.started", "{\"user_id\":990725}");
        message.setEventTs(null);

        assertThatThrownBy(() -> projector.project(message, "evt-no-time"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("QUEST_CANONICAL_EVENT_TIMESTAMP_REQUIRED");
        verify(factConsumer, never()).consume(any());
        verify(deliveryService, never()).markSuccess(any(), any(), any(Integer.class));
    }

    @Test
    void dayOneAndWeeklyFactsBothUseTheTrustedOutboxTime() {
        when(bindingMapper.listActiveBindings("H3_DAY_ONE_EARN_PAGE_VIEWED")).thenReturn(List.of(
                new CanonicalQuestEventBinding("DAY_ONE_EARN", "SYSTEM",
                        "H3_DAY_ONE_EARN_PAGE_VIEWED", "visit_earn", "user_id")));
        when(bindingMapper.listActiveBindings("H3_COMPUTE_COMPLETED_50")).thenReturn(List.of(
                new CanonicalQuestEventBinding("WEEKLY", "SYSTEM",
                        "H3_COMPUTE_COMPLETED_50", "weekly_compute", "user_id")));

        projector.project(event("evt-day-one", "H3_DAY_ONE_EARN_PAGE_VIEWED", "{\"user_id\":990725}"), "evt-day-one");
        projector.project(event("evt-weekly", "H3_COMPUTE_COMPLETED_50", "{\"user_id\":990725}"), "evt-weekly");

        ArgumentCaptor<QuestCompletionCommand> commands = ArgumentCaptor.forClass(QuestCompletionCommand.class);
        verify(factConsumer, org.mockito.Mockito.times(2)).consume(commands.capture());
        assertThat(commands.getAllValues()).containsExactly(
                new QuestCompletionCommand("SYSTEM", "evt-day-one:DAY_ONE_EARN", 990725L,
                        "visit_earn", EVENT_TS),
                new QuestCompletionCommand("SYSTEM", "evt-weekly:WEEKLY", 990725L,
                        "weekly_compute", EVENT_TS));
    }

    @Test
    void correctDayOneBindingStillProjectsWhenAHistoricalInviterSlotBindingIsPresent() {
        when(bindingMapper.listActiveBindings("H3_DAY_ONE_STORE_PAGE_VIEWED")).thenReturn(List.of(
                new CanonicalQuestEventBinding("WRONG_INVITER", "SYSTEM",
                        "H3_DAY_ONE_STORE_PAGE_VIEWED", "visit_store", "inviter_user_id"),
                new CanonicalQuestEventBinding("DAY_ONE_STORE", "SYSTEM",
                        "H3_DAY_ONE_STORE_PAGE_VIEWED", "visit_store", "user_id")));

        projector.project(event("evt-store", "H3_DAY_ONE_STORE_PAGE_VIEWED", "{\"user_id\":990725}"), "evt-store");

        verify(factConsumer).consume(new QuestCompletionCommand("SYSTEM", "evt-store:DAY_ONE_STORE",
                990725L, "visit_store", EVENT_TS));
        verify(deliveryService).markSuccess(QuestCanonicalEventConsumer.CONSUMER_GROUP, "evt-store", 1);
    }

    @Test
    void onlyHistoricalWrongDayOneBindingsKeepTheFactPendingForALaterExactBinding() {
        when(bindingMapper.listActiveBindings("H3_DAY_ONE_STORE_PAGE_VIEWED")).thenReturn(List.of(
                new CanonicalQuestEventBinding("WRONG_INVITER", "SYSTEM",
                        "H3_DAY_ONE_STORE_PAGE_VIEWED", "visit_store", "inviter_user_id"),
                new CanonicalQuestEventBinding("WRONG_QUEST", "SYSTEM",
                        "H3_DAY_ONE_STORE_PAGE_VIEWED", "visit_earn", "user_id")));

        projector.project(event("evt-store-wrong-only", "H3_DAY_ONE_STORE_PAGE_VIEWED", "{\"user_id\":990725}"),
                "evt-store-wrong-only");

        verify(factConsumer, never()).consume(any());
        verify(deliveryService).markPendingBinding(
                QuestCanonicalEventConsumer.CONSUMER_GROUP, "evt-store-wrong-only");
        verify(deliveryService, never()).markSuccess(
                QuestCanonicalEventConsumer.CONSUMER_GROUP, "evt-store-wrong-only", 0);
    }

    @Test
    void snapshotBindingCompletesItsFrozenItemWhenCurrentPcBindingIsGone() {
        when(bindingMapper.listActiveBindings("H3_DAY_ONE_EARN_PAGE_VIEWED")).thenReturn(List.of());
        when(dayOneInstances.listInWindowSnapshotBindings(List.of(990725L), "H3_DAY_ONE_EARN_PAGE_VIEWED", EVENT_TS)).thenReturn(List.of(
                new DayOneSnapshotBinding(71L, 990725L, "DAY_ONE:20260901T090000", 9L,
                        "visit_earn", "DAY_ONE_EARN", "SYSTEM", "H3_DAY_ONE_EARN_PAGE_VIEWED", "user_id",
                        "{\"bindingCode\":\"DAY_ONE_EARN\",\"producer\":\"SYSTEM\",\"eventType\":\"H3_DAY_ONE_EARN_PAGE_VIEWED\",\"userIdField\":\"user_id\"}")));

        projector.project(event("evt-frozen-day-one", "H3_DAY_ONE_EARN_PAGE_VIEWED", "{\"user_id\":990725}"),
                "evt-frozen-day-one");

        verify(factConsumer).consume(new QuestCompletionCommand("SYSTEM", "evt-frozen-day-one:I71:M9", 990725L,
                "visit_earn", EVENT_TS, 9L, "DAY_ONE:20260901T090000"));
        verify(deliveryService).markSuccess(QuestCanonicalEventConsumer.CONSUMER_GROUP,
                "evt-frozen-day-one", 1);
    }

    @Test
    void snapshotLookupIsBoundToTrustedPayloadUsersAndCannotCompleteAnotherUsersInstance() {
        when(bindingMapper.listActiveBindings("H3_DAY_ONE_EARN_PAGE_VIEWED")).thenReturn(List.of());
        when(dayOneInstances.listInWindowSnapshotBindings(List.of(42L), "H3_DAY_ONE_EARN_PAGE_VIEWED", EVENT_TS))
                .thenReturn(List.of(new DayOneSnapshotBinding(71L, 99L, "DAY_ONE:other", 9L,
                        "visit_earn", "DAY_ONE_EARN", "SYSTEM", "H3_DAY_ONE_EARN_PAGE_VIEWED", "user_id",
                        "{\"bindingCode\":\"DAY_ONE_EARN\",\"producer\":\"SYSTEM\",\"eventType\":\"H3_DAY_ONE_EARN_PAGE_VIEWED\",\"userIdField\":\"user_id\"}")));

        projector.project(event("evt-cross-user", "H3_DAY_ONE_EARN_PAGE_VIEWED", "{\"user_id\":42}"),
                "evt-cross-user");

        verify(dayOneInstances).listInWindowSnapshotBindings(
                List.of(42L), "H3_DAY_ONE_EARN_PAGE_VIEWED", EVENT_TS);
        verify(factConsumer, never()).consume(any());
        verify(deliveryService).markPendingBinding(
                QuestCanonicalEventConsumer.CONSUMER_GROUP, "evt-cross-user");
    }

    @Test
    void frozenDayOneRouteSuppressesALaterCurrentDayOneRemapForTheSameUser() {
        when(bindingMapper.listActiveBindings("H3_DAY_ONE_EARN_PAGE_VIEWED")).thenReturn(List.of(
                new CanonicalQuestEventBinding("CURRENT_REMAP", "SYSTEM", "H3_DAY_ONE_EARN_PAGE_VIEWED",
                        "new_pc_quest", "user_id", "DAY_ONE")));
        when(dayOneInstances.listInWindowSnapshotBindings(List.of(990725L), "H3_DAY_ONE_EARN_PAGE_VIEWED", EVENT_TS))
                .thenReturn(List.of(new DayOneSnapshotBinding(71L, 990725L, "DAY_ONE:20260901T090000", 9L,
                        "visit_earn", "DAY_ONE_EARN", "SYSTEM", "H3_DAY_ONE_EARN_PAGE_VIEWED", "user_id",
                        "{\"bindingCode\":\"DAY_ONE_EARN\",\"producer\":\"SYSTEM\",\"eventType\":\"H3_DAY_ONE_EARN_PAGE_VIEWED\",\"userIdField\":\"user_id\"}")));

        projector.project(event("evt-remap", "H3_DAY_ONE_EARN_PAGE_VIEWED", "{\"user_id\":990725}"), "evt-remap");

        verify(factConsumer).consume(new QuestCompletionCommand("SYSTEM", "evt-remap:I71:M9", 990725L,
                "visit_earn", EVENT_TS, 9L, "DAY_ONE:20260901T090000"));
        verify(factConsumer, never()).consume(new QuestCompletionCommand("SYSTEM", "evt-remap:CURRENT_REMAP", 990725L,
                "new_pc_quest", EVENT_TS));
        verify(deliveryService).markSuccess(QuestCanonicalEventConsumer.CONSUMER_GROUP, "evt-remap", 1);
    }

    private EventOutboxMessage event(String eventId, String eventType, String payload) {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(eventId);
        message.setEventType(eventType);
        message.setAggregateType("TEST");
        message.setAggregateId(eventId);
        message.setPayload(payload);
        message.setServerAuthoritative(true);
        message.setEventTs(EVENT_TS);
        return message;
    }

    private record TestRoute(
            String bindingCode, String producer, String eventType, String questCode, String userIdField) {
    }
}

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
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuestCanonicalEventProjectorTest {
    private final QuestCanonicalEventBindingMapper bindingMapper = mock(QuestCanonicalEventBindingMapper.class);
    private final QuestCompletionFactConsumer factConsumer = mock(QuestCompletionFactConsumer.class);
    private final EventConsumerDeliveryService deliveryService = mock(EventConsumerDeliveryService.class);
    private final QuestCanonicalEventProjector projector = new QuestCanonicalEventProjector(
            bindingMapper, factConsumer, deliveryService, new ObjectMapper());

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
                new QuestCompletionCommand("ORDER", "evt-h3-1:ORDER_STARTED", 990725L, "H3_FIRST_ORDER_STARTED"),
                new QuestCompletionCommand("REFERRAL", "evt-h3-2:REFERRAL_SETTLED", 990725L, "H3_REFERRAL_SETTLED"),
                new QuestCompletionCommand("LEARNING", "evt-h3-3:LEARNING_COMPLETED", 990725L, "H3_LEARNING_COMPLETED"),
                new QuestCompletionCommand("DEVICE", "evt-h3-4:DEVICE_ACTIVATED", 990725L, "H3_DEVICE_ACTIVATED"),
                new QuestCompletionCommand("COMMISSION", "evt-h3-5:COMMISSION_UNLOCKED", 990725L, "H3_COMMISSION_UNLOCKED"));
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

    private EventOutboxMessage event(String eventId, String eventType, String payload) {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId(eventId);
        message.setEventType(eventType);
        message.setAggregateType("TEST");
        message.setAggregateId(eventId);
        message.setPayload(payload);
        message.setServerAuthoritative(true);
        return message;
    }

    private record TestRoute(
            String bindingCode, String producer, String eventType, String questCode, String userIdField) {
    }
}

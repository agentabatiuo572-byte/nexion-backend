package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.outbox.EventConsumerDelivery;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class C2AdminAlertServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void successfulDurableReceiptsBecomeHumanVisibleC2Alerts() {
        EventConsumerDeliveryService deliveries = mock(EventConsumerDeliveryService.class);
        EventConsumerDelivery delivery = new EventConsumerDelivery();
        delivery.setEventId("event-52");
        delivery.setEventType("admin.user_frozen");
        delivery.setAggregateType("USER");
        delivery.setAggregateId("52");
        delivery.setProcessedAt(LocalDateTime.of(2026, 7, 18, 12, 0));
        when(deliveries.listByStatus(C2HighRiskAdminAlertConsumer.CONSUMER_GROUP, "SUCCESS", 30))
                .thenReturn(List.of(delivery));

        Map<String, Object> data = new C2AdminAlertService(deliveries).alerts().getData();
        List<Map<String, Object>> alerts = (List<Map<String, Object>>) data.get("alerts");

        assertThat(alerts).singleElement().satisfies(alert -> {
            assertThat(alert).containsEntry("id", "C2-event-52")
                    .containsEntry("domain", "C2")
                    .containsEntry("level", "high")
                    .containsEntry("title", "C2 账户已冻结");
            assertThat(alert.get("hint")).asString().contains("用户ID 52");
        });
        assertThat(data).containsEntry("source", "nx_event_consumer_delivery");
    }
}

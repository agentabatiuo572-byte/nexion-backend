package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.outbox.OutboxProperties;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class I3A4NotificationEventClosureContractTest {

    @Test
    void notificationDeliveryReadAndActionAreRegisteredAndProduced() throws Exception {
        Path migrationPath = Path.of("scripts/migrations/20260722_i3_a4_event_closure.sql");
        assertThat(migrationPath).exists();
        String migration = Files.readString(migrationPath, StandardCharsets.UTF_8);
        String dispatch = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/content/application/NotificationCampaignDispatchExecutor.java"));
        String app = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/content/application/AppNotificationService.java"));
        String eventCenter = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/platform/application/OpsEventCenterService.java"));

        assertThat(migration)
                .contains("'notification.delivered'", "'notification.read'", "'notification.swipe_action_taken'")
                .contains("INSERT INTO nx_event_schema_registry")
                .contains("INSERT INTO nx_event_schema_property")
                .contains("INSERT INTO nx_event_domain_extension")
                .contains("CREATE TABLE IF NOT EXISTS nx_notification_action_receipt")
                .contains("'campaign_id'", "'notification_id'", "'kind'", "'priority'", "'action'", "'route'");
        assertThat(dispatch).contains("notification.delivered", "listNotificationEventFactsByBizNo");
        assertThat(app).contains("notification.read", "notification.swipe_action_taken", "recordAction");
        assertThat(eventCenter)
                .contains("A4 已注册 · I3")
                .doesNotContain("通知三件套(I3)占位中");
    }

    @Test
    void allThreeI3PayloadsSatisfyTheRegisteredA4Types() {
        EventOutboxMapper mapper = mock(EventOutboxMapper.class);
        EventOutboxService outbox = new EventOutboxService(mapper, new ObjectMapper(), new OutboxProperties());
        when(mapper.findActiveSchema("notification.delivered"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("notification", 164, true));
        when(mapper.findActiveSchema("notification.read"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("notification", 165, true));
        when(mapper.findActiveSchema("notification.swipe_action_taken"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("notification", 166, false));
        when(mapper.listActiveProperties("notification.delivered")).thenReturn(List.of(
                required("campaign_id", "id"), required("notification_id", "id"),
                required("kind", "enum"), required("priority", "enum")));
        when(mapper.listActiveProperties("notification.read")).thenReturn(List.of(
                required("notification_id", "id"), required("kind", "enum"),
                required("priority", "enum")));
        when(mapper.listActiveProperties("notification.swipe_action_taken")).thenReturn(List.of(
                required("notification_id", "id"), required("kind", "enum"),
                required("action", "enum"), required("route", "string")));

        outbox.publishUserEvent("NOTIFICATION", "99", "notification.delivered",
                7L, "P3", 4, "2026-W10", Map.of(
                        "campaign_id", "CMP-1", "notification_id", 99L,
                        "kind", "system", "priority", "high"));
        outbox.publishUserEvent("NOTIFICATION", "99", "notification.read",
                7L, "P3", 4, "2026-W10", Map.of(
                        "notification_id", 99L, "kind", "system", "priority", "high"));
        outbox.publishUserEvent("NOTIFICATION", "99", "notification.swipe_action_taken",
                7L, "P3", 4, "2026-W10", Map.of(
                        "notification_id", 99L, "kind", "commission", "action", "cta",
                        "route", "/pages/me/wallet-repurchase"));

        verify(mapper, org.mockito.Mockito.times(3)).insertEvent(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyString(), anyBoolean(),
                org.mockito.ArgumentMatchers.any(), anyBoolean(), anyBoolean(), anyString());
    }

    private EventOutboxMapper.SchemaPropertyGateRow required(String name, String type) {
        return new EventOutboxMapper.SchemaPropertyGateRow(name, type, true);
    }
}

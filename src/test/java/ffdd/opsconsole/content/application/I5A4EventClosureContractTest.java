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

class I5A4EventClosureContractTest {

    @Test
    void disclosureUserAndReackEventsArePublishedThroughTheGovernedA4Outbox() throws Exception {
        String appService = Files.readString(
                Path.of("src/main/java/ffdd/opsconsole/content/application/AppRiskDisclosureService.java"),
                StandardCharsets.UTF_8);
        String reackService = Files.readString(
                Path.of("src/main/java/ffdd/opsconsole/content/application/DisclosureReackNotificationService.java"),
                StandardCharsets.UTF_8);
        String eventCenter = Files.readString(
                Path.of("src/main/java/ffdd/opsconsole/platform/application/OpsEventCenterService.java"),
                StandardCharsets.UTF_8);
        Path migrationPath = Path.of("scripts/migrations/20260722_i5_a4_event_closure.sql");

        assertThat(appService)
                .contains("EventOutboxService")
                .contains("disclosure.viewed")
                .contains("disclosure.acked")
                .contains("disclosure.gated_action_blocked");
        assertThat(reackService)
                .contains("EventOutboxService")
                .contains("disclosure.reack_triggered");
        assertThat(eventCenter)
                .contains("disclosure.viewed / acked / reack_triggered / gated_action_blocked")
                .contains("A4 已注册 · I5")
                .doesNotContain("披露操作链(I5)占位中");
        assertThat(migrationPath).exists();

        String migration = Files.readString(migrationPath, StandardCharsets.UTF_8);
        assertThat(migration)
                .contains("'disclosure.viewed'", "'disclosure.acked'",
                        "'disclosure.reack_triggered'", "'disclosure.gated_action_blocked'")
                .contains("INSERT INTO nx_event_schema_registry")
                .contains("INSERT INTO nx_event_schema_property")
                .contains("INSERT INTO nx_event_domain_extension")
                .contains("'jurisdiction'", "'version'", "'gated_action_context'",
                        "'dual_gate_satisfied'", "'from_version'", "'to_version'", "'gated_action'")
                .contains("is_server_authoritative");
    }

    @Test
    void allFourI5PayloadsSatisfyTheirRegisteredTypesAndRequiredFields() {
        EventOutboxMapper mapper = mock(EventOutboxMapper.class);
        EventOutboxService outbox = new EventOutboxService(mapper, new ObjectMapper(), new OutboxProperties());
        when(mapper.findActiveSchema(anyString()))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("compliance", 115, true));
        when(mapper.listActiveProperties("disclosure.viewed")).thenReturn(List.of(
                required("jurisdiction", "enum"), required("version", "id"),
                required("gated_action_context", "enum")));
        when(mapper.listActiveProperties("disclosure.acked")).thenReturn(List.of(
                required("jurisdiction", "enum"), required("version", "id"),
                required("dual_gate_satisfied", "boolean")));
        when(mapper.listActiveProperties("disclosure.reack_triggered")).thenReturn(List.of(
                required("jurisdiction", "enum"), required("from_version", "id"),
                required("to_version", "id"), required("affected_user_count", "number")));
        when(mapper.listActiveProperties("disclosure.gated_action_blocked")).thenReturn(List.of(
                required("jurisdiction", "enum"), required("version", "id"),
                required("gated_action", "enum"), required("business_flow_id", "id")));

        outbox.publishUserEvent("DISCLOSURE_VIEW", "42:SBV", "disclosure.viewed",
                42L, "P3", 4, "2026-W10",
                Map.of("jurisdiction", "SBV", "version", "v1", "gated_action_context", "direct"));
        outbox.publishUserEvent("DISCLOSURE_ACK", "42:SBV", "disclosure.acked",
                42L, "P3", 4, "2026-W10",
                Map.of("jurisdiction", "SBV", "version", "v1", "dual_gate_satisfied", true));
        outbox.publish("DISCLOSURE_REACK", "i5:reack:SBV:v2:key", "disclosure.reack_triggered",
                Map.of("jurisdiction", "SBV", "from_version", "v1", "to_version", "v2",
                        "affected_user_count", 12));
        outbox.publishUserEvent("DISCLOSURE_GATE", "WD-1001", "disclosure.gated_action_blocked",
                42L, "P3", 4, "2026-W10",
                Map.of("jurisdiction", "SBV", "version", "v1", "gated_action", "withdraw",
                        "business_flow_id", "WD-1001"));

        verify(mapper, org.mockito.Mockito.times(4)).insertEvent(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyString(), anyBoolean(), org.mockito.ArgumentMatchers.any(),
                anyBoolean(), anyBoolean(), anyString());
    }

    private EventOutboxMapper.SchemaPropertyGateRow required(String name, String type) {
        return new EventOutboxMapper.SchemaPropertyGateRow(name, type, true);
    }
}

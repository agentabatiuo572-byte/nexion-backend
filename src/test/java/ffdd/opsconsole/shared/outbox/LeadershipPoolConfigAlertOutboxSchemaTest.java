package ffdd.opsconsole.shared.outbox;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.application.A4RuntimePolicyService;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class LeadershipPoolConfigAlertOutboxSchemaTest {

    @Test
    void registeredAlertPayloadPassesA4ValidationInsteadOfBreakingTheFailClosedScheduler() {
        EventOutboxMapper mapper = Mockito.mock(EventOutboxMapper.class);
        A4RuntimePolicyService policy = Mockito.mock(A4RuntimePolicyService.class);
        EventOutboxService outbox = new EventOutboxService(mapper, new ObjectMapper(), new OutboxProperties(), policy);
        when(mapper.findActiveSchema("leadership_pool.settlement_blocked"))
                .thenReturn(new EventOutboxMapper.SchemaGateRow("operations", 305, true));
        when(mapper.findLifecycleState("leadership_pool.settlement_blocked")).thenReturn("full");
        when(mapper.listActiveProperties("leadership_pool.settlement_blocked")).thenReturn(List.of(
                required("source", "enum"),
                required("config_key", "string"),
                required("reason", "string"),
                required("value_fingerprint", "string"),
                required("blocked_at", "timestamp")));
        when(policy.samplingPercent(anyString(), anyBoolean())).thenReturn(100);

        assertThatCode(() -> outbox.publish(
                "LEADERSHIP_POOL_CONFIG", "absent", "leadership_pool.settlement_blocked", Map.of(
                        "source", "scheduler",
                        "configKey", "team.ui.F.pool.ratio",
                        "reason", "MISSING",
                        "valueFingerprint", "absent",
                        "blockedAt", "2026-08-11T00:00:00Z")))
                .doesNotThrowAnyException();
    }

    private EventOutboxMapper.SchemaPropertyGateRow required(String name, String type) {
        return new EventOutboxMapper.SchemaPropertyGateRow(name, type, true);
    }
}

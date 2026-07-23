package ffdd.opsconsole.janus.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.janus.application.OpsJanusService;
import ffdd.opsconsole.janus.domain.JanusDeviceView;
import ffdd.opsconsole.janus.domain.JanusRuleEvaluator;
import ffdd.opsconsole.janus.mapper.JanusMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.util.List;
import java.util.Map;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.junit.jupiter.api.Test;

class MybatisJanusRepositoryTest {
    private final JanusMapper mapper = mock(JanusMapper.class);
    private final MybatisJanusRepository repository = new MybatisJanusRepository(mapper, new ObjectMapper());

    @Test
    void hidesStrategyCommandPayloadFromManagementManualOverrideProjection() {
        when(mapper.findDevice("J-STRATEGY")).thenReturn(device("J-STRATEGY",
                "{\"source\":\"strategy\",\"reportId\":\"report-1\",\"action\":\"REVERSAL_IMMEDIATE\",\"strategyId\":\"st-1\"}"));

        JanusDeviceView view = repository.findDevice("J-STRATEGY").orElseThrow();

        assertThat(view.manualOverride()).isNotNull();
        assertThat(view.manualOverride().isEmpty()).isTrue();
    }

    @Test
    void preservesRealManualOverrideInManagementProjection() {
        when(mapper.findDevice("J-MANUAL")).thenReturn(device("J-MANUAL",
                "{\"targetStatus\":\"MANUAL_HOLD\",\"reasonCategory\":\"risk\",\"reasonText\":\"review\","
                        + "\"operatorId\":\"admin\",\"effectiveTiming\":\"immediate\",\"createdAt\":1,\"confirmationMode\":\"standard\"}"));

        JanusDeviceView view = repository.findDevice("J-MANUAL").orElseThrow();

        assertThat(view.manualOverride().path("targetStatus").asText()).isEqualTo("MANUAL_HOLD");
        assertThat(view.manualOverride().path("operatorId").asText()).isEqualTo("admin");
    }

    @Test
    void keepsStrategyPayloadAvailableToAppPendingCommandPath() {
        Map<String, Object> pending = Map.of(
                "sid", "J-STRATEGY",
                "payloadJson", "{\"source\":\"strategy\",\"strategyId\":\"st-1\"}");
        when(mapper.findPendingDeviceCommand(42L, "J-STRATEGY")).thenReturn(pending);

        assertThat(repository.findPendingDeviceCommand(42L, "J-STRATEGY")).contains(pending);
    }

    @Test
    void dashboardCountsOnlyRealManualOverridesAfterRepositoryProjection() {
        JanusDeviceRecord strategy = device("J-STRATEGY",
                "{\"source\":\"strategy\",\"strategyId\":\"st-1\"}");
        JanusDeviceRecord manual = device("J-MANUAL",
                "{\"targetStatus\":\"MANUAL_HOLD\",\"operatorId\":\"admin\"}");
        when(mapper.countDevices(null, null, null, null, null)).thenReturn(2L);
        when(mapper.pageDevices(null, null, null, null, null, 0L, 200)).thenReturn(List.of(strategy, manual));
        when(mapper.strategies()).thenReturn(List.of());
        AuditLogService audit = mock(AuditLogService.class);
        when(audit.list(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        OpsJanusService service = new OpsJanusService(repository, new JanusRuleEvaluator(), new ObjectMapper(),
                audit, mock(EventOutboxService.class));

        Map<String, Object> summary = castMap(service.dashboard().getData().get("summary"));

        assertThat(summary.get("manualOverrides")).isEqualTo(1L);
    }

    @Test
    void reservesNewCommandBeforeAnyExpiryCleanupToAvoidGapLockDeadlocks() {
        when(mapper.insertCommandReservation("idem-new", "STRATEGY_DRY_RUN", "K6-1", "hash", "admin"))
                .thenReturn(1);

        boolean reserved = repository.reserveCommand("idem-new", "STRATEGY_DRY_RUN", "K6-1", "hash", "admin");

        assertThat(reserved).isTrue();
        verify(mapper, never()).deleteExpiredCommand("idem-new");
        verify(mapper).insertCommandReservation("idem-new", "STRATEGY_DRY_RUN", "K6-1", "hash", "admin");
    }

    @Test
    void reusesExpiredCommandOnlyAfterDuplicateThenSuccessfulExpiryDelete() {
        when(mapper.insertCommandReservation("idem-expired", "STRATEGY_DRY_RUN", "K6-1", "hash", "admin"))
                .thenThrow(new DuplicateKeyException("duplicate"))
                .thenReturn(1);
        when(mapper.deleteExpiredCommand("idem-expired")).thenReturn(1);

        boolean reserved = repository.reserveCommand("idem-expired", "STRATEGY_DRY_RUN", "K6-1", "hash", "admin");

        assertThat(reserved).isTrue();
        InOrder order = inOrder(mapper);
        order.verify(mapper).insertCommandReservation("idem-expired", "STRATEGY_DRY_RUN", "K6-1", "hash", "admin");
        order.verify(mapper).deleteExpiredCommand("idem-expired");
        order.verify(mapper).insertCommandReservation("idem-expired", "STRATEGY_DRY_RUN", "K6-1", "hash", "admin");
    }

    @Test
    void leavesUnexpiredDuplicateUntouchedForExistingCommandValidation() {
        when(mapper.insertCommandReservation("idem-active", "STRATEGY_DRY_RUN", "K6-1", "hash", "admin"))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(mapper.deleteExpiredCommand("idem-active")).thenReturn(0);

        boolean reserved = repository.reserveCommand("idem-active", "STRATEGY_DRY_RUN", "K6-1", "hash", "admin");

        assertThat(reserved).isFalse();
        verify(mapper).deleteExpiredCommand("idem-active");
        verify(mapper).insertCommandReservation("idem-active", "STRATEGY_DRY_RUN", "K6-1", "hash", "admin");
    }

    private JanusDeviceRecord device(String sid, String manualOverrideJson) {
        JanusDeviceRecord row = new JanusDeviceRecord();
        row.setSid(sid);
        row.setStatus("OBSERVING");
        row.setRecommendationScore(10);
        row.setManualOverrideJson(manualOverrideJson);
        row.setTagsJson("[]");
        return row;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}

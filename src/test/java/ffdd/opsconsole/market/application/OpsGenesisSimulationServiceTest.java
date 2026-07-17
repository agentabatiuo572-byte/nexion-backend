package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.market.dto.GenesisSimulationRequest;
import ffdd.opsconsole.market.dto.NexMarketValueUpdateRequest;
import ffdd.opsconsole.market.mapper.GenesisSimulationMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Supplier;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

class OpsGenesisSimulationServiceTest {
    private final GenesisSimulationMapper mapper = mock(GenesisSimulationMapper.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final OpsGenesisSimulationService service = new OpsGenesisSimulationService(mapper, config, audit, idempotency);

    @BeforeEach
    void setUp() {
        when(mapper.lockConfigMutation()).thenReturn("G4_CONFIG");
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(Map.class), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
    }

    @Test
    void createsClearlyMarkedAdminOnlySimulationWithoutTouchingRealLedger() {
        when(mapper.insertSimulation(anyString(), eq("BUY"), eq(new BigDecimal("12.5")),
                eq(new BigDecimal("0.8")), eq("ops drill"), eq("superadmin"))).thenReturn(1);

        Map<String, Object> result = service.create("idem-sim-1",
                new GenesisSimulationRequest("BUY", new BigDecimal("12.5"), new BigDecimal("0.8"), "ops drill", "superadmin"));

        assertThat(result).containsEntry("recordType", "SIMULATED").containsEntry("ledgerImpact", "NONE");
        verify(audit).recordRequired(any());
    }

    @Test
    void rejectsSimulationThatIsNotExplicitlyDescribed() {
        assertThatThrownBy(() -> service.create("idem-sim-2",
                new GenesisSimulationRequest("BUY", BigDecimal.ONE, BigDecimal.ONE, "x", "superadmin")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("SIMULATION_REASON_TOO_SHORT");
    }

    @Test
    void rejectsPresaleStartThatIsNotBeforeConfiguredEnd() {
        when(config.activeValue("market.genesis.ops.presale.endAt"))
                .thenReturn(Optional.of("2026-08-01T00:00:00Z"));

        assertThatThrownBy(() -> service.updateConfig("presale.startAt", "idem-window-1",
                new NexMarketValueUpdateRequest("2026-08-02T00:00:00Z", "configure presale window", "superadmin")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("GENESIS_PRESALE_WINDOW_INVALID");
        verify(mapper).lockConfigMutation();
    }

    @Test
    void configMutationUsesSerializableIsolationUntilCommit() throws Exception {
        Transactional transactional = OpsGenesisSimulationService.class
                .getMethod("updateConfig", String.class, String.class, NexMarketValueUpdateRequest.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.isolation()).isEqualTo(Isolation.SERIALIZABLE);
    }

    @Test
    void missingDatabaseMutexFailsClosedBeforeConfigWrite() {
        when(mapper.lockConfigMutation()).thenReturn(null);

        assertThatThrownBy(() -> service.updateConfig("eligibility.enabled", "idem-no-mutex",
                new NexMarketValueUpdateRequest("true", "mutex health verification", "superadmin")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("G4_CONFIG_MUTEX_UNAVAILABLE");
    }
}

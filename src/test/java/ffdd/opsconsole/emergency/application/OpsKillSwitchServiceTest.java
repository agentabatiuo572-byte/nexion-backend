package ffdd.opsconsole.emergency.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.emergency.dto.EmergencyDisableRequest;
import ffdd.opsconsole.emergency.dto.KillSwitchToggleRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsKillSwitchServiceTest {
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final FakeTreasuryCoverageFacade coverageFacade = new FakeTreasuryCoverageFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsKillSwitchService service = new OpsKillSwitchService(
            configFacade,
            coverageFacade,
            auditLogService,
            ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction());

    @Test
    void matrixHasFiveActiveGatesAndRetiredSunsetGatesWithoutWritingConfig() {
        var result = service.matrix();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("activeGateCount", 5);
        assertThat(result.getData().get("retiredGates").toString()).contains("premium", "nexv2", "points");
        assertThat(configFacade.values).isEmpty();
    }

    @Test
    void disabledReadTimeSeedsExposeJ1EnumMatrixWithoutWritingConfig() {
        OpsKillSwitchService realOnlyService = new OpsKillSwitchService(
                configFacade,
                coverageFacade,
                mock(AuditLogService.class),
                OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        var result = realOnlyService.matrix();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("activeGateCount", 5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> activeGates = (List<Map<String, Object>>) result.getData().get("activeGates");
        assertThat(activeGates)
                .extracting(gate -> String.valueOf(gate.get("key")))
                .containsExactly("withdraw", "staking", "genesis", "exchange", "trial");
        assertThat(detailMap(result.getData().get("stats")))
                .containsEntry("liveGateCount", 0L)
                .containsEntry("killedGateCount", 5L);
        assertThat(configFacade.values).isEmpty();
    }

    @Test
    void restoreBelowB1RedlineReturns422() {
        configFacade.values.put("killswitch.withdraw", "disabled");
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));

        var result = service.toggle(
                "withdraw",
                "idem-j1",
                new KillSwitchToggleRequest("enabled", "restore withdrawals", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
    }

    @Test
    void restoreTrialDoesNotRequireB1Redline() {
        configFacade.values.put("killswitch.trial", "disabled");
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));

        var result = service.toggle(
                "trial",
                "idem-j1-trial-restore",
                new KillSwitchToggleRequest("enabled", "restore trial benefit gate", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("killswitch.trial", "enabled");
    }

    @Test
    void disableActiveGateWritesConfigAndAudit() {
        var result = service.toggle(
                "exchange",
                "idem-j1",
                new KillSwitchToggleRequest("disabled", "market incident", "risk-lead"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("killswitch.exchange", "disabled");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("J1_KILLSWITCH_TOGGLED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-j1");
    }

    @Test
    void retiredGateCannotBeToggled() {
        var result = service.toggle(
                "premium",
                "idem-j1",
                new KillSwitchToggleRequest("disabled", "old gate", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.RETIRED_FEATURE.httpStatus());
    }

    @Test
    void emergencyDisableOnlyAppliesActiveGates() {
        var result = service.emergencyDisable(
                "idem-j1-batch",
                new EmergencyDisableRequest(List.of("withdraw", "trial"), "incident response", "risk-lead"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("killswitch.withdraw", "disabled")
                .containsEntry("killswitch.trial", "disabled");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
    }

    private static final class FakePlatformConfigFacade implements PlatformConfigFacade {
        private final Map<String, String> values = new LinkedHashMap<>();

        @Override
        public Optional<String> activeValue(String configKey) {
            return Optional.ofNullable(values.get(configKey));
        }

        @Override
        public void upsertAdminValue(String configKey, String configValue, String valueType, String configGroup, String remark) {
            values.put(configKey, configValue);
        }

        @Override
        public Map<String, String> activeValuesByGroup(String configGroup) {
            return values;
        }
    }

    private static final class FakeTreasuryCoverageFacade implements TreasuryCoverageFacade {
        private TreasuryCoverageSnapshot snapshot = new TreasuryCoverageSnapshot(new BigDecimal("110.00"), new BigDecimal("85.00"));

        @Override
        public TreasuryCoverageSnapshot snapshot() {
            return snapshot;
        }
    }
}

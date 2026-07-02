package ffdd.opsconsole.emergency.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import ffdd.opsconsole.emergency.dto.EmergencyDisableRequest;
import ffdd.opsconsole.emergency.dto.KillSwitchToggleRequest;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsKillSwitchServiceTest {
    private final FakeEmergencyControlRepository emergencyRepository = new FakeEmergencyControlRepository();
    private final FakeTreasuryCoverageFacade coverageFacade = new FakeTreasuryCoverageFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsKillSwitchService service = new OpsKillSwitchService(
            emergencyRepository,
            coverageFacade,
            auditLogService,
            ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction());

    @Test
    void matrixHasFiveActiveGatesAndRetiredSunsetGatesWithoutWritingConfig() {
        var result = service.matrix();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("activeGateCount", 5);
        assertThat(result.getData().get("retiredGates").toString()).contains("premium", "nexv2", "points");
        assertThat(emergencyRepository.settings).isEmpty();
    }

    @Test
    void disabledReadTimeSeedsExposeJ1EnumMatrixWithoutWritingConfig() {
        OpsKillSwitchService realOnlyService = new OpsKillSwitchService(
                emergencyRepository,
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
        assertThat(emergencyRepository.settings).isEmpty();
    }

    @Test
    void restoreBelowB1RedlineReturns422() {
        emergencyRepository.settings.put("killswitch.withdraw", "disabled");
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));

        var result = service.toggle(
                "withdraw",
                "idem-j1",
                new KillSwitchToggleRequest("enabled", "restore withdrawals", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
    }

    @Test
    void restoreTrialDoesNotRequireB1Redline() {
        emergencyRepository.settings.put("killswitch.trial", "disabled");
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));

        var result = service.toggle(
                "trial",
                "idem-j1-trial-restore",
                new KillSwitchToggleRequest("enabled", "restore trial benefit gate", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(emergencyRepository.settings).containsEntry("killswitch.trial", "enabled");
    }

    @Test
    void disableActiveGateWritesControlSettingAndAudit() {
        var result = service.toggle(
                "exchange",
                "idem-j1",
                new KillSwitchToggleRequest("disabled", "market incident", "risk-lead"));

        assertThat(result.getCode()).isZero();
        assertThat(emergencyRepository.settings).containsEntry("killswitch.exchange", "disabled");

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
        assertThat(emergencyRepository.settings)
                .containsEntry("killswitch.withdraw", "disabled")
                .containsEntry("killswitch.trial", "disabled");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
    }

    private static final class FakeEmergencyControlRepository implements EmergencyControlRepository {
        private final Map<String, String> settings = new LinkedHashMap<>();

        @Override
        public void ensureTables() {
        }

        @Override
        public List<Map<String, Object>> geoCountryPolicies() {
            return List.of();
        }

        @Override
        public void upsertGeoCountryPolicy(String countryCode, String countryName, String status, String reason, String operator) {
        }

        @Override
        public List<Map<String, Object>> geoEndpointCatalogs() {
            return List.of();
        }

        @Override
        public Optional<Map<String, Object>> geoEndpointCatalog(String endpointKey) {
            return Optional.empty();
        }

        @Override
        public List<Map<String, Object>> geoEndpointPolicies() {
            return List.of();
        }

        @Override
        public void replaceGeoEndpointPolicies(String endpointKey, String endpointPath, String label, String biz, String domain,
                                               List<String> countryCodes, String source, String reason, String operator) {
        }

        @Override
        public List<Map<String, Object>> geoHits() {
            return List.of();
        }

        @Override
        public Map<String, Integer> geoEndpointHits() {
            return Map.of();
        }

        @Override
        public List<Map<String, Object>> geoEdgeMetrics() {
            return List.of();
        }

        @Override
        public Optional<String> settingValue(String settingKey) {
            return Optional.ofNullable(settings.get(settingKey));
        }

        @Override
        public void upsertSetting(String settingKey, String settingValue, String valueType, String groupCode, String remark, String operator) {
            settings.put(settingKey, settingValue);
        }

        @Override
        public Map<String, Object> tamperTrend(LocalDateTime now) {
            return Map.of();
        }

        @Override
        public List<Map<String, Object>> tamperPaths() {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> tamperAccounts() {
            return List.of();
        }

        @Override
        public void createTamperReport(String reportId, String window, boolean masked, String status,
                                       Map<String, Object> payload, String operator, String reason) {
        }

        @Override
        public List<Map<String, Object>> playbooks() {
            return List.of();
        }

        @Override
        public Optional<Map<String, Object>> playbook(String code) {
            return Optional.empty();
        }

        @Override
        public void createPlaybook(String code, String name, String scene, boolean emergency, String sla, String state,
                                   String owner, String notifyCampaignNo, String notifyTemplate, String rollback,
                                   boolean drillRequired, boolean draft, List<Map<String, Object>> sequence,
                                   String operator) {
        }

        @Override
        public void updatePlaybook(String code, String name, String scene, Boolean emergency, String sla, String state,
                                   String owner, String notifyCampaignNo, String notifyTemplate, String rollback,
                                   Boolean drillRequired, String summary, List<Map<String, Object>> sequence,
                                   String operator) {
        }

        @Override
        public void markPlaybookDrilled(String code, LocalDateTime drillAt, String operator) {
        }

        @Override
        public Optional<Map<String, Object>> executionByIdempotencyKey(String code, String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public Optional<Map<String, Object>> execution(String executionId) {
            return Optional.empty();
        }

        @Override
        public List<Map<String, Object>> executions(int limit) {
            return List.of();
        }

        @Override
        public void createExecution(Map<String, Object> row) {
        }

        @Override
        public void markExecutionRolledBack(String executionId, LocalDateTime rollbackAt, String reason,
                                            List<Map<String, Object>> rollbackActions) {
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

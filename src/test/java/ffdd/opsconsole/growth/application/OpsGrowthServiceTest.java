package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.growth.dto.GrowthConfigUpdateRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsGrowthServiceTest {
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final FakeTreasuryCoverageFacade coverageFacade = new FakeTreasuryCoverageFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsGrowthService service =
            new OpsGrowthService(configFacade, coverageFacade, auditLogService, new ObjectMapper());

    @Test
    void checkInUsesNexAndKeepsPointsSunset() {
        ApiResult<Map<String, Object>> result = service.checkIn();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("rewardAsset", "NEX")
                .containsEntry("pointsSystemStatus", "SUNSET_HISTORY_ONLY");
        assertThat(result.getData().get("disabledOutputs").toString()).contains("Points ledger writes");
    }

    @Test
    void raisingCheckInRewardBelowB1RedlineReturns422() {
        configFacade.values.put("growth.checkin.reward_nex", "0.25");
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));

        ApiResult<Map<String, Object>> result = service.updateCheckIn(
                "idem-h5",
                new GrowthConfigUpdateRequest("baseRewardNex", "0.50", "raise reward", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
    }

    @Test
    void stricterWithdrawGateWritesCanonicalMirrorAndAudit() {
        configFacade.values.put("growth.withdraw_nex_gate.min_balance_nex", "100");

        ApiResult<Map<String, Object>> result = service.updateWithdrawGate(
                "idem-h1",
                new GrowthConfigUpdateRequest("minBalanceNex", "150", "tighten gate", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("growth.withdraw_nex_gate.min_balance_nex", "150")
                .containsEntry("withdrawal.nex_gate.min_balance_nex", "150");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("H1_WITHDRAW_NEX_GATE_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-h1");
    }

    @Test
    void retiredPhaseDialReturnsReadonly422() {
        ApiResult<Map<String, Object>> result = service.updatePhaseDial(
                "idem-h1",
                "withdrawPointsRatio",
                new GrowthConfigUpdateRequest("withdrawPointsRatio", "10", "old dial", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.PHASE_PARAM_READONLY.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.PHASE_PARAM_READONLY.name());
    }

    @Test
    void phaseOverviewHasEightActiveDialsAndSunsetExclusions() {
        ApiResult<Map<String, Object>> result = service.phases();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("dialCount", 8);
        assertThat(result.getData().get("sunsetExclusions"))
                .asList()
                .contains("Premium", "NEX v2", "Points");
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
    }

    private static final class FakeTreasuryCoverageFacade implements TreasuryCoverageFacade {
        private TreasuryCoverageSnapshot snapshot = new TreasuryCoverageSnapshot(new BigDecimal("110.00"), new BigDecimal("85.00"));

        @Override
        public TreasuryCoverageSnapshot snapshot() {
            return snapshot;
        }
    }
}

package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.team.dto.TeamCommissionConfigUpdateRequest;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsTeamServiceTest {
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final FakeTreasuryCoverageFacade coverageFacade = new FakeTreasuryCoverageFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsTeamService service = new OpsTeamService(configFacade, coverageFacade, auditLogService);

    @Test
    void overviewDeclaresSunsetExclusions() {
        ApiResult<Map<String, Object>> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("sunsetExclusions").toString())
                .contains("Premium")
                .contains("Points")
                .contains("NEX v2");
    }

    @Test
    void looseningCommissionBelowB1RedlineReturns422() {
        configFacade.values.put("team.direct_royalty_pct", "8");
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));

        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f2",
                new TeamCommissionConfigUpdateRequest("directRoyaltyPct", "10", "raise commission", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
    }

    @Test
    void tighteningPolicyWritesConfigAndAudit() {
        configFacade.values.put("team.min_payout_usdt", "20");

        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f2",
                new TeamCommissionConfigUpdateRequest("minPayoutUsdt", "30", "tighten payout", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("team.min_payout_usdt", "30");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("F_TEAM_POLICY_CHANGED");
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

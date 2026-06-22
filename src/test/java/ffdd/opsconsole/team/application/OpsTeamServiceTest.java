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
    @SuppressWarnings("unchecked")
    void overviewExposesF1ToF4PageReadModelsWithoutStaticFrontendAuthority() {
        ApiResult<Map<String, Object>> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat((java.util.List<Map<String, Object>>) result.getData().get("vrankRows"))
                .hasSize(13)
                .extracting(row -> row.get("v"))
                .contains("V0", "V12");
        assertThat((java.util.List<Map<String, Object>>) result.getData().get("fulfillmentQueues"))
                .extracting(row -> row.get("configKey"))
                .contains("F.fulfillment.V3.queue.status");
        assertThat((java.util.List<Map<String, Object>>) result.getData().get("unilevelRates"))
                .hasSize(7)
                .allSatisfy(row -> assertThat(row).containsKeys("level", "usdtPct", "nexReward", "configKey", "nexConfigKey"));
        assertThat((java.util.List<Map<String, Object>>) result.getData().get("policyParams"))
                .extracting(row -> row.get("key"))
                .contains("F.influence.clamp", "F.binary.matchRate", "F.pool.ratio");
        assertThat(result.getData().get("rateTiers").toString())
                .doesNotContain("Premium");
        assertThat((java.util.List<Map<String, Object>>) result.getData().get("binarySettlements"))
                .hasSizeGreaterThanOrEqualTo(4)
                .allSatisfy(row -> assertThat(row).containsKeys("user", "trackA", "trackB", "matchAmount", "todayPaid", "state", "tone"));
        Map<String, Object> leadershipPool = (Map<String, Object>) result.getData().get("leadershipPool");
        assertThat(leadershipPool)
                .containsKeys("quotaRows", "ambassadorBands", "podium", "voteWeights")
                .containsEntry("settlementWindow", "Sunday 23:59 UTC");
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

    @Test
    @SuppressWarnings("unchecked")
    void uiConfigWritesWhitelistedFKeyAndOverviewEchoesValue() {
        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f-ui",
                new TeamCommissionConfigUpdateRequest("F.binary.spillover", "已关闭", "close spillover", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("team.ui.F.binary.spillover", "已关闭");
        Map<String, String> configValues = (Map<String, String>) result.getData().get("configValues");
        assertThat(configValues).containsEntry("F.binary.spillover", "已关闭");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("F_TEAM_UI_CONFIG_CHANGED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fulfillmentQueueActionWritesWhitelistedBackendState() {
        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f-fulfillment",
                new TeamCommissionConfigUpdateRequest("F.fulfillment.V3.queue.status", "opened", "open prize queue", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("team.ui.F.fulfillment.V3.queue.status", "opened");
        Map<String, String> configValues = (Map<String, String>) result.getData().get("configValues");
        assertThat(configValues).containsEntry("F.fulfillment.V3.queue.status", "opened");
    }

    @Test
    @SuppressWarnings("unchecked")
    void commissionsExposeF5AuditRowsAndReflectBackendStatusOverrides() {
        service.updateConfig(
                "idem-f-commission-status",
                new TeamCommissionConfigUpdateRequest("F.commission.CM-7781.status", "frozen", "freeze abnormal commission", "risk-ops"));

        ApiResult<Map<String, Object>> result = service.commissions();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsKeys("summary", "commissionKinds", "commissionFilters", "commissionEvents", "statusDistribution", "recentAuditFeed");
        assertThat(result.getData().get("sources").toString())
                .contains("nx_team_commission_event")
                .contains("nx_config_item:team.ui.F.commission.*.status");
        assertThat(result.getData().get("pagination").toString()).contains("server-pageable");
        var events = (java.util.List<Map<String, Object>>) result.getData().get("commissionEvents");
        assertThat(events).hasSizeGreaterThanOrEqualTo(12);
        assertThat(events)
                .filteredOn(event -> "CM-7781".equals(event.get("id")))
                .singleElement()
                .satisfies(event -> assertThat(event).containsEntry("state", "frozen"));
    }

    @Test
    void sunsetUiConfigKeyIsRejected() {
        try {
            service.updateConfig(
                    "idem-f-ui",
                    new TeamCommissionConfigUpdateRequest("F.points.enabled", "true", "restore points", "superadmin"));
        } catch (IllegalArgumentException ex) {
            assertThat(ex).hasMessageContaining("Sunset");
            return;
        }
        throw new AssertionError("Expected sunset capability rejection");
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

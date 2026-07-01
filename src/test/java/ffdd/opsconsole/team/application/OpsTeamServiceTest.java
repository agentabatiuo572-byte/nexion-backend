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
import ffdd.opsconsole.team.dto.VRankRewardRequest;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsTeamServiceTest {
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final FakeTreasuryCoverageFacade coverageFacade = new FakeTreasuryCoverageFacade();
    private final FakeTreasuryLedgerPostingFacade ledgerPostingFacade = new FakeTreasuryLedgerPostingFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsTeamService service = new OpsTeamService(
            configFacade,
            coverageFacade,
            ledgerPostingFacade,
            auditLogService,
            ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction());

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
        assertThat((java.util.List<Map<String, Object>>) result.getData().get("vrankRows")).isEmpty();
        assertThat((java.util.List<Map<String, Object>>) result.getData().get("fulfillmentQueues")).isEmpty();
        assertThat((java.util.List<Map<String, Object>>) result.getData().get("unilevelRates")).isEmpty();
        assertThat((java.util.List<Map<String, Object>>) result.getData().get("policyParams"))
                .extracting(row -> row.get("key"))
                .contains("F.influence.clampMin", "F.influence.clampMax", "F.binary.matchRate", "F.pool.ratio");
        assertThat(result.getData().get("rateTiers").toString())
                .doesNotContain("Premium");
        assertThat((java.util.List<Map<String, Object>>) result.getData().get("binarySettlements")).isEmpty();
        Map<String, Object> leadershipPool = (Map<String, Object>) result.getData().get("leadershipPool");
        assertThat(leadershipPool)
                .containsKeys("quotaRows", "ambassadorBands", "podium", "voteWeights")
                .containsEntry("settlementWindow", "");
    }

    @Test
    void binaryReadsCurrentRhythmFromH1() {
        configFacade.values.put("H1.rhythm.currentMonth", "10");
        configFacade.values.put("growth.phase.current", "P5");
        configFacade.values.put("growth.phase.commission_tightening_pct", "0.25");

        ApiResult<Map<String, Object>> result = service.binary();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("dailyCap"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("currentMonth", 10)
                .containsEntry("currentPhase", "P5")
                .containsEntry("h1CommissionTighteningPct", new BigDecimal("25"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void disabledReadTimeSeedsDoNotExposeFDomainBusinessDefaults() {
        OpsTeamService realOnlyService = new OpsTeamService(
                new FakePlatformConfigFacade(),
                coverageFacade,
                ledgerPostingFacade,
                mock(AuditLogService.class),
                ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        ApiResult<Map<String, Object>> rates = realOnlyService.rates();
        ApiResult<Map<String, Object>> pool = realOnlyService.leadershipPool();

        assertThat(rates.getCode()).isZero();
        assertThat((List<Map<String, Object>>) rates.getData().get("metrics")).isEmpty();
        assertThat((List<Map<String, Object>>) rates.getData().get("unilevelRates")).isEmpty();
        assertThat((List<Map<String, Object>>) rates.getData().get("rateTiers")).isEmpty();
        assertThat((List<Map<String, Object>>) rates.getData().get("policyParams"))
                .allSatisfy(row -> assertThat(row)
                        .containsEntry("value", "")
                        .containsEntry("defaultValue", ""));

        assertThat(pool.getCode()).isZero();
        assertThat(pool.getData())
                .containsEntry("poolRatio", "")
                .containsEntry("poolRatioValue", BigDecimal.ZERO)
                .containsEntry("weeklyGmvUsd", 0)
                .containsEntry("weeklyInjectedUsd", 0)
                .containsEntry("monthlyCapUsd", 0)
                .containsEntry("settlementWindow", "");
        assertThat((List<Map<String, Object>>) pool.getData().get("quotaRows")).isEmpty();
        assertThat((List<Map<String, Object>>) pool.getData().get("ambassadorBands")).isEmpty();
        assertThat((List<Map<String, Object>>) pool.getData().get("podium")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void ranksReturnEmptyReadModelWhenDatabaseIsEmpty() {
        ApiResult<Map<String, Object>> result = service.ranks();

        assertThat(result.getCode()).isZero();
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.getData().get("vrankRows");
        assertThat(rows).isEmpty();
        assertThat(result.getData().get("sources").toString())
                .contains("nx_config_item:team.ui.F.vrank.*")
                .contains("nx_config_item:team.ui.F.vrank.rewards.*");
        assertThat(result.getData().toString())
                .doesNotContain("Apple Watch")
                .doesNotContain("Porsche");
        assertThat(configFacade.values).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void ratesReturnEmptyReadModelWhenDatabaseIsEmpty() {
        ApiResult<Map<String, Object>> result = service.rates();

        assertThat(result.getCode()).isZero();
        assertThat((List<Map<String, Object>>) result.getData().get("metrics")).isEmpty();
        assertThat((List<Map<String, Object>>) result.getData().get("unilevelRates")).isEmpty();
        assertThat((List<Map<String, Object>>) result.getData().get("rateTiers")).isEmpty();
        assertThat((List<Map<String, Object>>) result.getData().get("policyParams"))
                .extracting(row -> row.get("key"))
                .contains("F.influence.clampMin", "F.influence.clampMax", "F.promo.weekMultiplier", "F.peer.rate");
        assertThat(result.getData().get("sources").toString())
                .contains("nx_config_item:team.ui.F.unilevel.*")
                .contains("nx_config_item:team.ui.F.rateTier.*")
                .contains("nx_config_item:team.ui.F2.metric.*");
        assertThat(configFacade.values).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void binaryReturnsEmptyReadModelWhenDatabaseIsEmpty() {
        ApiResult<Map<String, Object>> result = service.binary();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("domain", "F3");
        assertThat((List<Map<String, Object>>) result.getData().get("metrics")).isEmpty();
        assertThat((List<Map<String, Object>>) result.getData().get("settlements")).isEmpty();
        Map<String, Object> config = (Map<String, Object>) result.getData().get("config");
        assertThat(config)
                .containsEntry("threshold", "")
                .containsEntry("matchRate", "")
                .containsEntry("spillover", "")
                .containsEntry("settlePeriod", "")
                .containsEntry("residualPolicy", "");
        assertThat(result.getData().get("sources").toString())
                .contains("nx_config_item:team.ui.F3.binary.rows")
                .contains("nx_config_item:team.ui.F.binary.*")
                .contains("B1 treasury coverage facade");
        assertThat(configFacade.values).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void leadershipPoolReturnsEmptyReadModelWhenDatabaseIsEmpty() {
        ApiResult<Map<String, Object>> result = service.leadershipPool();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("domain", "F4");
        assertThat((List<Map<String, Object>>) result.getData().get("metrics")).isEmpty();
        assertThat(result.getData())
                .containsEntry("poolRatio", "")
                .containsEntry("weeklyGmvUsd", 0)
                .containsEntry("weeklyInjectedUsd", 0)
                .containsEntry("participantCount", 0)
                .containsEntry("settlementWindow", "");
        assertThat((List<Map<String, Object>>) result.getData().get("quotaRows")).isEmpty();
        assertThat((List<Map<String, Object>>) result.getData().get("ambassadorBands")).isEmpty();
        assertThat((List<Map<String, Object>>) result.getData().get("podium")).isEmpty();
        assertThat((List<Map<String, Object>>) result.getData().get("voteWeights")).isEmpty();
        assertThat(result.getData().get("sources").toString())
                .contains("nx_config_item:team.ui.F4.*")
                .contains("nx_config_item:team.ui.F.pool.*");
        assertThat(configFacade.values).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateVRankThresholdWritesFieldConfigAndAudit() {
        ApiResult<Map<String, Object>> result = service.updateVRankThreshold(
                "V3",
                "teamGv",
                "idem-f1-threshold",
                new TeamCommissionConfigUpdateRequest(null, "$25k", "raise v3 team gate", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("team.ui.F.vrank.V3.teamGv", "$25k");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.getData().get("vrankRows");
        assertThat(rows)
                .filteredOn(row -> "V3".equals(row.get("v")))
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("teamGv", "$25k"));
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("F_TEAM_VRANK_THRESHOLD_CHANGED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void vRankRewardCrudPersistsBackendConfig() {
        ApiResult<Map<String, Object>> added = service.addVRankReward(
                "V3",
                "idem-f1-add",
                new VRankRewardRequest("usdt", new BigDecimal("100"), null, null, null, "add usdt reward", "superadmin"));

        assertThat(added.getCode()).isZero();
        assertThat(configFacade.values.get("team.ui.F.vrank.rewards.V3")).contains("\"type\":\"usdt\"");
        Map<String, List<Map<String, Object>>> rewards = (Map<String, List<Map<String, Object>>>) added.getData().get("rewards");
        String rewardId = rewards.get("V3").stream()
                .filter(item -> "usdt".equals(item.get("type")))
                .map(item -> String.valueOf(item.get("id")))
                .findFirst()
                .orElseThrow();

        ApiResult<Map<String, Object>> updated = service.updateVRankReward(
                "V3",
                rewardId,
                "idem-f1-update",
                new VRankRewardRequest("nex", new BigDecimal("1200"), null, null, null, "change reward type", "superadmin"));
        assertThat(updated.getCode()).isZero();
        assertThat(configFacade.values.get("team.ui.F.vrank.rewards.V3")).contains("\"type\":\"nex\"");

        ApiResult<Map<String, Object>> removed = service.removeVRankReward(
                "V3",
                rewardId,
                "idem-f1-remove",
                new VRankRewardRequest(null, null, null, null, null, "remove reward", "superadmin"));
        assertThat(removed.getCode()).isZero();
        Map<String, List<Map<String, Object>>> afterRemove = (Map<String, List<Map<String, Object>>>) removed.getData().get("rewards");
        assertThat(afterRemove.get("V3")).noneSatisfy(item -> assertThat(item).containsEntry("id", rewardId));
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
    void updateF2UiConfigWritesBackendStateAndOverviewEchoesValue() {
        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f2-unilevel",
                new TeamCommissionConfigUpdateRequest("F.unilevel.L1", "11%", "raise direct rate", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("team.ui.F.unilevel.L1", "11%");
        List<Map<String, Object>> rates = (List<Map<String, Object>>) result.getData().get("unilevelRates");
        assertThat(rates)
                .filteredOn(row -> "L1".equals(row.get("level")))
                .singleElement()
                .satisfies(row -> assertThat(row.get("usdtPct").toString()).isEqualTo("11"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateF3UiConfigWritesBackendStateAndBinaryEchoesValue() {
        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f3-binary-period",
                new TeamCommissionConfigUpdateRequest("F.binary.settlePeriod", "每周", "change binary period", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("team.ui.F.binary.settlePeriod", "每周");
        Map<String, Object> config = (Map<String, Object>) service.binary().getData().get("config");
        assertThat(config).containsEntry("settlePeriod", "每周");
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateF4UiConfigWritesBackendStateAndLeadershipPoolEchoesValue() {
        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f4-pool-ratio",
                new TeamCommissionConfigUpdateRequest("F.pool.ratio", "6%", "raise leadership pool", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("team.ui.F.pool.ratio", "6%");
        Map<String, Object> pool = service.leadershipPool().getData();
        assertThat(pool)
                .containsEntry("poolRatio", "6%")
                .containsEntry("weeklyInjectedUsd", 0);
        Map<String, Object> config = (Map<String, Object>) pool.get("config");
        assertThat(config).containsEntry("poolRatio", "6%");
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
        configFacade.values.put(
                "team.ui.F5.commission.events",
                """
                        [{"id":"CM-7781","kind":"network","user":"usr_19C7","amount":420,"currency":"USDT","cooldownPercent":60,"cooldownLabel":"冷却 18d","state":"计提"}]
                        """);

        service.updateConfig(
                "idem-f-commission-status",
                new TeamCommissionConfigUpdateRequest("F.commission.CM-7781.status", "frozen", "freeze abnormal commission", "risk-ops"));

        assertThat(ledgerPostingFacade.entries).hasSize(1);
        assertThat(ledgerPostingFacade.entries.get(0))
                .containsEntry("bizNo", "F5-COMMISSION-CM-7781-FROZEN")
                .containsEntry("bizType", "TEAM_COMMISSION")
                .containsEntry("asset", "USDT")
                .containsEntry("direction", "OUT")
                .containsEntry("status", "SUCCESS");

        ApiResult<Map<String, Object>> result = service.commissions();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsKeys("summary", "commissionKinds", "commissionFilters", "commissionEvents", "statusDistribution", "recentAuditFeed");
        assertThat(result.getData().get("sources").toString())
                .contains("nx_config_item:team.ui.F5.commission.*")
                .contains("nx_config_item:team.ui.F.commission.*.status");
        assertThat(result.getData().get("pagination").toString()).contains("server-pageable");
        assertThat(configFacade.values)
                .containsKeys(
                        "team.ui.F5.commission.events",
                        "team.ui.F.commission.CM-7781.status");
        var events = (java.util.List<Map<String, Object>>) result.getData().get("commissionEvents");
        assertThat(events).hasSize(1);
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

    private static final class FakeTreasuryLedgerPostingFacade implements TreasuryLedgerPostingFacade {
        private final List<Map<String, Object>> entries = new ArrayList<>();

        @Override
        public void postLedgerEntry(String bizNo, Long userId, String bizType, String asset, String direction,
                                    BigDecimal amount, String status, String remark) {
            entries.add(Map.of(
                    "bizNo", bizNo,
                    "userId", userId,
                    "bizType", bizType,
                    "asset", asset,
                    "direction", direction,
                    "amount", amount,
                    "status", status,
                    "remark", remark));
        }
    }
}

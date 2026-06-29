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
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
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
                .contains("F.influence.clampMin", "F.influence.clampMax", "F.binary.matchRate", "F.pool.ratio");
        assertThat(result.getData().get("rateTiers").toString())
                .doesNotContain("Premium");
        assertThat((java.util.List<Map<String, Object>>) result.getData().get("binarySettlements"))
                .hasSizeGreaterThanOrEqualTo(4)
                .allSatisfy(row -> assertThat(row).containsKeys("user", "trackA", "trackB", "matchAmount", "todayPaid", "state", "tone"));
        Map<String, Object> leadershipPool = (Map<String, Object>) result.getData().get("leadershipPool");
        assertThat(leadershipPool)
                .containsKeys("quotaRows", "ambassadorBands", "podium", "voteWeights")
                .containsEntry("settlementWindow", "周日 23:59 UTC");
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
    void ranksSeedsF1ReadModelIntoConfigWhenDatabaseIsEmpty() {
        ApiResult<Map<String, Object>> result = service.ranks();

        assertThat(result.getCode()).isZero();
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.getData().get("vrankRows");
        assertThat(rows).hasSize(13);
        assertThat(rows)
                .filteredOn(row -> "V3".equals(row.get("v")))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("teamGv", "$20k")
                        .containsEntry("legCount", "2")
                        .containsEntry("legRank", "V1")
                        .containsKey("rewards"));
        assertThat(result.getData().get("sources").toString())
                .contains("nx_config_item:team.ui.F.vrank.*")
                .contains("nx_config_item:team.ui.F.vrank.rewards.*");
        assertThat(result.getData().toString())
                .doesNotContain("Apple Watch")
                .doesNotContain("Porsche");
        assertThat(configFacade.values)
                .containsEntry("team.ui.F.vrank.V3.teamGv", "$20k")
                .containsEntry("team.ui.F.vrank.V3.legRank", "V1")
                .containsKey("team.ui.F.vrank.rewards.V3");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ratesSeedsF2ReadModelIntoConfigWhenDatabaseIsEmpty() {
        ApiResult<Map<String, Object>> result = service.rates();

        assertThat(result.getCode()).isZero();
        assertThat((List<Map<String, Object>>) result.getData().get("metrics"))
                .hasSize(4)
                .extracting(row -> row.get("id"))
                .contains("l1TriggerRate", "weeklyUsdtRoyalty");
        assertThat((List<Map<String, Object>>) result.getData().get("unilevelRates"))
                .hasSize(7)
                .filteredOn(row -> "L1".equals(row.get("level")))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("label", "直推 DIRECT")
                        .containsEntry("configKey", "F.unilevel.L1")
                        .containsEntry("nexConfigKey", "F.unilevel.nex.L1"));
        assertThat((List<Map<String, Object>>) result.getData().get("rateTiers"))
                .hasSize(4)
                .extracting(row -> row.get("name"))
                .containsExactly("Standard", "Verified", "Elite", "Diamond");
        assertThat((List<Map<String, Object>>) result.getData().get("policyParams"))
                .extracting(row -> row.get("key"))
                .contains("F.influence.clampMin", "F.influence.clampMax", "F.promo.weekMultiplier", "F.peer.rate");
        assertThat(result.getData().get("sources").toString())
                .contains("nx_config_item:team.ui.F.unilevel.*")
                .contains("nx_config_item:team.ui.F.rateTier.*")
                .contains("nx_config_item:team.ui.F2.metric.*");
        assertThat(configFacade.values)
                .containsEntry("team.ui.F.unilevel.L1", "10")
                .containsEntry("team.ui.F.unilevel.nex.L1", "50")
                .containsEntry("team.ui.F.influence.clampMin", "1.0")
                .containsEntry("team.ui.F.rateTier.Elite.rate", "12%")
                .containsEntry("team.ui.F2.metric.weeklyUsdtRoyalty.value", "$182k");
    }

    @Test
    @SuppressWarnings("unchecked")
    void binarySeedsF3ReadModelIntoConfigWhenDatabaseIsEmpty() {
        ApiResult<Map<String, Object>> result = service.binary();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("domain", "F3");
        assertThat((List<Map<String, Object>>) result.getData().get("metrics"))
                .hasSize(4)
                .extracting(row -> row.get("id"))
                .contains("todayBalanceMatch", "residualPool");
        assertThat((List<Map<String, Object>>) result.getData().get("settlements"))
                .hasSize(30)
                .filteredOn(row -> "usr_31E8".equals(row.get("user")))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("trackA", 84000)
                        .containsEntry("trackB", 62000)
                        .containsEntry("matchAmount", 6200)
                        .containsEntry("todayPaid", 1500));
        Map<String, Object> config = (Map<String, Object>) result.getData().get("config");
        assertThat(config)
                .containsEntry("threshold", "$1,000 / 轨")
                .containsEntry("matchRate", "10%")
                .containsEntry("spillover", "已启用")
                .containsEntry("settlePeriod", "每月")
                .containsEntry("residualPolicy", "每月清零");
        assertThat(result.getData().get("sources").toString())
                .contains("nx_config_item:team.ui.F3.binary.rows")
                .contains("nx_config_item:team.ui.F.binary.*")
                .contains("B1 treasury coverage facade");
        assertThat(configFacade.values)
                .containsEntry("team.ui.F.binary.threshold", "$1,000 / 轨")
                .containsEntry("team.ui.F.binary.matchRate", "10%")
                .containsEntry("team.ui.F.binary.settlePeriod", "每月")
                .containsEntry("team.ui.F.binary.residualPolicy", "每月清零")
                .containsKey("team.ui.F3.binary.rows")
                .containsEntry("team.ui.F3.metric.todayBalanceMatch.value", "$10,490");
    }

    @Test
    @SuppressWarnings("unchecked")
    void leadershipPoolSeedsF4ReadModelIntoConfigWhenDatabaseIsEmpty() {
        ApiResult<Map<String, Object>> result = service.leadershipPool();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("domain", "F4");
        assertThat((List<Map<String, Object>>) result.getData().get("metrics"))
                .hasSize(4)
                .extracting(row -> row.get("id"))
                .contains("weeklyLeadershipPool", "hardwareQuotaRemaining", "ambassadorPending", "leaderboardFraudHits");
        assertThat(result.getData())
                .containsEntry("poolRatio", "5%")
                .containsEntry("weeklyGmvUsd", 9746420)
                .containsEntry("weeklyInjectedUsd", 487321)
                .containsEntry("participantCount", 496)
                .containsEntry("settlementWindow", "周日 23:59 UTC");
        assertThat((List<Map<String, Object>>) result.getData().get("quotaRows"))
                .hasSize(2)
                .filteredOn(row -> "Pro".equals(row.get("name")))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("current", 48)
                        .containsEntry("cap", 70));
        assertThat((List<Map<String, Object>>) result.getData().get("ambassadorBands"))
                .extracting(row -> row.get("name"))
                .containsExactly("KOL", "EVENT", "AD", "LOCAL");
        assertThat((List<Map<String, Object>>) result.getData().get("podium"))
                .extracting(row -> row.get("userId"))
                .contains("usr_31E8", "usr_55B1");
        assertThat((List<Map<String, Object>>) result.getData().get("voteWeights"))
                .filteredOn(row -> "V12".equals(row.get("v")))
                .singleElement()
                .satisfies(row -> assertThat(row.get("votes").toString()).isEqualTo("512"));
        assertThat(result.getData().get("sources").toString())
                .contains("nx_config_item:team.ui.F4.*")
                .contains("nx_config_item:team.ui.F.pool.*");
        assertThat(configFacade.values)
                .containsEntry("team.ui.F.pool.ratio", "5%")
                .containsEntry("team.ui.F.pool.monthlyCap", "$2,600,000")
                .containsEntry("team.ui.F.leaderboard.poolUsd", "$48,000")
                .containsKey("team.ui.F4.quota.rows")
                .containsKey("team.ui.F4.ambassador.bands")
                .containsKey("team.ui.F4.leaderboard.podium");
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
                .containsEntry("weeklyInjectedUsd", 584785);
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
        service.updateConfig(
                "idem-f-commission-status",
                new TeamCommissionConfigUpdateRequest("F.commission.CM-7781.status", "frozen", "freeze abnormal commission", "risk-ops"));

        ApiResult<Map<String, Object>> result = service.commissions();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsKeys("summary", "commissionKinds", "commissionFilters", "commissionEvents", "statusDistribution", "recentAuditFeed");
        assertThat(result.getData().get("sources").toString())
                .contains("nx_config_item:team.ui.F5.commission.*")
                .contains("nx_config_item:team.ui.F.commission.*.status");
        assertThat(result.getData().get("pagination").toString()).contains("server-pageable");
        assertThat(configFacade.values)
                .containsKeys(
                        "team.ui.F5.commission.kinds",
                        "team.ui.F5.commission.filters",
                        "team.ui.F5.commission.events",
                        "team.ui.F5.commission.auditFeed",
                        "team.ui.F5.commission.monthlySpendLabel",
                        "team.ui.F5.commission.coolingBalanceLabel",
                        "team.ui.F5.commission.withdrawableThisMonthLabel",
                        "team.ui.F.commission.CM-7781.status");
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

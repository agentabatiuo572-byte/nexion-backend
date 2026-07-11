package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.domain.TeamFulfillmentQueueRepository;
import ffdd.opsconsole.team.domain.TeamFulfillmentQueueRow;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsTeamServiceTest {
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final FakeTreasuryCoverageFacade coverageFacade = new FakeTreasuryCoverageFacade();
    private final FakeTreasuryLedgerPostingFacade ledgerPostingFacade = new FakeTreasuryLedgerPostingFacade();
    private final FakeTeamFulfillmentQueueRepository fulfillmentQueueRepository = new FakeTeamFulfillmentQueueRepository();
    private final FakeTeamCommissionRepository commissionRepository = new FakeTeamCommissionRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final ffdd.opsconsole.shared.security.AdminPermissionCache permissionCache = mock(ffdd.opsconsole.shared.security.AdminPermissionCache.class);
    private final OpsTeamService service = new OpsTeamService(
            configFacade,
            coverageFacade,
            ledgerPostingFacade,
            auditLogService,
            ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
            fulfillmentQueueRepository,
            commissionRepository,
            permissionCache,
            mock(ffdd.opsconsole.platform.mapper.AuditObjectLockMapper.class));

    @BeforeEach
    void seedPermissionContext() {
        // updateConfig service 层二次校验需 admin id + 权限码;默认 stub 全 network_f2/f3/f4 码通过
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(1L, null, List.of()));
        org.mockito.Mockito.when(permissionCache.getPermissionCodes(org.mockito.ArgumentMatchers.anyLong())).thenReturn(java.util.Set.of(
                "network_f2_read", "network_f2_write", "network_f2_royalty_rate", "network_f2_policy_amplify",
                "network_f3_read", "network_f3_write", "network_f3_match_rate",
                "network_f4_read", "network_f4_write", "network_f4_pool_fund"));
    }

    @Test
    void overviewDeclaresSunsetExclusions() {
        configFacade.values.put("team.ui.F.sunset.exclusions",
                "Premium rank unlock, Points commission payout; NEX v2 lock reward");

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
        assertThat((java.util.List<String>) result.getData().get("sunsetExclusions")).isEmpty();
        assertThat((java.util.List<Map<String, Object>>) result.getData().get("binarySettlements")).isEmpty();
        Map<String, Object> leadershipPool = (Map<String, Object>) result.getData().get("leadershipPool");
        assertThat(leadershipPool)
                .containsKeys("quotaRows", "ambassadorBands", "podium", "voteWeights")
                .containsEntry("settlementWindow", "");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fulfillmentQueuesComeFromBusinessRepositoryRows() {
        fulfillmentQueueRepository.rows.add(new TeamFulfillmentQueueRow("V3", "Pro hardware reward", "PENDING", 2L));

        ApiResult<Map<String, Object>> result = service.overview();

        assertThat(result.getCode()).isZero();
        List<Map<String, Object>> queues = (List<Map<String, Object>>) result.getData().get("fulfillmentQueues");
        assertThat(queues).containsExactly(Map.of(
                "v", "V3",
                "name", "Pro hardware reward",
                "count", 2L,
                "status", "PENDING",
                "configKey", "F.fulfillment.V3.queue.status",
                "source", "nx_v_rank_reward_fulfillment"));
        assertThat(configFacade.values).doesNotContainKey("team.ui.F.fulfillment.queues");
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
                ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.disabledForDirectConstruction(),
                new FakeTeamFulfillmentQueueRepository(),
                new FakeTeamCommissionRepository(),
                permissionCache,
                mock(ffdd.opsconsole.platform.mapper.AuditObjectLockMapper.class));

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
                .contains("nx_v_rank_config")
                .contains("nx_team_member")
                .contains("nx_v_rank_reward_rule");
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
                .contains("nx_commission_rule:UNILEVEL")
                .contains("nx_v_rank_config")
                .contains("nx_commission_event");
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
                .contains("nx_binary_commission_settlement")
                .doesNotContain("nx_config_item:team.ui.F.binary.*")
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
                .contains("nx_team_hardware_quota_tier")
                .contains("nx_team_ambassador_application")
                .contains("nx_team_leaderboard_action");
        assertThat(configFacade.values).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void configValuesDoNotExposeLegacyFBusinessStateKeys() {
        configFacade.values.put("team.ui.F3.dailyCap.currentMonth", "6");
        configFacade.values.put("team.ui.F3.dailyCap.currentPhase", "收缩期");
        configFacade.values.put("team.ui.F.quota.monthlyStock", "96 台");
        configFacade.values.put("team.ui.F.ambassador.q3-2025.status", "pending");
        configFacade.values.put("team.ui.F.leaderboard.poolUsd", "$48,000");
        configFacade.values.put("team.ui.F.leaderboard.period.status", "active");

        ApiResult<Map<String, Object>> result = service.leadershipPool();

        assertThat(result.getCode()).isZero();
        Map<String, String> values = (Map<String, String>) result.getData().get("configValues");
        assertThat(values).doesNotContainKeys(
                "F3.dailyCap.currentMonth",
                "F3.dailyCap.currentPhase",
                "F.quota.monthlyStock",
                "F.ambassador.q3-2025.status",
                "F.leaderboard.poolUsd",
                "F.leaderboard.period.status");
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateVRankThresholdWritesBusinessTableAndAudit() {
        commissionRepository.vRankRows.add(new LinkedHashMap<>(Map.of(
                "v", "V3",
                "label", "Builder",
                "selfBuyUsd", BigDecimal.ZERO,
                "directRefs", 0,
                "teamGvUsd", new BigDecimal("20000"),
                "legCount", 2,
                "legRank", "V1",
                "votes", 4,
                "physicalReward", "",
                "pop", 12)));

        ApiResult<Map<String, Object>> result = service.updateVRankThreshold(
                "V3",
                "teamGv",
                "idem-f1-threshold",
                new TeamCommissionConfigUpdateRequest(null, "$25k", "raise v3 team gate", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).doesNotContainKey("team.ui.F.vrank.V3.teamGv");
        assertThat(commissionRepository.vRankRows.get(0)).containsEntry("teamGvUsd", new BigDecimal("25000"));
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
    void vRankRewardCrudPersistsBusinessTable() {
        ApiResult<Map<String, Object>> added = service.addVRankReward(
                "V3",
                "idem-f1-add",
                new VRankRewardRequest("usdt", new BigDecimal("100"), null, null, null, "add usdt reward", "superadmin"));

        assertThat(added.getCode()).isZero();
        assertThat(configFacade.values).doesNotContainKey("team.ui.F.vrank.rewards.V3");
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
        assertThat(commissionRepository.vRankRewards.get("V3"))
                .filteredOn(item -> rewardId.equals(item.get("id")))
                .singleElement()
                .satisfies(item -> assertThat(item).containsEntry("type", "nex"));

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
        commissionRepository.unilevelRates.add(new LinkedHashMap<>(Map.of(
                "level", "L1",
                "usdtPct", new BigDecimal("10"),
                "nexReward", new BigDecimal("50"),
                "label", "直推 DIRECT",
                "direct", true)));

        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f2-unilevel",
                new TeamCommissionConfigUpdateRequest("F.unilevel.L1", "11%", "raise direct rate", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).doesNotContainKey("team.ui.F.unilevel.L1");
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
        commissionRepository.commissionEvents.add(new LinkedHashMap<>(Map.of(
                "id", "CM-7781",
                "kind", "network",
                "user", "U00001987",
                "amount", new BigDecimal("420"),
                "currency", "USDT",
                "cooldownPercent", 60,
                "cooldownLabel", "冷却 18d",
                "state", "计提")));
        commissionRepository.kindSummary.add(new LinkedHashMap<>(Map.of(
                "key", "network",
                "code", "NETWORK",
                "label", "网络版税",
                "amountLabel", "$420",
                "countLabel", "1 笔",
                "className", "k-network",
                "amountColor", "")));
        commissionRepository.auditFeed.add(new LinkedHashMap<>(Map.of(
                "when", "2026-07-01 10:00",
                "text", "佣金事件 CM-7781 状态 PENDING",
                "level", "LOW")));

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
                .contains("nx_commission_event")
                .contains("nx_wallet_ledger");
        assertThat(result.getData().get("pagination").toString()).contains("server-pageable");
        assertThat(configFacade.values)
                .doesNotContainKeys(
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

    private static final class FakeTeamFulfillmentQueueRepository implements TeamFulfillmentQueueRepository {
        private final List<TeamFulfillmentQueueRow> rows = new ArrayList<>();

        @Override
        public List<TeamFulfillmentQueueRow> fulfillmentQueues() {
            return rows;
        }
    }

    private static final class FakeTeamCommissionRepository implements TeamCommissionRepository {
        private final List<Map<String, Object>> binarySettlements = new ArrayList<>();
        private final List<Map<String, Object>> vRankRows = new ArrayList<>();
        private final List<Map<String, Object>> f2Metrics = new ArrayList<>();
        private final List<Map<String, Object>> unilevelRates = new ArrayList<>();
        private final List<Map<String, Object>> rateTiers = new ArrayList<>();
        private final Map<String, List<Map<String, Object>>> vRankRewards = new LinkedHashMap<>();
        private final Map<String, Object> leadershipPoolSummary = new LinkedHashMap<>();
        private final List<Map<String, Object>> leadershipRanks = new ArrayList<>();
        private final List<Map<String, Object>> quotaRows = new ArrayList<>();
        private final List<Map<String, Object>> ambassadorBands = new ArrayList<>();
        private final Map<String, Object> ambassadorSummary = new LinkedHashMap<>();
        private final List<Map<String, Object>> leaderboardPodium = new ArrayList<>();
        private final Map<String, Object> leaderboardSummary = new LinkedHashMap<>();
        private final List<Map<String, Object>> commissionEvents = new ArrayList<>();
        private final List<Map<String, Object>> kindSummary = new ArrayList<>();
        private final List<Map<String, Object>> auditFeed = new ArrayList<>();

        @Override
        public List<Map<String, Object>> binarySettlements(int limit) {
            return binarySettlements.stream().limit(limit).toList();
        }

        @Override
        public Map<String, Object> binarySettlementSummary() {
            int participantCount = (int) binarySettlements.stream()
                    .map(row -> row.get("user"))
                    .distinct()
                    .count();
            int monthlyMatchedUsd = binarySettlements.stream()
                    .mapToInt(row -> ((Number) row.getOrDefault("matchAmount", 0)).intValue())
                    .sum();
            int maxTrackGmv = binarySettlements.stream()
                    .mapToInt(row -> Math.max(
                            ((Number) row.getOrDefault("trackA", 0)).intValue(),
                            ((Number) row.getOrDefault("trackB", 0)).intValue()))
                    .max()
                    .orElse(0);
            int residualPoolUsd = binarySettlements.stream()
                    .mapToInt(row -> Math.max(
                            ((Number) row.getOrDefault("trackA", 0)).intValue(),
                            ((Number) row.getOrDefault("trackB", 0)).intValue())
                            - ((Number) row.getOrDefault("matchAmount", 0)).intValue())
                    .sum();
            return Map.of(
                    "participantCount", participantCount,
                    "blockedCount", 0,
                    "monthlyMatchedUsd", monthlyMatchedUsd,
                    "dailyMatchUsd", monthlyMatchedUsd,
                    "autoPlacement7dCount", 0,
                    "maxTrackGmv", maxTrackGmv,
                    "residualPoolUsd", residualPoolUsd);
        }

        @Override
        public List<Map<String, Object>> vRankRows() {
            return vRankRows;
        }

        @Override
        public boolean updateVRankThreshold(String rank, String field, Object value) {
            for (Map<String, Object> row : vRankRows) {
                if (rank.equals(row.get("v"))) {
                    switch (field) {
                        case "selfBuy" -> row.put("selfBuyUsd", value);
                        case "directRefs" -> row.put("directRefs", value);
                        case "teamGv" -> row.put("teamGvUsd", value);
                        case "legCount" -> row.put("legCount", value);
                        case "legRank" -> row.put("legRank", value);
                        default -> {
                            return false;
                        }
                    }
                    return true;
                }
            }
            return false;
        }

        @Override
        public List<Map<String, Object>> f2Metrics() {
            return f2Metrics;
        }

        @Override
        public List<Map<String, Object>> unilevelRates() {
            return unilevelRates;
        }

        @Override
        public boolean updateUnilevelRule(int layerNo, String field, Object value) {
            String level = "L" + layerNo;
            for (Map<String, Object> row : unilevelRates) {
                if (level.equals(row.get("level"))) {
                    if ("usdtRate".equals(field)) {
                        row.put("usdtPct", ((BigDecimal) value).multiply(new BigDecimal("100")).stripTrailingZeros());
                        return true;
                    }
                    if ("nexPerUsd".equals(field)) {
                        row.put("nexReward", value);
                        return true;
                    }
                    return false;
                }
            }
            return false;
        }

        @Override
        public List<Map<String, Object>> rateTiers() {
            return rateTiers;
        }

        @Override
        public List<Map<String, Object>> vRankRewards(String rank) {
            return vRankRewards.getOrDefault(rank, List.of());
        }

        @Override
        public boolean addVRankReward(String rank, Map<String, Object> reward) {
            vRankRewards.computeIfAbsent(rank, ignored -> new ArrayList<>()).add(new LinkedHashMap<>(reward));
            return true;
        }

        @Override
        public boolean updateVRankReward(String rank, String rewardId, Map<String, Object> reward) {
            List<Map<String, Object>> rewards = vRankRewards.getOrDefault(rank, List.of());
            for (int i = 0; i < rewards.size(); i++) {
                if (rewardId.equals(rewards.get(i).get("id"))) {
                    rewards.set(i, new LinkedHashMap<>(reward));
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean deleteVRankReward(String rank, String rewardId) {
            List<Map<String, Object>> rewards = vRankRewards.get(rank);
            return rewards != null && rewards.removeIf(item -> rewardId.equals(item.get("id")));
        }

        @Override
        public Map<String, Object> leadershipPoolSummary() {
            return leadershipPoolSummary;
        }

        @Override
        public List<Map<String, Object>> leadershipRanks() {
            return leadershipRanks;
        }

        @Override
        public List<Map<String, Object>> quotaRows() {
            return quotaRows;
        }

        @Override
        public List<Map<String, Object>> ambassadorBands() {
            return ambassadorBands;
        }

        @Override
        public Map<String, Object> ambassadorSummary() {
            return ambassadorSummary;
        }

        @Override
        public List<Map<String, Object>> leaderboardPodium(int limit) {
            return leaderboardPodium.stream().limit(limit).toList();
        }

        @Override
        public Map<String, Object> leaderboardSummary() {
            return leaderboardSummary;
        }

        @Override
        public List<Map<String, Object>> commissionEvents(int limit) {
            return commissionEvents.stream().limit(limit).toList();
        }

        @Override
        public List<Map<String, Object>> commissionKindSummary() {
            return kindSummary;
        }

        @Override
        public List<Map<String, Object>> commissionAuditFeed(int limit) {
            return auditFeed.stream().limit(limit).toList();
        }

        @Override
        public boolean updateCommissionStatus(String eventId, String status) {
            for (Map<String, Object> event : commissionEvents) {
                if (eventId.equals(event.get("id"))) {
                    event.put("state", status);
                    event.put("cooldownLabel", status);
                    event.put("cooldownPercent", "unlocked".equals(status) ? 100 : event.getOrDefault("cooldownPercent", 0));
                    return true;
                }
            }
            return false;
        }
    }
}

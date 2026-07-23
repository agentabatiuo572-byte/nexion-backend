package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.growth.facade.VoucherGrantFacade;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.domain.TeamFulfillmentQueueRepository;
import ffdd.opsconsole.team.domain.TeamFulfillmentQueueRow;
import ffdd.opsconsole.team.domain.VRankConfigRow;
import ffdd.opsconsole.team.domain.VRankEvaluationSnapshot;
import ffdd.opsconsole.team.domain.VRankPerformanceRepository;
import ffdd.opsconsole.team.dto.TeamCommissionConfigUpdateRequest;
import ffdd.opsconsole.team.dto.VRankOverrideRequest;
import ffdd.opsconsole.team.dto.VRankPromotionLogQuery;
import ffdd.opsconsole.team.dto.VRankRewardPayoutActionRequest;
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
    private final EventOutboxService eventOutboxService = mock(EventOutboxService.class);
    private final ffdd.opsconsole.shared.security.AdminPermissionCache permissionCache = mock(ffdd.opsconsole.shared.security.AdminPermissionCache.class);
    private final VoucherGrantFacade voucherGrantFacade = command ->
            new VoucherGrantFacade.VoucherGrantResult("VGR-TEST-" + command.userId(), false);
    // Sprint5:F1 V-Rank 引擎 + 派发器(由 service 的 evaluate/override 端点驱动)
    private final VRankRewardDispatcher vRankRewardDispatcher = new VRankRewardDispatcher(
            commissionRepository,
            coverageFacade,
            ledgerPostingFacade,
            voucherGrantFacade,
            configFacade);
    private final VRankPromotionEngine vRankPromotionEngine = new VRankPromotionEngine(
            commissionRepository,
            new FakeVRankPerformanceRepository(),
            configFacade,
            new com.fasterxml.jackson.databind.ObjectMapper(),
            vRankRewardDispatcher,
            mock(ffdd.opsconsole.shared.outbox.EventOutboxService.class));
    private final OpsTeamService service = new OpsTeamService(
            configFacade,
            coverageFacade,
            ledgerPostingFacade,
            auditLogService,
            ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
            fulfillmentQueueRepository,
            commissionRepository,
            permissionCache,
            mock(ffdd.opsconsole.platform.mapper.AuditObjectLockMapper.class),
            vRankPromotionEngine,
            vRankRewardDispatcher,
            eventOutboxService);

    @BeforeEach
    void seedPermissionContext() {
        // updateConfig/updateUiConfig service 层二次校验需 admin id + 权限码;默认 stub 全 network_f2/f3/f4/f5 码通过。
        // A1 批1a:补 network_f5_commission_dispose / reject 以覆盖 F5 状态处置权限分流。
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(1L, null, List.of()));
        org.mockito.Mockito.when(permissionCache.getPermissionCodes(org.mockito.ArgumentMatchers.anyLong())).thenReturn(java.util.Set.of(
                "network_f2_read", "network_f2_write", "network_f2_royalty_rate", "network_f2_policy_amplify",
                "network_f3_read", "network_f3_write", "network_f3_match_rate",
                "network_f4_read", "network_f4_write", "network_f4_pool_fund",
                "network_f4_ambassador_approve", "network_f4_leaderboard_control",
                "network_f5_read", "network_f5_write", "network_f5_commission_dispose", "network_f5_commission_reject",
                "network_f1_permanent_protection"));
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
        configFacade.values.put("growth.phase.month.10.binaryDailyCap", "1500");

        ApiResult<Map<String, Object>> result = service.binary();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("dailyCap"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("currentMonth", 10)
                .containsEntry("currentPhase", "P5")
                .containsEntry("h1BinaryDailyCap", new BigDecimal("1500"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void disabledReadTimeSeedsDoNotExposeFDomainBusinessDefaults() {
        FakeTeamCommissionRepository soloRepo = new FakeTeamCommissionRepository();
        VRankRewardDispatcher soloDispatcher = new VRankRewardDispatcher(
                soloRepo, coverageFacade, ledgerPostingFacade, voucherGrantFacade, configFacade);
        VRankPromotionEngine soloEngine = new VRankPromotionEngine(
                soloRepo,
                new FakeVRankPerformanceRepository(),
                new FakePlatformConfigFacade(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                soloDispatcher,
                mock(ffdd.opsconsole.shared.outbox.EventOutboxService.class));
        OpsTeamService realOnlyService = new OpsTeamService(
                new FakePlatformConfigFacade(),
                coverageFacade,
                ledgerPostingFacade,
                mock(AuditLogService.class),
                ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.disabledForDirectConstruction(),
                new FakeTeamFulfillmentQueueRepository(),
                soloRepo,
                permissionCache,
                mock(ffdd.opsconsole.platform.mapper.AuditObjectLockMapper.class),
                soloEngine,
                soloDispatcher,
                mock(EventOutboxService.class));

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
    void k2LeaderboardSignalStaysPendingUntilF4ExecutesARealDisqualificationAction() {
        commissionRepository.leaderboardSummary.put("participantCount", 1);
        commissionRepository.leaderboardSummary.put("fraudHitCount", 1);
        commissionRepository.leaderboardSummary.put("poolUsd", BigDecimal.ZERO);
        commissionRepository.leaderboardSummary.put("periodStatus", "flagged");

        Map<String, Object> signalOnly = service.leadershipPool().getData();

        assertThat(signalOnly)
                .containsEntry("leaderboardFraudHitCount", 1)
                .containsEntry("leaderboardPeriodStatus", "flagged")
                .containsEntry("leaderboardDisqualified", false);

        commissionRepository.leaderboardSummary.put("periodStatus", "disqualified");

        Map<String, Object> afterF4Action = service.leadershipPool().getData();
        assertThat(afterF4Action)
                .containsEntry("leaderboardPeriodStatus", "disqualified")
                .containsEntry("leaderboardDisqualified", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void configValuesDoNotExposeLegacyFBusinessStateKeys() {
        configFacade.values.put("team.ui.F3.dailyCap.currentMonth", "6");
        configFacade.values.put("team.ui.F3.dailyCap.currentPhase", "收缩期");
        configFacade.values.put("team.ui.F.ambassador.q3-2025.status", "pending");
        configFacade.values.put("team.ui.F.leaderboard.period.status", "active");

        ApiResult<Map<String, Object>> result = service.leadershipPool();

        assertThat(result.getCode()).isZero();
        Map<String, String> values = (Map<String, String>) result.getData().get("configValues");
        // A1 批2b 修复1:F.ambassador.{label}.status / F.leaderboard.period.status 走业务表动态分发,不进 configValues 白名单,不暴露。
        // F3.dailyCap.* 为 H1 节奏镜像展示态,不归 F 域配置面,保留断言不暴露。
        // 注:F.quota.{proUnlock|rackUnlock|monthlyStock} 与 F.leaderboard.poolUsd 已纳入配置层兜底白名单(批2b 修复1),合理暴露。
        assertThat(values).doesNotContainKeys(
                "F3.dailyCap.currentMonth",
                "F3.dailyCap.currentPhase",
                "F.ambassador.q3-2025.status",
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

    // 批2a · 修复1 NEX 路径补强:F.unilevel.nex.L{1-7} 调 NEX 奖励走 updateUnilevelRule(nexPerUsd),
    // 不进 422 白名单分支(与 USDT 路径 F.unilevel.L{1-7} 对称)。前端 f2-rates.tsx 调 NEX 按钮 paramKey 对齐后端 isUnilevelRuleKey。
    @Test
    @SuppressWarnings("unchecked")
    void updateF2UnilevelNexPathUpdatesNexPerUsd() {
        commissionRepository.unilevelRates.add(new LinkedHashMap<>(Map.of(
                "level", "L1",
                "usdtPct", new BigDecimal("10"),
                "nexReward", new BigDecimal("50"),
                "label", "直推 DIRECT",
                "direct", true)));

        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f2-unilevel-nex",
                new TeamCommissionConfigUpdateRequest("F.unilevel.nex.L1", "55", "raise NEX reward", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).doesNotContainKey("team.ui.F.unilevel.nex.L1");
        List<Map<String, Object>> rates = (List<Map<String, Object>>) result.getData().get("unilevelRates");
        assertThat(rates)
                .filteredOn(row -> "L1".equals(row.get("level")))
                .singleElement()
                .satisfies(row -> assertThat(row.get("nexReward").toString()).isEqualTo("55"));
    }

    // 批2a · 修复2:F.cooldown 佣金冷却期范围校验 · 合法边界 0-90 天,负值越界拒绝。
    // 旧实现缺范围校验,F.cooldown=-1 被接受会导致佣金永不解锁(冷却期永恒),影响可提余额释放。
    @Test
    void updateF2CooldownBelowRangeRejected() {
        assertThatThrownBy(() -> service.updateConfig(
                "idem-f2-cooldown-neg",
                new TeamCommissionConfigUpdateRequest("F.cooldown", "-1", "negative cooldown", "superadmin")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("F_TEAM_COOLDOWN_OUT_OF_RANGE");
    }

    // 批2a · 修复2:F.cooldown 上界 90 天,超 90 拒绝。
    @Test
    void updateF2CooldownAboveRangeRejected() {
        assertThatThrownBy(() -> service.updateConfig(
                "idem-f2-cooldown-over",
                new TeamCommissionConfigUpdateRequest("F.cooldown", "91", "cooldown too long", "superadmin")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("F_TEAM_COOLDOWN_OUT_OF_RANGE");
    }

    // 批2a · 修复2:F.cooldown 合法值(30 天)被接受,写 configFacade 并审计。
    @Test
    void updateF2CooldownInRangeAccepted() {
        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f2-cooldown-ok",
                new TeamCommissionConfigUpdateRequest("F.cooldown", "30", "normal cooldown", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("team.ui.F.cooldown", "30");
    }

    // 批2a · 修复2:F.promo.weekMultiplier promo 周倍率范围校验 · 合法 1.0-3.0,3.5 越界拒绝。
    // 旧实现缺范围校验,promo 倍率是放大佣金流出的杠杆(amplifies=true),越界值突破 B1 覆盖率。
    @Test
    void updateF2PromoWeekMultiplierAboveRangeRejected() {
        assertThatThrownBy(() -> service.updateConfig(
                "idem-f2-promo-over",
                new TeamCommissionConfigUpdateRequest("F.promo.weekMultiplier", "3.5", "promo too aggressive", "superadmin")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("F_TEAM_PROMO_MULT_OUT_OF_RANGE");
    }

    // 批2a · 修复2:F.promo.weekMultiplier 下界 1.0,低于 1.0 拒绝(0.5 会反向缩小佣金,业务无意义)。
    @Test
    void updateF2PromoWeekMultiplierBelowRangeRejected() {
        assertThatThrownBy(() -> service.updateConfig(
                "idem-f2-promo-under",
                new TeamCommissionConfigUpdateRequest("F.promo.weekMultiplier", "0.5", "promo too low", "superadmin")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("F_TEAM_PROMO_MULT_OUT_OF_RANGE");
    }

    // 批2a · 修复2:F.promo.weekMultiplier 合法值(2.0)被接受。
    @Test
    void updateF2PromoWeekMultiplierInRangeAccepted() {
        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f2-promo-ok",
                new TeamCommissionConfigUpdateRequest("F.promo.weekMultiplier", "2.0", "normal promo week", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("team.ui.F.promo.weekMultiplier", "2.0");
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
        // A1 批1a:fixture 改 unlocked → rejected(原 计提→frozen 改 frozen 不再 post ledger)。
        // rejected 触发 OUT/SUCCESS 对冲分录(bizNo 含 REJECTED),覆盖红冲路径 + 状态机 + commission_reject 权限分流。
        commissionRepository.commissionEvents.add(new LinkedHashMap<>(Map.of(
                "id", "CM-7781",
                "kind", "network",
                "user", "U00001987",
                "amount", new BigDecimal("420"),
                "currency", "USDT",
                "cooldownPercent", 60,
                "cooldownLabel", "冷却 18d",
                "state", "unlocked")));
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
                new TeamCommissionConfigUpdateRequest("F.commission.CM-7781.status", "rejected", "reverse abnormal commission", "risk-ops"));

        assertThat(ledgerPostingFacade.entries).hasSize(1);
        assertThat(ledgerPostingFacade.entries.get(0))
                .containsEntry("bizNo", "F5-COMMISSION-CM-7781-REJECTED")
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
                .satisfies(event -> assertThat(event).containsEntry("state", "rejected"));
    }

    @Test
    void rejectedCommissionEventCannotBeReactivated() {
        // A1 批1a 修复1:REJECTED 终态不可转换(防资金漏洞:已驳回佣金复活=资金被重复处置)。
        commissionRepository.commissionEvents.add(new LinkedHashMap<>(Map.of(
                "id", "CM-9001",
                "kind", "network",
                "user", "U00002001",
                "amount", new BigDecimal("150"),
                "currency", "USDT",
                "cooldownPercent", 0,
                "cooldownLabel", "已驳回",
                "state", "rejected")));

        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f-commission-revive",
                new TeamCommissionConfigUpdateRequest("F.commission.CM-9001.status", "unlocked", "revive reversed commission", "risk-ops"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.name());
        assertThat(ledgerPostingFacade.entries).isEmpty();
    }

    @Test
    void frozenCommissionEventDoesNotPostLedger() {
        // A1 批1a 修复1b:frozen 不动余额,不应 post OUT 台账(防账实不符);UNLOCKED→FROZEN 为合法转换。
        commissionRepository.commissionEvents.add(new LinkedHashMap<>(Map.of(
                "id", "CM-9002",
                "kind", "binary",
                "user", "U00002002",
                "amount", new BigDecimal("88"),
                "currency", "USDT",
                "cooldownPercent", 100,
                "cooldownLabel", "已解锁(运营)",
                "state", "unlocked")));

        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f-commission-freeze",
                new TeamCommissionConfigUpdateRequest("F.commission.CM-9002.status", "frozen", "freeze for investigation", "risk-ops"));

        assertThat(result.getCode()).isZero();
        assertThat(ledgerPostingFacade.entries).isEmpty();
        verify(eventOutboxService, never()).publish(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("COMMISSION_UNLOCKED"),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void coolingToUnlockedPublishesServerAuthoritativeH3Event() {
        commissionRepository.commissionEvents.add(new LinkedHashMap<>(Map.of(
                "id", "CM-4201",
                "kind", "binary",
                "user", "U00004201",
                "amount", new BigDecimal("88"),
                "currency", "USDT",
                "cooldownPercent", 80,
                "cooldownLabel", "冷却 1d",
                "state", "cooling")));

        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f-commission-unlock",
                new TeamCommissionConfigUpdateRequest(
                        "F.commission.CM-4201.status", "unlocked", "cooldown completed", "risk-ops"));

        assertThat(result.getCode()).isZero();
        verify(eventOutboxService).publish(
                "COMMISSION",
                "CM-4201",
                "COMMISSION_UNLOCKED",
                Map.of("user_id", 4201L, "commission_event_id", "CM-4201"));
    }

    @Test
    void repeatedCommissionStateDoesNotPublishUnlockedEvent() {
        commissionRepository.commissionEvents.add(new LinkedHashMap<>(Map.of(
                "id", "CM-4202",
                "kind", "binary",
                "user", "U00004202",
                "amount", new BigDecimal("45"),
                "currency", "USDT",
                "cooldownPercent", 100,
                "cooldownLabel", "已解锁",
                "state", "unlocked")));

        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f-commission-unlock-replay",
                new TeamCommissionConfigUpdateRequest(
                        "F.commission.CM-4202.status", "unlocked", "duplicate request", "risk-ops"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        verify(eventOutboxService, never()).publish(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("COMMISSION_UNLOCKED"),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unlockedOutboxFailurePropagatesFromTransactionalUpdateBoundary() throws Exception {
        commissionRepository.commissionEvents.add(new LinkedHashMap<>(Map.of(
                "id", "CM-4203",
                "kind", "network",
                "user", "U00004203",
                "amount", new BigDecimal("25"),
                "currency", "USDT",
                "cooldownPercent", 90,
                "cooldownLabel", "冷却 1h",
                "state", "cooling")));
        org.mockito.Mockito.when(eventOutboxService.publish(
                        "COMMISSION",
                        "CM-4203",
                        "COMMISSION_UNLOCKED",
                        Map.of("user_id", 4203L, "commission_event_id", "CM-4203")))
                .thenThrow(new IllegalStateException("outbox unavailable"));

        assertThatThrownBy(() -> service.updateConfig(
                "idem-f-commission-unlock-outbox-failure",
                new TeamCommissionConfigUpdateRequest(
                        "F.commission.CM-4203.status", "unlocked", "cooldown completed", "risk-ops")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox unavailable");
        assertThat(OpsTeamService.class.getMethod(
                        "updateConfig", String.class, TeamCommissionConfigUpdateRequest.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class))
                .isTrue();
    }

    @Test
    void coolingToUnlockedFailsClosedWhenCommissionUserCannotBeResolved() {
        commissionRepository.commissionEvents.add(new LinkedHashMap<>(Map.of(
                "id", "CM-4204",
                "kind", "network",
                "user", "unknown-user",
                "amount", new BigDecimal("10"),
                "currency", "USDT",
                "cooldownPercent", 95,
                "cooldownLabel", "冷却 5m",
                "state", "cooling")));

        assertThatThrownBy(() -> service.updateConfig(
                "idem-f-commission-unlock-invalid-user",
                new TeamCommissionConfigUpdateRequest(
                        "F.commission.CM-4204.status", "unlocked", "cooldown completed", "risk-ops")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("COMMISSION_EVENT_USER_ID_INVALID");
        verify(eventOutboxService, never()).publish(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("COMMISSION_UNLOCKED"),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void uiPayoutAmplifyKeyIsBlockedBelowRedline() {
        // A1 批1a 修复2:UI 资金放大 key(F.pool.ratio 上调)B1 红线下阻断 422 COVERAGE_BELOW_REDLINE。
        coverageFacade.setSnapshot(new ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot(
                new BigDecimal("80.00"), new BigDecimal("85.00")));
        configFacade.values.put("team.ui.F.pool.ratio", "5%");

        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f-pool-amplify",
                new TeamCommissionConfigUpdateRequest("F.pool.ratio", "8%", "raise pool ratio", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        assertThat(configFacade.values).containsEntry("team.ui.F.pool.ratio", "5%");
    }

    @Test
    void uiConfigRejectsCrossDomainWriterWithoutPermission() {
        // A1 批1a 修复4:UI keys 跨域越权收敛:仅持 F2 权限者改 F.pool.ratio(F4 资金类)应 403 PERMISSION_DENIED。
        org.mockito.Mockito.when(permissionCache.getPermissionCodes(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.util.Set.of("network_f2_royalty_rate"));

        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f-pool-cross",
                new TeamCommissionConfigUpdateRequest("F.pool.ratio", "6%", "raise pool ratio", "f2-only-ops"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("PERMISSION_DENIED");
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

    // A1 批2b 修复2:F.pool.votes.V{n} 票权动态分发 → UPDATE nx_v_rank_config.leadership_votes + 审计 F_TEAM_VRANK_VOTES_CHANGED。
    // 旧实现:UI_CONFIG_KEYS 白名单不含 F.pool.votes.V{n} → updateUiConfig 抛 IllegalArgumentException → HTTP 500。
    @Test
    void updateF4VRankVotesWritesBusinessTableAndAudit() {
        commissionRepository.vRankRows.add(new LinkedHashMap<>(Map.of(
                "v", "V5",
                "label", "Advocate",
                "selfBuyUsd", BigDecimal.ZERO,
                "directRefs", 0,
                "teamGvUsd", BigDecimal.ZERO,
                "legCount", 0,
                "legRank", "",
                "votes", 4,
                "physicalReward", "",
                "pop", 0)));

        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f4-votes-v5",
                new TeamCommissionConfigUpdateRequest("F.pool.votes.V5", "8", "amplify V5 leadership weight", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(commissionRepository.lastUpdatedVotesRank).isEqualTo("V5");
        assertThat(commissionRepository.lastUpdatedVotesValue).isEqualTo(8);
        assertThat(configFacade.values).doesNotContainKey("team.ui.F.pool.votes.V5");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("F_TEAM_VRANK_VOTES_CHANGED");
        assertThat(captor.getValue().getResourceId()).isEqualTo("nx_v_rank_config:V5.leadership_votes");
    }

    // A1 批2b 修复2:票权越界拒绝(负值 / >1_000_000)。
    @Test
    void updateF4VRankVotesOutOfRangeRejected() {
        assertThatThrownBy(() -> service.updateConfig(
                "idem-f4-votes-neg",
                new TeamCommissionConfigUpdateRequest("F.pool.votes.V3", "-1", "negative votes", "superadmin")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("F_TEAM_VOTES_OUT_OF_RANGE");
    }

    // A1 批2b 修复2:票权权重调高 = 放大头部虹吸,B1 红线下阻断(对齐 pool_fund 类放大护栏范式)。
    @Test
    void updateF4VRankVotesAmplifyBlockedBelowRedline() {
        coverageFacade.setSnapshot(new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00")));
        commissionRepository.vRankRows.add(new LinkedHashMap<>(Map.of(
                "v", "V5",
                "label", "Advocate",
                "selfBuyUsd", BigDecimal.ZERO,
                "directRefs", 0,
                "teamGvUsd", BigDecimal.ZERO,
                "legCount", 0,
                "legRank", "",
                "votes", 4,
                "physicalReward", "",
                "pop", 0)));

        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f4-votes-amplify",
                new TeamCommissionConfigUpdateRequest("F.pool.votes.V5", "10", "amplify below redline", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
    }

    // A1 批2b 修复3:F.ambassador.{label}.status 动态分发 → UPDATE nx_team_ambassador_application.status
    // (label 非数字 → fallback 最新 PENDING 一条) + 审计 F_TEAM_AMBASSADOR_REVIEWED。
    @Test
    void updateF4AmbassadorStatusWritesBusinessTableAndAudit() {
        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f4-ambassador-approve",
                new TeamCommissionConfigUpdateRequest("F.ambassador.q3-2025.status", "approved", "approve q3 cycle ambassador", "risk-ops"));

        assertThat(result.getCode()).isZero();
        assertThat(commissionRepository.lastAmbassadorLabel).isEqualTo("q3-2025");
        assertThat(commissionRepository.lastAmbassadorStatus).isEqualTo("APPROVED");
        assertThat(configFacade.values).doesNotContainKey("team.ui.F.ambassador.q3-2025.status");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("F_TEAM_AMBASSADOR_REVIEWED");
    }

    // A1 批2b 修复3:大使 approved=开通预算=资金放大,B1 红线下阻断;rejected 不放大,放行。
    @Test
    void updateF4AmbassadorApprovedBlockedBelowRedline() {
        coverageFacade.setSnapshot(new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00")));

        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f4-ambassador-approve-blocked",
                new TeamCommissionConfigUpdateRequest("F.ambassador.q3-2025.status", "approved", "approve below redline", "risk-ops"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(commissionRepository.lastAmbassadorStatus).isNull();
    }

    // A1 批2b 修复4:F.leaderboard.period.status=disqualified → INSERT nx_team_leaderboard_action 流水 + 审计 F_TEAM_LEADERBOARD_CHANGED。
    @Test
    void updateF4LeaderboardDisqualifiedInsertsActionAndAudit() {
        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f4-leaderboard-dq",
                new TeamCommissionConfigUpdateRequest("F.leaderboard.period.status", "disqualified", "disqualify fraudulent leaderboard entries", "risk-ops"));

        assertThat(result.getCode()).isZero();
        assertThat(commissionRepository.lastLeaderboardPeriod).isEqualTo("week");
        assertThat(commissionRepository.lastLeaderboardActionType).isEqualTo("DISQUALIFIED");
        assertThat(configFacade.values).doesNotContainKey("team.ui.F.leaderboard.period.status");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("F_TEAM_LEADERBOARD_CHANGED");
    }

    // A1 批2b 修复1:F4 配额 3 key(proUnlock/rackUnlock/monthlyStock)走配置层兜底(业务表 tier 级细化留后续)。
    @Test
    void updateF4QuotaConfigWritesConfigLayer() {
        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f4-quota-stock",
                new TeamCommissionConfigUpdateRequest("F.quota.monthlyStock", "120", "raise monthly stock cap", "ops"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("team.ui.F.quota.monthlyStock", "120");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("F_TEAM_UI_CONFIG_CHANGED");
    }

    // A1 批2b 修复1:F.leaderboard.poolUsd 走配置层兜底(nx_team_leaderboard_action 无奖池字段,D4 联动留后续)。
    @Test
    void updateF4LeaderboardPoolUsdWritesConfigLayer() {
        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f4-leaderboard-pool",
                new TeamCommissionConfigUpdateRequest("F.leaderboard.poolUsd", "$60,000", "raise leaderboard pool", "ops"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("team.ui.F.leaderboard.poolUsd", "$60,000");
    }

    // A1 批2b 修复1:跨域越权收敛 — 仅持 F4 配额写权限(network_f4_write)不应用以改 F4 资金类(network_f4_pool_fund)。
    @Test
    void f4QuotaKeyRequiresF4WritePermissionNotPoolFund() {
        org.mockito.Mockito.when(permissionCache.getPermissionCodes(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.util.Set.of("network_f4_pool_fund"));

        ApiResult<Map<String, Object>> result = service.updateConfig(
                "idem-f4-quota-cross",
                new TeamCommissionConfigUpdateRequest("F.quota.monthlyStock", "120", "raise stock", "f4-pool-only-ops"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("PERMISSION_DENIED");
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

        void setSnapshot(TreasuryCoverageSnapshot next) {
            this.snapshot = next;
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
        // A1 批2b:F4 业务表写路径 fake 记录字段
        private String lastUpdatedVotesRank;
        private int lastUpdatedVotesValue;
        private String lastAmbassadorLabel;
        private String lastAmbassadorStatus;
        private String lastLeaderboardPeriod;
        private String lastLeaderboardActionType;
        // F1 V-Rank 引擎(Sprint 1+2):fake 状态
        private final List<VRankConfigRow> vRankConfigRows = new ArrayList<>();
        private final Map<Long, String> memberVRanks = new LinkedHashMap<>();
        private final List<Map<String, Object>> userLevelLogs = new ArrayList<>();

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

        // A1 批2b:F4 票权写 fake · F.pool.votes.V{n} 记录到 lastUpdatedVotes*。
        @Override
        public boolean updateVRankLeadershipVotes(String rankCode, int votes) {
            this.lastUpdatedVotesRank = rankCode;
            this.lastUpdatedVotesValue = votes;
            return true;
        }

        // A1 批2b:F4 大使审批写 fake · 记录 lastAmbassador*。
        @Override
        public boolean updateAmbassadorStatus(String applicationId, String status, String reviewer, String reason) {
            this.lastAmbassadorLabel = applicationId;
            this.lastAmbassadorStatus = status;
            return true;
        }

        // A1 批2b:F4 榜单处置写 fake · 记录 lastLeaderboard*。
        @Override
        public boolean insertLeaderboardAction(String period, String actionType, String reason, String operator) {
            this.lastLeaderboardPeriod = period;
            this.lastLeaderboardActionType = actionType;
            return true;
        }

        // F1 V-Rank 引擎(Sprint 1+2)fake 实现 — 返回预置 13 阶配置。
        @Override
        public List<VRankConfigRow> vRankConfigRows() {
            return vRankConfigRows;
        }

        @Override
        public String currentMemberVRank(Long userId) {
            return memberVRanks.getOrDefault(userId, "V0");
        }

        @Override
        public boolean updateMemberVRank(Long userId, String newRank) {
            memberVRanks.put(userId, newRank);
            return true;
        }

        @Override
        public boolean insertUserLevelLog(Long userId,
                                          String fromCode,
                                          String toCode,
                                          String reason,
                                          String operator,
                                          String snapshotJson,
                                          String triggerEventId,
                                          String auditNo,
                                          boolean isManual) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", userId);
            row.put("fromCode", fromCode);
            row.put("toCode", toCode);
            row.put("reason", reason);
            row.put("operator", operator);
            row.put("snapshotJson", snapshotJson);
            row.put("triggerEventId", triggerEventId);
            row.put("auditNo", auditNo);
            row.put("isManual", isManual);
            userLevelLogs.add(row);
            return true;
        }

        // ============================================================
        // Sprint3 接口实现 — OpsTeamService 测试不直接用,提供 no-op 让接口契约满足
        // ============================================================

        @Override
        public List<ffdd.opsconsole.team.domain.VRankRewardRuleRow> selectVRankRewardRulesByRank(String rankCode) {
            return List.of();
        }

        @Override
        public Long findSponsorUserId(Long userId) {
            return null;
        }

        @Override
        public boolean existsVRankRewardPayout(Long userId, String rankCode, String rewardType) {
            return false;
        }

        @Override
        public Long insertCommissionEvent(Long userId,
                                          String commissionType,
                                          Long sourceUserId,
                                          String currency,
                                          java.math.BigDecimal amountUsdt,
                                          java.math.BigDecimal amountNex,
                                          String status,
                                          int coolingDays,
                                          String remark) {
            // Sprint6 reissue 资金类路径需要有效 commissionEventId 才能 postLedgerEntry
            return nextCommissionEventId++;
        }

        @Override
        public int countNetworkCommissionByOrder(Long userId, String orderNo) {
            return 0;
        }

        @Override
        public Long insertNetworkCommissionEvent(Long userId,
                                                 String commissionType,
                                                 Long sourceUserId,
                                                 Integer layerNo,
                                                 String orderNo,
                                                 java.math.BigDecimal orderAmountUsdt,
                                                 String currency,
                                                 java.math.BigDecimal amountUsdt,
                                                 java.math.BigDecimal amountNex,
                                                 String status,
                                                 int coolingDays,
                                                 String remark) {
            return nextCommissionEventId++;
        }

        @Override
        public boolean insertVRankRewardPayout(ffdd.opsconsole.team.domain.VRankRewardPayout payout) {
            return false;
        }

        // Sprint5 端点 3:promotion-log 查询 fake — 按筛选条件在内存过滤预置流水。
        private final List<Map<String, Object>> promotionLog = new ArrayList<>();

        @Override
        public List<Map<String, Object>> queryPromotionLog(Long userId,
                                                           String v,
                                                           String cohort,
                                                           String from,
                                                           String to) {
            return promotionLog.stream()
                    .filter(row -> userId == null || userId.equals(row.get("userId")))
                    .filter(row -> v == null || v.isEmpty()
                            || v.equals(row.get("fromCode"))
                            || v.equals(row.get("toCode")))
                    .filter(row -> cohort == null || cohort.isEmpty()
                            || String.valueOf(row.getOrDefault("cohort", "")).contains(cohort))
                    .filter(row -> from == null || from.isEmpty()
                            || String.valueOf(row.getOrDefault("createdAt", "")).compareTo(from) >= 0)
                    .filter(row -> to == null || to.isEmpty()
                            || String.valueOf(row.getOrDefault("createdAt", "")).compareTo(to + " 23:59:59") <= 0)
                    .toList();
        }

        // ============================================================
        // Sprint6 端点第二组(payout 查询/补发/撤销)— 内存 fake 支持单测
        // ============================================================

        private final List<Map<String, Object>> rewardPayouts = new ArrayList<>();
        // 已红冲的 commission_event id 集合(reverseCommissionEvent 校验用)
        private final java.util.Set<Long> reversedCommissionEventIds = new java.util.HashSet<>();
        // insertCommissionEvent 自增计数(覆盖默认 null 实现,供 reissue 落 D4 测试)
        private long nextCommissionEventId = 9001L;

        @Override
        public List<Map<String, Object>> queryRewardPayouts(String type,
                                                            String v,
                                                            String status,
                                                            Long userId,
                                                            String cursor) {
            return rewardPayouts.stream()
                    .filter(row -> type == null || type.isEmpty()
                            || type.equalsIgnoreCase(String.valueOf(row.get("rewardType"))))
                    .filter(row -> v == null || v.isEmpty()
                            || v.equalsIgnoreCase(String.valueOf(row.get("rankCode"))))
                    .filter(row -> status == null || status.isEmpty()
                            || status.equalsIgnoreCase(String.valueOf(row.get("status"))))
                    .filter(row -> userId == null || userId.equals(row.get("userId")))
                    .filter(row -> cursor == null || cursor.isEmpty()
                            || String.valueOf(row.getOrDefault("grantedAt", "")).compareTo(cursor) < 0)
                    // 内存 fake 不保证顺序,排序由 SQL 层做;这里按 grantedAt desc 近似
                    .sorted((a, b) -> String.valueOf(b.getOrDefault("grantedAt", ""))
                            .compareTo(String.valueOf(a.getOrDefault("grantedAt", ""))))
                    .limit(100)
                    .toList();
        }

        @Override
        public Map<String, Object> findRewardPayoutByPayoutId(String payoutId) {
            return rewardPayouts.stream()
                    .filter(row -> payoutId.equals(row.get("payoutId")))
                    .findFirst()
                    .map(LinkedHashMap::new)
                    .orElse(null);
        }

        @Override
        public boolean updateRewardPayoutStatus(String payoutId, String newStatus, String operator, String reason) {
            for (Map<String, Object> row : rewardPayouts) {
                if (payoutId.equals(row.get("payoutId"))) {
                    row.put("status", newStatus);
                    row.put("operator", operator);
                    row.put("reason", reason);
                    if ("REVERSED".equals(newStatus)) {
                        row.put("reversedAt", java.time.LocalDateTime.now().toString());
                    }
                    return true;
                }
            }
            return false;
        }

        @Override
        public int reverseCommissionEvent(Long commissionEventId) {
            if (commissionEventId == null) {
                return 0;
            }
            reversedCommissionEventIds.add(commissionEventId);
            return 1;
        }
    }

    /** Sprint5:V-Rank 业绩仓储 fake — 默认返回全零快照(engine.evaluate 不会因业绩触发升阶,便于隔离测试)。 */
    private static final class FakeVRankPerformanceRepository implements VRankPerformanceRepository {
        @Override
        public VRankEvaluationSnapshot computeSnapshot(Long userId) {
            return VRankEvaluationSnapshot.empty();
        }
    }

    // ============================================================
    // Sprint 5:F1 V-Rank 晋升引擎 HTTP 端点单测
    // ============================================================

    /** 端点 1:evaluateVRank 在引擎判定无阶变化时返回 before==after、promoted=false。 */
    @Test
    @SuppressWarnings("unchecked")
    void evaluateVRankReturnsBeforeAfterPromotedAndRewards() {
        // given:用户当前 V0,业绩全零 → 引擎判定仍为 V0(无阶变化)
        commissionRepository.memberVRanks.put(7001L, "V0");

        ApiResult<Map<String, Object>> result = service.evaluateVRank(7001L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("userId", 7001L);
        assertThat(result.getData()).containsEntry("before", "V0");
        assertThat(result.getData()).containsEntry("after", "V0");
        assertThat(result.getData()).containsEntry("promoted", false);
        // rewards 来自 activeRewards(after),fake 默认为空 list
        assertThat((List<Map<String, Object>>) result.getData().get("rewards")).isEmpty();
        // 引擎未升阶,不写流水
        assertThat(commissionRepository.userLevelLogs).isEmpty();
    }

    /** 端点 1:evaluateVRank 在 vRankConfigRows 空(引擎提前 return currentRank)时 promoted=false。 */
    @Test
    void evaluateVRankReflectsEngineShortCircuitWhenConfigEmpty() {
        // fake vRankConfigRows 默认空,引擎读不到 13 阶配置 → 直接 return currentRank,不写流水
        commissionRepository.memberVRanks.put(7002L, "V3");

        ApiResult<Map<String, Object>> result = service.evaluateVRank(7002L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("before", "V3");
        assertThat(result.getData()).containsEntry("after", "V3");
        assertThat(result.getData()).containsEntry("promoted", false);
        assertThat(commissionRepository.userLevelLogs).isEmpty();
    }

    /** 端点 2:override promote —— 缺 Idempotency-Key 直接拒(IDEMPOTENCY_KEY_REQUIRED)。 */
    @Test
    void overrideVRankRejectsMissingIdempotencyKey() {
        ApiResult<Map<String, Object>> result = service.overrideVRank(7101L, null,
                new VRankOverrideRequest("V3", "promote", "manual promotion test", "tester"));
        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
    }

    /** 端点 2:override promote —— reason <6 字符拒(REASON_REQUIRED)。 */
    @Test
    void overrideVRankRejectsShortReason() {
        ApiResult<Map<String, Object>> result = service.overrideVRank(7102L, "idem-7102",
                new VRankOverrideRequest("V3", "promote", "short", "tester"));
        assertThat(result.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
    }

    /** 端点 2:override promote —— direction 与 targetV vs currentV 不一致拒。 */
    @Test
    void overrideVRankRejectsDirectionMismatch() {
        // currentV=V5,targetV=V3,direction=promote → 不一致(targetV 应 > currentV)
        commissionRepository.memberVRanks.put(7103L, "V5");
        ApiResult<Map<String, Object>> result = service.overrideVRank(7103L, "idem-7103",
                new VRankOverrideRequest("V3", "promote", "direction mismatch test", "tester"));
        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat((String) result.getMessage()).contains("VRANK_OVERRIDE_DIRECTION_MISMATCH");
    }

    /** 端点 2:override promote 合法路径 —— 写 member.v_rank + level_log(is_manual=true)。 */
    @Test
    @SuppressWarnings("unchecked")
    void overrideVRankPromoteWritesMemberAndLevelLog() {
        // currentV=V1,targetV=V3,direction=promote;业绩全零(fake) → 派发器在 V3 找不到奖励规则,不实际派发
        commissionRepository.memberVRanks.put(7104L, "V1");

        ApiResult<Map<String, Object>> result = service.overrideVRank(7104L, "idem-7104",
                new VRankOverrideRequest("V3", "promote", "force promote for testing", "tester"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("before", "V1");
        assertThat(result.getData()).containsEntry("after", "V3");
        assertThat(result.getData()).containsEntry("direction", "promote");
        assertThat(result.getData()).containsEntry("logInserted", true);
        // member 已被 UPDATE
        assertThat(commissionRepository.memberVRanks.get(7104L)).isEqualTo("V3");
        // level_log 落 1 条(is_manual=true, reason=[MANUAL] 前缀,operator 来自 resolveOperator)
        assertThat(commissionRepository.userLevelLogs).hasSize(1);
        Map<String, Object> log = commissionRepository.userLevelLogs.get(0);
        assertThat(log).containsEntry("userId", 7104L);
        assertThat(log).containsEntry("fromCode", "V1");
        assertThat(log).containsEntry("toCode", "V3");
        assertThat((Boolean) log.get("isManual")).isTrue();
        assertThat((String) log.get("reason")).startsWith("PROMOTE"); // direction.toUpperCase
        assertThat(log.get("auditNo")).asString().startsWith("VRANK-OVERRIDE-7104-V1-V3-");
        // 联动预览字段存在(before/after)
        assertThat(result.getData()).containsKey("beforePreview");
        assertThat(result.getData()).containsKey("afterPreview");
    }

    /** 端点 2:override rollback —— 不派发奖励(rollback 路径跳过 dispatcher.dispatch)。 */
    @Test
    void overrideVRankRollbackSkipsRewardDispatch() {
        // currentV=V5,targetV=V2,direction=rollback
        commissionRepository.memberVRanks.put(7105L, "V5");

        ApiResult<Map<String, Object>> result = service.overrideVRank(7105L, "idem-7105",
                new VRankOverrideRequest("V2", "rollback", "policy violation rollback", "tester"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("after", "V2");
        assertThat(commissionRepository.memberVRanks.get(7105L)).isEqualTo("V2");
        // level_log 落 1 条,direction=ROLLBACK
        assertThat(commissionRepository.userLevelLogs).hasSize(1);
        assertThat(commissionRepository.userLevelLogs.get(0)).containsEntry("reason", "ROLLBACK");
        // 不派发奖励(rollback 路径不进 dispatcher.dispatch)— ledgerPostingFacade 无写入
        assertThat(ledgerPostingFacade.entries).isEmpty();
    }

    /** 端点 2:A2 对象锁存在 → 409 OBJECT_LOCKED_BY_A2。 */
    @Test
    void overrideVRankBlockedByA2ObjectLock() {
        ffdd.opsconsole.platform.mapper.AuditObjectLockMapper lockMapper =
                mock(ffdd.opsconsole.platform.mapper.AuditObjectLockMapper.class);
        org.mockito.Mockito.when(lockMapper.countActiveByTarget("F", "vrank_override", "7106"))
                .thenReturn(1);
        OpsTeamService lockedService = new OpsTeamService(
                configFacade,
                coverageFacade,
                ledgerPostingFacade,
                auditLogService,
                ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
                fulfillmentQueueRepository,
                commissionRepository,
                permissionCache,
                lockMapper,
                vRankPromotionEngine,
                vRankRewardDispatcher,
                eventOutboxService);
        commissionRepository.memberVRanks.put(7106L, "V1");

        ApiResult<Map<String, Object>> result = lockedService.overrideVRank(7106L, "idem-7106",
                new VRankOverrideRequest("V3", "promote", "locked object test case", "tester"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat((String) result.getMessage()).contains("OBJECT_LOCKED_BY_A2");
        // 应在 lock 检查处阻断,不应写库
        assertThat(commissionRepository.memberVRanks.get(7106L)).isEqualTo("V1");
        assertThat(commissionRepository.userLevelLogs).isEmpty();
    }

    /** 端点 3:promotion-log —— 空筛选返回全部预置流水。 */
    @Test
    @SuppressWarnings("unchecked")
    void promotionLogReturnsAllRowsWhenNoFilter() {
        commissionRepository.promotionLog.add(makePromotionLogRow(8001L, "V0", "V1",
                "[MANUAL] force promote", "admin-1", "2026-07-21 10:00:00", "C2026Q3", "alice"));
        commissionRepository.promotionLog.add(makePromotionLogRow(8002L, "V1", "V2",
                "ORDER_PAID", "ENGINE", "2026-07-20 09:00:00", "C2026Q2", "bob"));

        ApiResult<Map<String, Object>> result = service.queryPromotionLog(new VRankPromotionLogQuery(null, null, null, null, null));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("total", 2);
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.getData().get("items");
        assertThat(items).hasSize(2);
        // isManual 标记:[MANUAL]% reason → true;其他 → false
        assertThat(items.stream().filter(row -> (Boolean) row.get("isManual")).toList()).hasSize(1);
    }

    /** 端点 3:promotion-log —— 按 userId 精确过滤。 */
    @Test
    @SuppressWarnings("unchecked")
    void promotionLogFiltersByUserId() {
        commissionRepository.promotionLog.add(makePromotionLogRow(8001L, "V0", "V1",
                "[MANUAL] test", "admin", "2026-07-21 10:00:00", "C1", "alice"));
        commissionRepository.promotionLog.add(makePromotionLogRow(8002L, "V1", "V2",
                "ORDER_PAID", "ENGINE", "2026-07-20 10:00:00", "C2", "bob"));

        ApiResult<Map<String, Object>> result = service.queryPromotionLog(new VRankPromotionLogQuery(8002L, null, null, null, null));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("total", 1);
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.getData().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("userId", 8002L);
    }

    /** 端点 3:promotion-log —— 按 v(fromCode 或 toCode 任一匹配)过滤。 */
    @Test
    @SuppressWarnings("unchecked")
    void promotionLogFiltersByV() {
        commissionRepository.promotionLog.add(makePromotionLogRow(8001L, "V0", "V1",
                "ORDER_PAID", "ENGINE", "2026-07-21 10:00:00", "C1", "alice"));
        commissionRepository.promotionLog.add(makePromotionLogRow(8002L, "V1", "V2",
                "ORDER_PAID", "ENGINE", "2026-07-20 10:00:00", "C2", "bob"));
        commissionRepository.promotionLog.add(makePromotionLogRow(8003L, "V5", "V3",
                "SYSTEM_EVALUATION", "ENGINE", "2026-07-19 10:00:00", "C3", "carol"));

        ApiResult<Map<String, Object>> result = service.queryPromotionLog(new VRankPromotionLogQuery(null, "V1", null, null, null));

        assertThat(result.getCode()).isZero();
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.getData().get("items");
        assertThat(items).hasSize(2); // 8001(toCode=V1) + 8002(fromCode=V1)
    }

    private Map<String, Object> makePromotionLogRow(Long userId,
                                                    String fromCode,
                                                    String toCode,
                                                    String reason,
                                                    String operator,
                                                    String createdAt,
                                                    String cohort,
                                                    String nickname) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", userId * 100);
        row.put("userId", userId);
        row.put("fromCode", fromCode);
        row.put("toCode", toCode);
        row.put("reason", reason);
        row.put("operator", operator);
        row.put("isManual", reason != null && reason.startsWith("[MANUAL]") ? 1 : 0);
        row.put("cohort", cohort);
        row.put("nickname", nickname);
        row.put("createdAt", createdAt);
        return row;
    }

    // ============================================================
    // Sprint 6 端点第二组(payout query/reissue/reverse)单测
    // ============================================================

    /** 端点 4:reward-payouts 查询 — 按 type/v/status/userId 筛选。 */
    @Test
    @SuppressWarnings("unchecked")
    void rewardPayoutsQueryFiltersByTypeVStatusUserId() {
        commissionRepository.rewardPayouts.add(makePayoutRow("pay-001", 8001L, "V1", "usdt",
                "GRANTED", "2026-07-21 10:00:00"));
        commissionRepository.rewardPayouts.add(makePayoutRow("pay-002", 8002L, "V2", "nex",
                "GRANTED", "2026-07-20 10:00:00"));
        commissionRepository.rewardPayouts.add(makePayoutRow("pay-003", 8003L, "V3", "voucher",
                "REVERSED", "2026-07-19 10:00:00"));

        // 按 type=usdt 过滤
        ApiResult<Map<String, Object>> byType = service.queryRewardPayouts("usdt", null, null, null, null);
        assertThat(byType.getCode()).isZero();
        List<Map<String, Object>> items = (List<Map<String, Object>>) byType.getData().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("payoutId", "pay-001");

        // 按 status=REVERSED 过滤
        ApiResult<Map<String, Object>> byStatus = service.queryRewardPayouts(null, null, "reversed", null, null);
        List<Map<String, Object>> reversedItems = (List<Map<String, Object>>) byStatus.getData().get("items");
        assertThat(reversedItems).hasSize(1);
        assertThat(reversedItems.get(0)).containsEntry("payoutId", "pay-003");
        // status 大写归一
        assertThat(reversedItems.get(0)).containsEntry("status", "REVERSED");

        // 按 v=V2 + userId 过滤
        ApiResult<Map<String, Object>> byVUser = service.queryRewardPayouts(null, "V2", null, 8002L, null);
        List<Map<String, Object>> v2Items = (List<Map<String, Object>>) byVUser.getData().get("items");
        assertThat(v2Items).hasSize(1);
        assertThat(v2Items.get(0)).containsEntry("payoutId", "pay-002");
    }

    /** 端点 5:reissue 缺 Idempotency-Key 拒。 */
    @Test
    void reissueRejectsMissingIdempotencyKey() {
        ApiResult<Map<String, Object>> result = service.reissueRewardPayout("pay-x", null,
                new VRankRewardPayoutActionRequest("reissue reason test", "tester"));
        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
    }

    /** 端点 5:reissue 找不到 payout → PAYOUT_NOT_FOUND。 */
    @Test
    void reissueRejectsMissingPayout() {
        ApiResult<Map<String, Object>> result = service.reissueRewardPayout("pay-missing", "idem-reissue-1",
                new VRankRewardPayoutActionRequest("reissue missing payout", "tester"));
        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat((String) result.getMessage()).contains("PAYOUT_NOT_FOUND");
    }

    /** 端点 5:reissue 资金类(usdt)落 D4:新 commission_event + postLedgerEntry(IN/PENDING) + payout UPDATE REISSUED。 */
    @Test
    void reissueFundPayoutPostsD4LedgerAndMarksReissued() {
        // 原 payout:status=REVERSED(允许补发),type=usdt,amount=50,commissionEventId=1234
        commissionRepository.rewardPayouts.add(makePayoutRow("pay-reissue-1", 8101L, "V2", "usdt",
                "REVERSED", "2026-07-15 10:00:00", new BigDecimal("50"), 1234L));
        // 资金类合法路径(B1 红线 fake 不阻断)
        ApiResult<Map<String, Object>> result = service.reissueRewardPayout("pay-reissue-1", "idem-reissue-2",
                new VRankRewardPayoutActionRequest("manual reissue payout", "admin-1"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("status", "REISSUED");
        assertThat(result.getData()).containsEntry("previousStatus", "REVERSED");
        // 新 commission_event 落库(insertCommissionEvent fake 自增 9001+)
        // postLedgerEntry 被调用(IN/PENDING,金额=50)
        assertThat(ledgerPostingFacade.entries).hasSize(1);
        Map<String, Object> entry = ledgerPostingFacade.entries.get(0);
        assertThat(entry).containsEntry("direction", "IN");
        assertThat(entry).containsEntry("status", "PENDING");
        assertThat(entry).containsEntry("amount", new BigDecimal("50"));
        // payout 已 UPDATE 为 REISSUED
        Map<String, Object> updated = commissionRepository.findRewardPayoutByPayoutId("pay-reissue-1");
        assertThat(updated.get("status")).isEqualTo("REISSUED");
    }

    /** 端点 5:reissue 状态机 — GRANTED 已派发 → INVALID_STATE_TRANSITION 拒(防重复补发)。 */
    @Test
    void reissueRejectsAlreadyGrantedPayout() {
        commissionRepository.rewardPayouts.add(makePayoutRow("pay-granted", 8102L, "V1", "usdt",
                "GRANTED", "2026-07-15 10:00:00", new BigDecimal("30"), null));

        ApiResult<Map<String, Object>> result = service.reissueRewardPayout("pay-granted", "idem-reissue-3",
                new VRankRewardPayoutActionRequest("double reissue attempt", "tester"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat((String) result.getMessage()).contains("PAYOUT_REISSUE_REQUIRES_REVERSED");
    }

    /** 端点 6:reverse 资金类红冲:commission_event REVERSED + ledgerPostingFacade OUT/SUCCESS + payout REVERSED。 */
    @Test
    void reverseFundPayoutPostsD4ReverseEntryAndMarksReversed() {
        commissionRepository.rewardPayouts.add(makePayoutRow("pay-reverse-1", 8201L, "V3", "usdt",
                "GRANTED", "2026-07-15 10:00:00", new BigDecimal("80"), 5678L));
        // 资金类合法路径
        ApiResult<Map<String, Object>> result = service.reverseRewardPayout("pay-reverse-1", "idem-reverse-1",
                new VRankRewardPayoutActionRequest("fraud detection reversal", "risk-admin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("status", "REVERSED");
        assertThat(result.getData()).containsEntry("previousStatus", "GRANTED");
        // commission_event 已被 reverseCommissionEvent 标 REVERSED
        assertThat(commissionRepository.reversedCommissionEventIds).contains(5678L);
        // postLedgerEntry 被调用(OUT/SUCCESS 反向冲正)
        assertThat(ledgerPostingFacade.entries).hasSize(1);
        Map<String, Object> entry = ledgerPostingFacade.entries.get(0);
        assertThat(entry).containsEntry("direction", "OUT");
        assertThat(entry).containsEntry("status", "SUCCESS");
        assertThat(entry).containsEntry("amount", new BigDecimal("80"));
        // payout 已 UPDATE 为 REVERSED
        Map<String, Object> updated = commissionRepository.findRewardPayoutByPayoutId("pay-reverse-1");
        assertThat(updated.get("status")).isEqualTo("REVERSED");
        // reversedAt 写入
        assertThat(updated.get("reversedAt")).asString().isNotEmpty();
    }

    /** 端点 6:reverse 权益类(voucher) 不进 D4:payout REVERSED 但 ledgerPostingFacade 不被调用。 */
    @Test
    void reverseVoucherPayoutSkipsD4Ledger() {
        commissionRepository.rewardPayouts.add(makePayoutRow("pay-voucher", 8202L, "V1", "voucher",
                "GRANTED", "2026-07-15 10:00:00", null, null));

        ApiResult<Map<String, Object>> result = service.reverseRewardPayout("pay-voucher", "idem-reverse-2",
                new VRankRewardPayoutActionRequest("voucher grant rollback", "tester"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("status", "REVERSED");
        // 权益类不走 D4
        assertThat(ledgerPostingFacade.entries).isEmpty();
    }

    /** 端点 6:reverse 幂等 — REVERSED 已终态,再 reverse 拒。 */
    @Test
    void reverseRejectsAlreadyReversedPayout() {
        commissionRepository.rewardPayouts.add(makePayoutRow("pay-twice", 8203L, "V2", "usdt",
                "REVERSED", "2026-07-15 10:00:00", new BigDecimal("10"), 9999L));

        ApiResult<Map<String, Object>> result = service.reverseRewardPayout("pay-twice", "idem-reverse-3",
                new VRankRewardPayoutActionRequest("double reverse attempt", "tester"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat((String) result.getMessage()).contains("PAYOUT_ALREADY_REVERSED");
    }

    /** 构造 payout row 测试数据。 */
    private Map<String, Object> makePayoutRow(String payoutId,
                                              Long userId,
                                              String rankCode,
                                              String rewardType,
                                              String status,
                                              String grantedAt,
                                              BigDecimal amount,
                                              Long commissionEventId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("payoutId", payoutId);
        row.put("userId", userId);
        row.put("rankCode", rankCode);
        row.put("rewardType", rewardType);
        row.put("status", status);
        row.put("grantedAt", grantedAt);
        row.put("amount", amount);
        row.put("commissionEventId", commissionEventId);
        row.put("sponsorUserId", null);
        row.put("operator", "");
        row.put("reason", "");
        row.put("reversedAt", null);
        return row;
    }

    /** 4-arg 重载:默认 amount=null, commissionEventId=null。 */
    private Map<String, Object> makePayoutRow(String payoutId,
                                              Long userId,
                                              String rankCode,
                                              String rewardType,
                                              String status,
                                              String grantedAt) {
        return makePayoutRow(payoutId, userId, rankCode, rewardType, status, grantedAt, null, null);
    }
}

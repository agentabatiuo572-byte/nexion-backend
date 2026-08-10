package ffdd.opsconsole.risk.infrastructure;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.eq;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.risk.mapper.RiskOpsMapper;
import ffdd.opsconsole.risk.application.K4RiskScorer;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class MybatisRiskOpsRepositoryTest {
    private final RiskOpsMapper mapper = mock(RiskOpsMapper.class);

    @Test
    void legacyKycDimensionIsRemovedFromTheCurrentK4ProjectionWithoutMutatingHistory() throws Exception {
        Map<String, Integer> legacyWeights = new LinkedHashMap<>();
        legacyWeights.put("multiAccount", 25);
        legacyWeights.put("arbitrage", 20);
        legacyWeights.put("kycStatus", 20);
        legacyWeights.put("withdrawVelocity", 15);
        legacyWeights.put("accountAge", 10);
        legacyWeights.put("anomalyBehavior", 10);
        Map<String, Boolean> legacySources = new LinkedHashMap<>();
        legacyWeights.keySet().forEach(key -> legacySources.put(key, true));
        Map<String, Integer> legacyMappings = new LinkedHashMap<>(K4RiskScorer.DEFAULT_MAPPINGS);
        legacyMappings.put("kyc.reviewScore", 20);
        legacyMappings.put("kyc.pendingScore", 40);
        legacyMappings.put("kyc.rejectedScore", 80);
        legacyMappings.put("kyc.sanctionedScore", 100);
        ObjectMapper json = new ObjectMapper();
        when(mapper.activeScoreModel()).thenReturn(new RiskOpsMapper.ScoreModelRecord(
                7L, 3L, "active", json.writeValueAsString(legacyWeights),
                json.writeValueAsString(legacySources), json.writeValueAsString(legacyMappings),
                40, 70, 85, "legacy active", "risk-admin", "risk-admin", "now", "now"));
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper, OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        var model = repository.activeScoringModel().orElseThrow();

        assertThat(model.weights()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "multiAccount", 30, "arbitrage", 25,
                "withdrawVelocity", 20, "accountAge", 10, "anomalyBehavior", 15));
        assertThat(model.weights().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
        assertThat(model.inputSources()).containsOnlyKeys(
                "multiAccount", "arbitrage", "withdrawVelocity", "accountAge", "anomalyBehavior");
        assertThat(model.scoreMappings()).isEqualTo(K4RiskScorer.DEFAULT_MAPPINGS);
        assertThat(model.scoreMappings()).doesNotContainKeys(
                "kyc.reviewScore", "kyc.pendingScore", "kyc.rejectedScore", "kyc.sanctionedScore");
    }

    @Test
    void customLegacyWeightsAreDeterministicallyRenormalizedAndMalformedJsonStillFailsClosed() throws Exception {
        ObjectMapper json = new ObjectMapper();
        Map<String, Integer> customLegacyWeights = new LinkedHashMap<>();
        customLegacyWeights.put("multiAccount", 10);
        customLegacyWeights.put("arbitrage", 10);
        customLegacyWeights.put("kycStatus", 20);
        customLegacyWeights.put("withdrawVelocity", 20);
        customLegacyWeights.put("accountAge", 20);
        customLegacyWeights.put("anomalyBehavior", 20);
        Map<String, Boolean> legacySources = new LinkedHashMap<>();
        customLegacyWeights.keySet().forEach(key -> legacySources.put(key, true));
        Map<String, Integer> legacyMappings = new LinkedHashMap<>(K4RiskScorer.DEFAULT_MAPPINGS);
        legacyMappings.put("kyc.reviewScore", 20);
        legacyMappings.put("kyc.pendingScore", 40);
        legacyMappings.put("kyc.rejectedScore", 80);
        legacyMappings.put("kyc.sanctionedScore", 100);
        when(mapper.activeScoreModel()).thenReturn(
                new RiskOpsMapper.ScoreModelRecord(
                        8L, 1L, "active", json.writeValueAsString(customLegacyWeights),
                        json.writeValueAsString(legacySources), json.writeValueAsString(legacyMappings),
                        40, 70, 85, "custom legacy", "risk-admin", "risk-admin", "now", "now"),
                new RiskOpsMapper.ScoreModelRecord(
                        9L, 1L, "active", "{broken", "{broken", "{broken",
                        40, 70, 85, "malformed", "risk-admin", "risk-admin", "now", "now"));
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper, OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        var custom = repository.activeScoringModel().orElseThrow();
        var malformed = repository.activeScoringModel().orElseThrow();

        assertThat(custom.weights()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "multiAccount", 13, "arbitrage", 12,
                "withdrawVelocity", 25, "accountAge", 25, "anomalyBehavior", 25));
        assertThat(K4RiskScorer.isCurrentModelSnapshot(custom)).isTrue();
        assertThat(malformed.weights()).isEmpty();
        assertThat(malformed.inputSources()).isEmpty();
        assertThat(malformed.scoreMappings()).isEmpty();
    }

    @Test
    void unknownOrIncompleteKycMappingsAreNotProjectedAsApprovedLegacyHistory() throws Exception {
        ObjectMapper json = new ObjectMapper();
        Map<String, Integer> legacyWeights = new LinkedHashMap<>();
        legacyWeights.put("multiAccount", 25);
        legacyWeights.put("arbitrage", 20);
        legacyWeights.put("kycStatus", 20);
        legacyWeights.put("withdrawVelocity", 15);
        legacyWeights.put("accountAge", 10);
        legacyWeights.put("anomalyBehavior", 10);
        Map<String, Boolean> legacySources = new LinkedHashMap<>();
        legacyWeights.keySet().forEach(key -> legacySources.put(key, true));
        Map<String, Integer> unknownMapping = new LinkedHashMap<>(K4RiskScorer.DEFAULT_MAPPINGS);
        unknownMapping.put("kyc.unknownScore", 50);
        Map<String, Integer> incompleteMapping = new LinkedHashMap<>(K4RiskScorer.DEFAULT_MAPPINGS);
        incompleteMapping.put("kyc.reviewScore", 20);
        when(mapper.activeScoreModel()).thenReturn(
                new RiskOpsMapper.ScoreModelRecord(
                        10L, 1L, "active", json.writeValueAsString(legacyWeights),
                        json.writeValueAsString(legacySources), json.writeValueAsString(unknownMapping),
                        40, 70, 85, "unknown mapping", "risk-admin", "risk-admin", "now", "now"),
                new RiskOpsMapper.ScoreModelRecord(
                        11L, 1L, "active", json.writeValueAsString(legacyWeights),
                        json.writeValueAsString(legacySources), json.writeValueAsString(incompleteMapping),
                        40, 70, 85, "incomplete mapping", "risk-admin", "risk-admin", "now", "now"));
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper, OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        var unknown = repository.activeScoringModel().orElseThrow();
        var incomplete = repository.activeScoringModel().orElseThrow();

        assertThat(unknown.scoreMappings()).containsKey("kyc.unknownScore");
        assertThat(incomplete.scoreMappings()).containsKey("kyc.reviewScore");
    }

    @Test
    void malformedActiveModelDoesNotCrashStartupOrOverwriteDerivedK4Tables() {
        when(mapper.countScoreModels()).thenReturn(1L);
        when(mapper.activeScoreModel()).thenReturn(new RiskOpsMapper.ScoreModelRecord(
                12L, 1L, "active", "{broken", "null", "{broken",
                40, 70, 85, "malformed", "risk-admin", "risk-admin", "now", "now"));
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper, OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        repository.ensureRiskSchema();

        verify(mapper, never()).upsertScoreDimension(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
        verify(mapper, never()).upsertScoreConfig(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void k4DraftVersionAdvancesFromTheGreatestImmutableHistoryVersion() {
        when(mapper.activeScoreModel()).thenReturn(new RiskOpsMapper.ScoreModelRecord(
                112L, 1L, "active", "{}", "{}", "{}",
                40, 70, 85, "baseline", "publisher", "publisher", "now", "now"));
        when(mapper.draftScoreModel()).thenReturn(null).thenReturn(new RiskOpsMapper.ScoreModelRecord(
                115L, 0L, "draft", "{}", "{}", "{}",
                40, 70, 85, "next", "maker", null, "now", null));
        when(mapper.maxScoreModelVersion()).thenReturn(114L);
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper, OpsReadTimeSeedPolicy.disabledForDirectConstruction());
        var request = new ffdd.opsconsole.risk.dto.RiskScoringModelDraftRequest(
                1L, Map.of(), Map.of(), Map.of(), 40, 70, 85, "next", "maker");

        assertThat(repository.saveScoringModelDraft(1L, request, "maker")).isPresent();

        ArgumentCaptor<Long> version = ArgumentCaptor.forClass(Long.class);
        verify(mapper).insertScoreModelDraft(
                version.capture(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), eq(40), eq(70), eq(85), eq("next"), eq("maker"));
        assertThat(version.getValue()).isEqualTo(115L);
    }

    @Test
    void k3DryRunCandidateQueryUsesTheDeclaredThirtyDayWindow() throws Exception {
        var select = RiskOpsMapper.class.getMethod("withdrawRuleCandidates", int.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class);

        assertThat(String.join(" ", select.value()))
                .contains("w.created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)");
    }

    @Test
    void k2E3ProjectionKeepsExactlyFiveBusinessEvidenceCells() throws Exception {
        var insert = RiskOpsMapper.class.getMethod("upsertE3TradeinArbitrageRows")
                .getAnnotation(org.apache.ibatis.annotations.Insert.class);
        String sql = String.join(" ", insert.value()).replaceAll("\\s+", " ");

        assertThat(sql)
                .contains("CONCAT_WS(' / '")
                .contains("NULL, 3, 'flag'")
                .doesNotContain("NULL, 4, 'flag'")
                .doesNotContain("'E3 交易事实实时投影'");
    }

    @Test
    void orphanContributionCleanupOnlyUpdatesColumnsDeclaredByTheTable() throws Exception {
        var update = RiskOpsMapper.class.getMethod("retireOrphanScoreContributions")
                .getAnnotation(org.apache.ibatis.annotations.Update.class);

        assertThat(String.join(" ", update.value()))
                .contains("c.is_deleted=1")
                .doesNotContain("c.updated_at");
    }

    @Test
    void k4ModelAndScoreHistoryPersistVersionedMappingSnapshots() throws Exception {
        var modelSelect = RiskOpsMapper.class.getMethod("activeScoreModel")
                .getAnnotation(org.apache.ibatis.annotations.Select.class);
        var contributionInsert = RiskOpsMapper.class.getMethod(
                        "insertCanonicalScoreContribution", String.class, long.class, String.class, String.class,
                        boolean.class, String.class, int.class, int.class, int.class, int.class)
                .getAnnotation(org.apache.ibatis.annotations.Insert.class);
        var historyInsert = RiskOpsMapper.class.getMethod(
                        "insertScoreHistory", String.class, long.class, int.class, int.class,
                        String.class, String.class, String.class, String.class)
                .getAnnotation(org.apache.ibatis.annotations.Insert.class);

        assertThat(String.join(" ", modelSelect.value())).contains("score_mapping_json");
        assertThat(String.join(" ", contributionInsert.value())).contains("model_version");
        assertThat(String.join(" ", historyInsert.value())).contains("nx_admin_risk_score_history", "contributions_json");
    }

    @Test
    void automaticK4ProjectionRefreshPreservesOverrideAndRecordsTheEffectiveScore() {
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper, OpsReadTimeSeedPolicy.disabledForDirectConstruction());
        var model = new ffdd.opsconsole.risk.domain.RiskScoreModelView(
                45L, 1L, "active",
                Map.of("multiAccount", 30, "arbitrage", 25,
                        "withdrawVelocity", 20, "accountAge", 10, "anomalyBehavior", 15),
                Map.of("multiAccount", true, "arbitrage", true,
                        "withdrawVelocity", true, "accountAge", true, "anomalyBehavior", true),
                40, 70, 85, "{}", "publish", "superadmin", "now", "now");
        var override = new ffdd.opsconsole.risk.domain.RiskScoreOverrideView(
                "U00000052", 10, 35, "人工判断", "superadmin", "now", true);
        when(mapper.activeScoreOverride("U00000052")).thenReturn(override);
        when(mapper.updateScoreUserModelIfVersion("U00000052", 7L, 15, "k4-v45")).thenReturn(1);
        when(mapper.findScoreUser("U00000052")).thenReturn(
                new RiskOpsMapper.ScoreUserRecord("U00000052", 15, "k4-v45", 8L, "now", "now"));
        when(mapper.scoreConfigRows()).thenReturn(java.util.List.of());
        when(mapper.scoreContributions("U00000052")).thenReturn(java.util.List.of());

        var refreshed = repository.refreshScoreProjection(
                "U00000052", 7L, model, 15, java.util.List.of()).orElseThrow();

        assertThat(refreshed.modelScore()).isEqualTo(15);
        assertThat(refreshed.effectiveScore()).isEqualTo(35);
        assertThat(refreshed.overridden()).isTrue();
        verify(mapper, never()).deactivateScoreOverrides("U00000052");
        verify(mapper).insertScoreHistory(
                eq("U00000052"), eq(45L), eq(15), eq(35), eq("manually-overridden"),
                org.mockito.ArgumentMatchers.anyString(), eq("事实源刷新（保留人工覆盖）"), eq("system:k4"));
        verify(mapper).advanceScoreAsOfToLatestSource("U00000052");
        var order = inOrder(mapper);
        order.verify(mapper).lockScoreUserForUpdate("U00000052");
        order.verify(mapper).activeScoreOverride("U00000052");
        order.verify(mapper).updateScoreUserModelIfVersion("U00000052", 7L, 15, "k4-v45");
    }

    @Test
    void k4OverrideLocksTheScoreRootBeforeCasAndOverrideRows() {
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper, OpsReadTimeSeedPolicy.disabledForDirectConstruction());
        when(mapper.findScoreUser("U00000052")).thenReturn(
                new RiskOpsMapper.ScoreUserRecord("U00000052", 15, "k4-v45", 8L, "now", "now"));
        when(mapper.bumpScoreUserVersion("U00000052", 8L)).thenReturn(1);
        when(mapper.scoreConfigRows()).thenReturn(java.util.List.of());
        when(mapper.scoreContributions("U00000052")).thenReturn(java.util.List.of());
        when(mapper.activeScoreOverride("U00000052")).thenReturn(
                new ffdd.opsconsole.risk.domain.RiskScoreOverrideView(
                        "U00000052", 15, 35, "manual", "operator", "now", true));

        repository.overrideScore("U00000052", 8L, 35, "manual", "operator");

        var order = inOrder(mapper);
        order.verify(mapper).lockScoreUserForUpdate("U00000052");
        order.verify(mapper).findScoreUser("U00000052");
        order.verify(mapper).bumpScoreUserVersion("U00000052", 8L);
        order.verify(mapper).deactivateScoreOverrides("U00000052");
    }

    @Test
    void synchronizeScoringUsersUsesScoreOverrideContributionLockOrder() {
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper, OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        repository.synchronizeScoringUsers();

        var order = inOrder(mapper);
        order.verify(mapper).retireOrphanScoreUsers();
        order.verify(mapper).ensureAllActiveUsersHaveScoreRows();
        order.verify(mapper).deactivateOrphanScoreOverrides();
        order.verify(mapper).retireOrphanScoreContributions();
    }

    @Test
    void c1CurrentScoreBandUsesTheCurrentActiveModelsNonDefaultThresholds() {
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper, OpsReadTimeSeedPolicy.disabledForDirectConstruction());
        when(mapper.findCurrentScoreUser("U00000067")).thenReturn(
                new RiskOpsMapper.ScoreUserRecord("U00000067", 67, "k4-v23", 4L, "now", "now"));
        when(mapper.activeScoreModel()).thenReturn(new RiskOpsMapper.ScoreModelRecord(
                23L, 8L, "active", "{}", "{}", "{}",
                35, 65, 80, "non-default threshold test", "risk-admin", "risk-admin", "now", "now"));
        when(mapper.activeScoreOverride("U00000067")).thenReturn(null);
        when(mapper.scoreContributions("U00000067")).thenReturn(java.util.List.of());
        when(mapper.scoreConfigRows()).thenReturn(java.util.List.of(
                new RiskOpsMapper.ScoreConfigRecord("bandLowMax", "40"),
                new RiskOpsMapper.ScoreConfigRecord("bandHighMin", "70"),
                new RiskOpsMapper.ScoreConfigRecord("autoEscalateScore", "85")));

        var current = repository.findCurrentScoreUser("U00000067").orElseThrow();

        assertThat(current.bandLabel()).isEqualTo("高风险");
        assertThat(current.bandTone()).isEqualTo("bad");
        assertThat(current.modelVersion()).isEqualTo("k4-v23");
    }

    @Test
    void currentScoreFailsClosedWhenEscalationIsBelowTheHighRiskBand() {
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper, OpsReadTimeSeedPolicy.disabledForDirectConstruction());
        when(mapper.findCurrentScoreUser("U00000068")).thenReturn(
                new RiskOpsMapper.ScoreUserRecord("U00000068", 88, "k4-v24", 5L, "now", "now"));
        when(mapper.activeScoreModel()).thenReturn(new RiskOpsMapper.ScoreModelRecord(
                24L, 9L, "active", "{}", "{}", "{}",
                40, 90, 85, "invalid escalation threshold", "risk-admin", "risk-admin", "now", "now"));

        assertThat(repository.findCurrentScoreUser("U00000068")).isEmpty();
    }

    @Test
    void ensureRiskSchemaDoesNotSeedDataWhenReadTimeSeedsAreDisabled() {
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper,
                OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        repository.ensureRiskSchema();

        verify(mapper).createRiskDecisionTable();
        verify(mapper).createWithdrawRuleTable();
        verify(mapper).addWithdrawRulePriorityColumn();
        verify(mapper).addWithdrawRuleVersionColumn();
        verify(mapper).deactivateOrphanScoreOverrides();
        verify(mapper).retireOrphanScoreContributions();
        verify(mapper).retireOrphanScoreUsers();
        verify(mapper).ensureAllActiveUsersHaveScoreRows();
        verify(mapper).createScoreHistoryTable();
        verify(mapper).backfillScoreModelMappings(org.mockito.ArgumentMatchers.anyString());
        verify(mapper, never()).countRiskCases();
        verify(mapper, never()).insertWithdrawRule(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void k3RuleUpdatesUseExpectedVersionAndReturnOnlyTheIncrementedRow() {
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper,
                OpsReadTimeSeedPolicy.disabledForDirectConstruction());
        var updated = new ffdd.opsconsole.risk.domain.RiskRuleView(
                "WR-1", "金额", "单笔 >= $2,000", "freeze", "paused", false,
                80, 4L, java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
        when(mapper.updateWithdrawRuleState("WR-1", 3L, "paused")).thenReturn(1);
        when(mapper.findWithdrawRule("WR-1")).thenReturn(updated);

        assertThat(repository.updateWithdrawRuleState("WR-1", 3L, "paused")).contains(updated);
        verify(mapper).updateWithdrawRuleState(eq("WR-1"), eq(3L), eq("paused"));
    }

    @Test
    void tamperProjectionReturnsTheActuallyAppliedK4DeltaAndPersistsTheSharedB5Signal() {
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper,
                OpsReadTimeSeedPolicy.disabledForDirectConstruction());
        when(mapper.lockTamperScoreValue("U00000042")).thenReturn(90);
        when(mapper.scoreValue("U00000042")).thenReturn(100);

        var projection = repository.projectTamperSignal(
                "TAMPER-event-42", 42L, "U00000042", "canonical evidence", 20,
                true, "risk.tamper_detected");

        assertThat(projection.k4Accepted()).isTrue();
        assertThat(projection.k4Delta()).isEqualTo(10);
        assertThat(projection.b5Accepted()).isTrue();
        verify(mapper).insertSignal(
                "TAMPER-event-42", 42L, "TAMPER_DETECTED", "HIGH",
                "canonical evidence", "risk.tamper_detected");
        verify(mapper).applyTamperScore("U00000042", 20);
        verify(mapper).insertTamperScoreContribution(
                "U00000042", "服务器篡改拦截事件 TAMPER-event-42", 10);
        var ordered = inOrder(mapper);
        ordered.verify(mapper).ensureTamperScoreUser("U00000042");
        ordered.verify(mapper).lockTamperScoreValue("U00000042");
        ordered.verify(mapper).applyTamperScore("U00000042", 20);
        ordered.verify(mapper).scoreValue("U00000042");
    }

    @Test
    void disablingK4StillPersistsTheB5TamperSignalWithoutChangingRiskScore() {
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper,
                OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        var projection = repository.projectTamperSignal(
                "TAMPER-event-b5", 43L, "U00000043", "canonical evidence", 4,
                false, "risk.tamper_detected");

        assertThat(projection.k4Accepted()).isFalse();
        assertThat(projection.k4Delta()).isZero();
        assertThat(projection.b5Accepted()).isTrue();
        verify(mapper).insertSignal(
                "TAMPER-event-b5", 43L, "TAMPER_DETECTED", "HIGH",
                "canonical evidence", "risk.tamper_detected");
        verify(mapper, never()).applyTamperScore(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
        verify(mapper, never()).insertTamperScoreContribution(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void k1OverviewCanonicalizesLegacyJoinedAtArraysAndSpaceStringsWithoutInventingNullTimes() {
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper, OpsReadTimeSeedPolicy.disabledForDirectConstruction());
        String legacyNodes = """
                [{"userNo":"U00000001","joinedAt":[2026,7,26,18,22,54]},
                 {"userNo":"U00000002","joinedAt":[2026,7,26,18,22,55,123456789]},
                 {"userNo":"U00000003","joinedAt":"2026-07-26 18:22:56"},
                 {"userNo":"U00000004","joinedAt":null},
                 {"userNo":"U00000005","joinedAt":[2026,7,26,18,22,54.9]},
                 {"userNo":"U00000006","joinedAt":[2026,7,26,18]},
                 {"userNo":"U00000007","joinedAt":[2026,7,26,18,22,54,0,1]},
                 {"userNo":"U00000008","joinedAt":[2026,2,29,18,22,54]},
                 {"userNo":"U00000009","joinedAt":[2026,7,26,18,22,1e2]},
                 {"userNo":"U00000010","joinedAt":[2026,-1,26,18,22,54]},
                 {"userNo":"U00000011","joinedAt":[2147483648,7,26,18,22,54]}]
                """;
        var legacy = new RiskOpsMapper.MultiAccountClusterRecord(
                "K1-LEGACY", "设备 ••••LEGACY", "device", "设备指纹", 11, 0.8,
                "2026-07-26 18:22 至 2026-07-26 18:23（1 分钟）", "detected", "legacy",
                "[]", legacyNodes, "[]", null, 2L);
        when(mapper.multiAccountClusters()).thenReturn(java.util.List.of(legacy));
        when(mapper.countMultiAccountClustersByFilter(null, null)).thenReturn(1L);
        when(mapper.pageMultiAccountClustersByFilter(null, null, "strength_desc", 0, 5))
                .thenReturn(java.util.List.of(legacy));
        when(mapper.riskParams("k1")).thenReturn(java.util.List.of());

        Map<String, Object> overview = repository.multiAccountOverview(1, 5, null, null, "strength_desc", 1, 5);
        var page = (ffdd.opsconsole.shared.api.PageResult<?>) overview.get("clusters");
        var row = (RiskOpsMapper.MultiAccountClusterRecord) page.getRecords().get(0);

        assertThat(row.nodesJson())
                .contains("\"joinedAt\":\"2026-07-26T18:22:54\"")
                .contains("\"joinedAt\":\"2026-07-26T18:22:55.123456789\"")
                .contains("\"joinedAt\":\"2026-07-26T18:22:56\"")
                .contains("\"joinedAt\":null")
                .contains("\"joinedAt\":[2026,7,26,18,22,54.9]")
                .contains("\"joinedAt\":[2026,7,26,18]")
                .contains("\"joinedAt\":[2026,7,26,18,22,54,0,1]")
                .contains("\"joinedAt\":[2026,2,29,18,22,54]")
                .contains("\"joinedAt\":[2026,7,26,18,22,100.0]")
                .contains("\"joinedAt\":[2026,-1,26,18,22,54]")
                .contains("\"joinedAt\":[2147483648,7,26,18,22,54]")
                .doesNotContain("\"joinedAt\":\"2026-07-26T18:22:54.9\"");
    }

    @Test
    void k1AuditSnapshotReadsCurrentParamAndWhitelistIncludingInactiveRows() {
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper,
                OpsReadTimeSeedPolicy.disabledForDirectConstruction());
        when(mapper.riskParamValue("k1", "maxAccountsPerDevice")).thenReturn("2");
        when(mapper.ipWhitelistState("198.51.100.10/32")).thenReturn(
                new RiskOpsMapper.IpWhitelistRecord(
                        "198.51.100.10/32", "shared office", "risk-admin", "2099-12-31", false));

        assertThat(repository.multiAccountParamValue("maxAccountsPerDevice")).contains("2");
        assertThat(repository.ipWhitelistState("198.51.100.10/32")).contains(
                new ffdd.opsconsole.risk.domain.RiskOpsRepository.IpWhitelistState(
                        "198.51.100.10/32", "shared office", "risk-admin", "2099-12-31", false));
    }
}

package ffdd.opsconsole.risk.infrastructure;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.eq;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.risk.mapper.RiskOpsMapper;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MybatisRiskOpsRepositoryTest {
    private final RiskOpsMapper mapper = mock(RiskOpsMapper.class);

    @Test
    void firstHighK4ScoreClaimsOneDurableK5Crossing() {
        when(mapper.findK4KycTriggerStateForUpdate("U1")).thenReturn(null);
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper, OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        boolean claimed = repository.transitionK4KycReviewTriggerState("U1", 91, 85, "score:9");

        assertThat(claimed).isTrue();
        verify(mapper).insertK4KycTriggerState("U1", true, 91, 85, "score:9", 1L);
    }

    @Test
    void sustainedHighK4ScoreUpdatesStateWithoutClaimingOrIncrementingAgain() {
        when(mapper.findK4KycTriggerStateForUpdate("U1")).thenReturn(
                new RiskOpsMapper.K4KycTriggerStateRecord("U1", true, 90, 85, "score:8", 1L, 3L));
        when(mapper.updateK4KycTriggerState("U1", true, 91, 85, "score:9", 0, 3L)).thenReturn(1);
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper, OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        boolean claimed = repository.transitionK4KycReviewTriggerState("U1", 91, 85, "score:9");

        assertThat(claimed).isFalse();
        verify(mapper).updateK4KycTriggerState("U1", true, 91, 85, "score:9", 0, 3L);
    }

    @Test
    void persistedBelowToAboveK4TransitionClaimsAndIncrementsExactlyOnce() {
        when(mapper.findK4KycTriggerStateForUpdate("U1")).thenReturn(
                new RiskOpsMapper.K4KycTriggerStateRecord("U1", false, 80, 85, "score:8", 1L, 4L));
        when(mapper.updateK4KycTriggerState("U1", true, 86, 85, "score:9", 1, 4L)).thenReturn(1);
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper, OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        boolean claimed = repository.transitionK4KycReviewTriggerState("U1", 86, 85, "score:9");

        assertThat(claimed).isTrue();
        verify(mapper).updateK4KycTriggerState("U1", true, 86, 85, "score:9", 1, 4L);
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
                .contains("NULL, 4, 'flag'")
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
                Map.of("multiAccount", 25, "arbitrage", 20, "kycStatus", 20,
                        "withdrawVelocity", 15, "accountAge", 10, "anomalyBehavior", 10),
                Map.of("multiAccount", true, "arbitrage", true, "kycStatus", true,
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
    void ensureRiskSchemaDoesNotSeedDataWhenReadTimeSeedsAreDisabled() {
        when(mapper.countKycTicketOpenUserKeyColumn()).thenReturn(1);
        when(mapper.kycTicketOpenUserKeyExpression())
                .thenReturn("CASE WHEN status IN ('triggered','in-review') AND is_deleted=0 THEN user_no ELSE NULL END");
        when(mapper.countKycTicketOpenUserUniqueKey()).thenReturn(1);
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
        verify(mapper).createKycAlertTable();
        verify(mapper).createKycReviewSourceTable();
        verify(mapper).backfillKycReviewSources();
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
        var boundaryOrder = inOrder(mapper);
        boundaryOrder.verify(mapper).mergeDuplicateOpenKycTickets();
        boundaryOrder.verify(mapper, org.mockito.Mockito.atLeastOnce()).countKycTicketOpenUserKeyColumn();
        boundaryOrder.verify(mapper).promoteTriggeredKycTickets();
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
    void k5SubscriptionReadIsPureAndReturnsVersionZeroDefaults() {
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper, OpsReadTimeSeedPolicy.disabledForDirectConstruction());
        when(mapper.findKycAlertSubscription("risk-admin")).thenReturn(null);

        Map<String, Object> subscription = repository.kycAlertSubscription("risk-admin");

        assertThat(subscription).containsEntry("version", 0L);
        assertThat(subscription.get("alertTypes")).isEqualTo(java.util.List.of("sla-breach"));
        verify(mapper, never()).ensureKycAlertSubscription(org.mockito.ArgumentMatchers.anyString());
        verify(mapper, never()).insertKycAlertSubscription(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void k5SubscriptionFirstWriteCreatesVersionOneAndDoesNotRunVersionZeroUpdate() {
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper, OpsReadTimeSeedPolicy.disabledForDirectConstruction());
        RiskOpsMapper.KycAlertSubscriptionRecord created = new RiskOpsMapper.KycAlertSubscriptionRecord(
                "risk-admin", "[\"sla-breach\"]", "[\"in-app\"]", 1L);
        when(mapper.findKycAlertSubscription("risk-admin")).thenReturn(null, created);
        when(mapper.insertKycAlertSubscription(
                "risk-admin", "[\"sla-breach\"]", "[\"in-app\"]")).thenReturn(1);

        var result = repository.updateKycAlertSubscription(
                "risk-admin", java.util.List.of("sla-breach"), java.util.List.of("in-app"), 0L);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow()).containsEntry("version", 1L);
        verify(mapper, never()).updateKycAlertSubscription(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void k5SubscriptionConcurrentFirstWriteDoesNotOverwriteWinner() {
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper, OpsReadTimeSeedPolicy.disabledForDirectConstruction());
        when(mapper.findKycAlertSubscription("risk-admin")).thenReturn(null);
        when(mapper.insertKycAlertSubscription(
                "risk-admin", "[\"sla-breach\"]", "[\"in-app\"]"))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("winner committed"));

        assertThat(repository.updateKycAlertSubscription(
                "risk-admin", java.util.List.of("sla-breach"), java.util.List.of("in-app"), 0L)).isEmpty();
        verify(mapper, never()).updateKycAlertSubscription(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void largeWithdrawalTicketPersistsItsAuthoritativeD2SourceLink() {
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper, OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        repository.createLargeWithdrawalKycReviewTicket(
                "KR-D2-1", "U00000001", new java.math.BigDecimal("1200"), "WD-1",
                "PENDING", "large withdrawal source", "risk-admin");

        verify(mapper).insertKycReviewSource("KR-D2-1", "D2", "WD-1");
    }

    @Test
    void normalizedTicketSourcesAreReturnedInMapperOrder() {
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper, OpsReadTimeSeedPolicy.disabledForDirectConstruction());
        when(mapper.kycReviewSources("KR-MULTI")).thenReturn(java.util.List.of(
                new RiskOpsMapper.KycReviewSourceRecord("D2", "WD-1"),
                new RiskOpsMapper.KycReviewSourceRecord("D2", "WD-2")));

        assertThat(repository.kycReviewSources("KR-MULTI")).containsExactly(
                new ffdd.opsconsole.risk.domain.RiskOpsRepository.KycReviewSource("D2", "WD-1"),
                new ffdd.opsconsole.risk.domain.RiskOpsRepository.KycReviewSource("D2", "WD-2"));
    }

    @Test
    void k5OverviewReadsBackHistoryAsTimeEventToneTriples() {
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper, OpsReadTimeSeedPolicy.disabledForDirectConstruction());
        String history = "[[\"2026-07-17 10:00:00\",\"复审通过·操作人:risk-admin\",\"\"],"
                + "[\"2026-07-17 10:01:00\",\"并入重复信号·操作人:risk-admin\",\"warn\"]]";
        when(mapper.countKycTicketsByFilter(null)).thenReturn(1L);
        String info = "[[\"触发原因\",\"initial\"],[\"触发原因\",\"merge-one\"],"
                + "[\"触发原因\",\"merge-two\"]]";
        when(mapper.pageKycReviewTickets(null, 0, 5)).thenReturn(java.util.List.of(
                new RiskOpsMapper.KycReviewTicketRecord(
                        "KR-1", "手动触发", "U00000001", "—", "—", "PENDING", "in-review",
                        0.1, "剩 7 天", info, history, 2L)));
        when(mapper.riskParams("k5")).thenReturn(java.util.List.of());

        Map<String, Object> overview = repository.kycReviewOverview(1, 5, null);
        var tickets = (ffdd.opsconsole.shared.api.PageResult<?>) overview.get("tickets");
        var row = (RiskOpsMapper.KycReviewTicketRecord) tickets.getRecords().get(0);

        assertThat(row.histJson()).isEqualTo(history);
        assertThat(row.histJson()).doesNotContain("\",\"risk-admin\"]");
        assertThat(row.infoJson()).contains("initial", "merge-one", "merge-two");
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

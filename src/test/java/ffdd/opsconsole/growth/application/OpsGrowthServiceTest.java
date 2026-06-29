package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.growth.dto.GrowthEarnMilestoneUpdateRequest;
import ffdd.opsconsole.growth.dto.GrowthConfigUpdateRequest;
import ffdd.opsconsole.growth.dto.GrowthVoucherRequest;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
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
        assertThat(result.getData().get("rules")).asList().hasSize(6);
        assertThat(result.getData().get("streakMilestones")).asList().hasSize(7);
        assertThat(result.getData().get("streakDistribution")).asList().hasSize(5);
        assertThat(result.getData().get("powerUps")).asList().hasSize(4);
        assertThat(result.getData().get("earnMilestones")).asList().hasSize(5);
        assertThat(result.getData()).containsKeys("tickInterval", "coverage");
    }

    @Test
    void trialsMasksServerOnlyFailureRateAndReturnsRuntimeRows() {
        configFacade.values.put("growth.trial.param.failRate", "4.1");

        ApiResult<Map<String, Object>> result = service.trials();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("domain", "H2")
                .containsEntry("autoPushKilled", false);
        assertThat(result.getData().toString()).doesNotContain("4.1");
        assertThat(result.getData().get("params")).asList().hasSize(12);
        assertThat(result.getData().get("sessions")).asList().hasSize(4);
        assertThat(result.getData().get("serverOnlyFields")).asList().contains("failRate");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> params = (List<Map<String, Object>>) result.getData().get("params");
        assertThat(params)
                .filteredOn(param -> "failRate".equals(param.get("key")))
                .singleElement()
                .extracting(param -> param.get("cur"))
                .isEqualTo("•••(server only)");
        assertThat(params)
                .extracting(param -> param.get("key"))
                .contains("trialDays", "graceDays", "extensionDays")
                .doesNotContain("days");
    }

    @Test
    void updateTrialParamWritesConfigAuditsAndStillMasksFailureRate() {
        ApiResult<Map<String, Object>> result = service.updateTrialParam(
                "idem-h2-param",
                "failRate",
                new GrowthConfigUpdateRequest("failRate", "6.5", "tune failure", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("growth.trial.param.failRate", "6.5");
        assertThat(result.getData().toString()).doesNotContain("6.5");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("H2_TRIAL_PARAM_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("serverOnly", true);
    }

    @Test
    void updateTrialDayParamsWritesSeparateNumericConfigItems() {
        ApiResult<Map<String, Object>> result = service.updateTrialParam(
                "idem-h2-trial-days",
                "trialDays",
                new GrowthConfigUpdateRequest("trialDays", "5", "extend trial window", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("growth.trial.param.trialDays", "5")
                .doesNotContainKey("growth.trial.param.days");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> params = (List<Map<String, Object>>) result.getData().get("params");
        assertThat(params)
                .filteredOn(param -> "trialDays".equals(param.get("key")))
                .singleElement()
                .extracting(param -> param.get("cur"))
                .isEqualTo("5");
    }

    @Test
    void splitTrialDaySeedCanMigrateLegacyCombinedDaysValue() {
        configFacade.values.put("growth.trial.param.days", "4 / 8 / 2 天");

        service.trials();

        assertThat(configFacade.values)
                .containsEntry("growth.trial.param.trialDays", "4")
                .containsEntry("growth.trial.param.graceDays", "8")
                .containsEntry("growth.trial.param.extensionDays", "2");
    }

    @Test
    void cancelTrialSessionWritesTerminalStateAndAudit() {
        ApiResult<Map<String, Object>> result = service.cancelTrialSession(
                "idem-h2-cancel",
                "usr_9921",
                new GrowthConfigUpdateRequest("cancel", "cancelled", "risk case", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("growth.trial.session.usr_9921.state", "cancelled")
                .containsKey("growth.trial.session.usr_9921.cancelled_at");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("H2_TRIAL_SESSION_CANCELLED");
    }

    @Test
    void chargeTerminalTrialSessionReturns409() {
        ApiResult<Map<String, Object>> result = service.chargeTrialSession(
                "idem-h2-charge",
                "usr_77D4",
                new GrowthConfigUpdateRequest("charge", "redeemed", "force charge", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("TRIAL_SESSION_ALREADY_TERMINAL");
    }

    @Test
    void killAutoPushWritesConfigAndAudit() {
        ApiResult<Map<String, Object>> result = service.killTrialAutoPush(
                "idem-h2-kill",
                new GrowthConfigUpdateRequest("autoPushKilled", "true", "incident", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("growth.trial.auto_push_killed", "true");
        assertThat(result.getData()).containsEntry("autoPushKilled", true);

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("H2_TRIAL_AUTO_PUSH_KILLED");
    }

    @Test
    void questEventsReturnsH3H4ReadModelAndSunsetOnly() {
        ApiResult<Map<String, Object>> result = service.questEvents();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("domain", "H3_H4")
                .containsEntry("rewardAsset", "NEX")
                .containsEntry("pointsSystemStatus", "SUNSET_HISTORY_ONLY");
        assertThat(result.getData().get("dayOneTasks")).asList().hasSize(6);
        assertThat(result.getData().get("weeklyTier1")).asList().hasSize(9);
        assertThat(result.getData().get("weeklyTier2")).asList().hasSize(8);
        assertThat(result.getData().get("monthlyMissions")).asList().hasSize(5);
        assertThat(result.getData().get("taskContracts")).asList().hasSize(6);
        assertThat(result.getData().get("events")).asList().hasSize(7);
        assertThat(result.getData().get("wheelTiers")).asList().hasSize(8);
        assertThat(result.getData().get("trackables")).asList().hasSize(4);
        assertThat(result.getData()).containsKeys("h3Stats", "h4Stats", "taskContracts", "promoBanner", "wheelEvUsd", "coverage", "sources");
        assertThat(result.getData().get("dayOneTasks")).asList()
                .first()
                .extracting(row -> ((Map<?, ?>) row).get("completionType"))
                .isEqualTo("event");
        assertThat(result.getData().get("promoBanner"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("status", "active");
    }

    @Test
    void questEventsReadsH3H4RowsFromConfigItems() {
        configFacade.values.put("growth.quest.day_one.tasks",
                "[{\"id\":0,\"task\":\"DB 首日任务\",\"href\":\"/db/day-one\",\"reward\":\"11 NEX\"}]");
        configFacade.values.put("growth.quest.weekly.t1.rows",
                "[{\"cond\":\"DB 一档\",\"reward\":\"22\"}]");
        configFacade.values.put("growth.quest.weekly.t2.rows",
                "[{\"cond\":\"DB 二档\",\"reward\":\"33\"}]");
        configFacade.values.put("growth.quest.monthly.rows",
                "[{\"id\":\"mcx\",\"theme\":\"DB 月度\",\"age\":\"1 月\",\"reward\":\"44\",\"goals\":\"DB 目标\"}]");
        configFacade.values.put("growth.quest.task_monitor",
                "[{\"label\":\"DB 监控\",\"note\":\"来自 nx_config_item\"}]");
        configFacade.values.put("growth.quest.task_contracts",
                "[{\"taskId\":0,\"taskKey\":\"db.task\",\"serverEvent\":\"db.server\",\"downstream\":\"db.downstream\",\"b3\":true,\"retentionOnly\":false,\"day7\":\"DB Day7\",\"bi\":\"db.bi\",\"sample24h\":1,\"anomalyPct\":\"0.1%\"}]");
        configFacade.values.put("growth.quest.promo_banner",
                "{\"baseReward\":\"900\",\"multiplier\":\"1.2\",\"countdownDays\":\"3\",\"countdownHours\":\"6\",\"targetDevice\":\"DB 设备\",\"targetDaily\":\"8.00\",\"status\":\"paused\"}");
        configFacade.values.put("growth.event.rows",
                "[{\"id\":\"db-event\",\"name\":\"DB 活动\",\"kind\":\"db\",\"state\":\"ongoing\",\"reward\":\"55\",\"featured\":true,\"trackable\":true,\"condition\":\"DB 条件\",\"geo\":\"全区\"}]");
        configFacade.values.put("growth.wheel.tiers",
                "[{\"tier\":\"DB 奖\",\"reward\":\"$10\",\"prob\":100,\"real\":true,\"kind\":\"真实流出\"}]");
        configFacade.values.put("growth.event.trackables",
                "[{\"id\":\"db-event\",\"name\":\"DB 活动\",\"cond\":\"DB 条件\",\"join\":\"1\",\"done\":\"1\",\"claim\":\"1\",\"geo\":\"全区\"}]");

        ApiResult<Map<String, Object>> result = service.questEvents();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("dayOneTasks")).asList()
                .hasSize(6)
                .first()
                .extracting(row -> ((Map<?, ?>) row).get("task"))
                .isEqualTo("DB 首日任务");
        assertThat(result.getData().get("dayOneTasks")).asList()
                .first()
                .extracting(row -> ((Map<?, ?>) row).get("completionType"))
                .isEqualTo("event");
        assertThat(result.getData().get("weeklyTier1")).asList()
                .hasSize(9)
                .first()
                .extracting(row -> ((Map<?, ?>) row).get("cond"))
                .isEqualTo("DB 一档");
        assertThat(result.getData().get("weeklyTier2")).asList().hasSize(8);
        assertThat(result.getData().get("monthlyMissions")).asList()
                .hasSize(6)
                .first()
                .extracting(row -> ((Map<?, ?>) row).get("theme"))
                .isEqualTo("DB 月度");
        assertThat(result.getData().get("taskMonitor")).asList()
                .singleElement()
                .extracting(row -> ((Map<?, ?>) row).get("note"))
                .isEqualTo("来自 nx_config_item");
        assertThat(result.getData().get("taskContracts")).asList()
                .hasSize(6)
                .first()
                .extracting(row -> ((Map<?, ?>) row).get("taskKey"))
                .isEqualTo("db.task");
        assertThat(result.getData().get("promoBanner"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("baseReward", "900")
                .containsEntry("status", "paused");
        assertThat(result.getData().get("events")).asList()
                .singleElement()
                .extracting(row -> ((Map<?, ?>) row).get("name"))
                .isEqualTo("DB 活动");
        assertThat(result.getData().get("wheelTiers")).asList()
                .singleElement()
                .extracting(row -> ((Map<?, ?>) row).get("tier"))
                .isEqualTo("DB 奖");
        assertThat(result.getData().get("trackables")).asList()
                .singleElement()
                .extracting(row -> ((Map<?, ?>) row).get("name"))
                .isEqualTo("DB 活动");
        assertThat(result.getData().get("wheelEvUsd").toString()).isEqualTo("10");
    }

    @Test
    void updateQuestConfigWritesAllowedConfigAndAudits() {
        ApiResult<Map<String, Object>> result = service.updateQuestConfig(
                "idem-h3-config",
                "dayOne.tasks.0.reward",
                new GrowthConfigUpdateRequest("reward", "80 NEX", "adjust day one", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("growth.quest.day_one.task.0.reward", "80 NEX");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("H3_QUEST_CONFIG_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-h3-config");
    }

    @Test
    void raisingQuestRewardBelowB1RedlineReturns422() {
        configFacade.values.put("growth.quest.day_one.task.0.reward", "50 NEX");
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));

        ApiResult<Map<String, Object>> result = service.updateQuestConfig(
                "idem-h3-redline",
                "dayOne.tasks.0.reward",
                new GrowthConfigUpdateRequest("reward", "500 NEX", "raise reward", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
    }

    @Test
    void updateQuestEventStatusWritesConfigAndAudit() {
        ApiResult<Map<String, Object>> result = service.updateQuestEventStatus(
                "idem-h4-status",
                "regional-pk",
                new GrowthConfigUpdateRequest("status", "ongoing", "launch event", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("growth.event.regional-pk.status", "ongoing");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("H4_EVENT_STATUS_CHANGED");
    }

    @Test
    void settingSecondFeaturedOngoingEventReturns422() {
        ApiResult<Map<String, Object>> result = service.updateQuestEventFeatured(
                "idem-h4-featured",
                "ref-5",
                new GrowthConfigUpdateRequest("featured", "true", "feature referral", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("EVENT_FEATURED_UNIQUE_VIOLATION");
    }

    @Test
    void wheelKillSwitchWritesGuardAndAudit() {
        ApiResult<Map<String, Object>> result = service.updateQuestConfig(
                "idem-h4-kill",
                "wheel.guards.kill",
                new GrowthConfigUpdateRequest("kill", "关", "regulatory incident", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("growth.wheel.guard.kill", "关");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("H4_WHEEL_GUARD_CHANGED");
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
    void luckyProbabilitySumAboveOneHundredReturns422() {
        ApiResult<Map<String, Object>> result = service.updateCheckInRule(
                "idem-h5-lucky",
                "p15",
                new GrowthConfigUpdateRequest("p15", "96", "raise lucky too far", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("LUCKY_PROBABILITY_SUM_EXCEEDS_100");
    }

    @Test
    void updateStreakMilestoneWritesConfigAndAudit() {
        ApiResult<Map<String, Object>> result = service.updateStreakMilestone(
                "idem-h5-ms",
                1,
                new GrowthConfigUpdateRequest("reward", "+20 NEX", "adjust streak", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("growth.checkin.streak_milestone.1.reward", "+20 NEX");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("H5_STREAK_MILESTONE_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-h5-ms");
    }

    @Test
    void updatePowerUpWritesThresholdAndAudit() {
        ApiResult<Map<String, Object>> result = service.updatePowerUp(
                "idem-h5-pu",
                2,
                new GrowthConfigUpdateRequest("day", "35", "adjust power up", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("growth.checkin.power_up.2.day", "35");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("H5_POWER_UP_CHANGED");
    }

    @Test
    void unorderedEarnMilestoneThresholdReturns422() {
        ApiResult<Map<String, Object>> result = service.updateEarnMilestone(
                "idem-h6-order",
                "earn-500",
                new GrowthEarnMilestoneUpdateRequest(
                        new BigDecimal("50"), new BigDecimal("250"), "break order", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("EARN_MILESTONE_THRESHOLD_ORDER_INVALID");
    }

    @Test
    void updateEarnMilestoneWritesThresholdRewardAndAudit() {
        ApiResult<Map<String, Object>> result = service.updateEarnMilestone(
                "idem-h6-earn",
                "earn-500",
                new GrowthEarnMilestoneUpdateRequest(
                        new BigDecimal("700"), new BigDecimal("300"), "adjust earn milestone", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("growth.earn_milestone.earn-500.threshold_usd", "700")
                .containsEntry("growth.earn_milestone.earn-500.reward_nex", "300");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("H6_EARN_MILESTONE_CHANGED");
    }

    @Test
    void updateTickIntervalEnforcesBoundsAndWritesAudit() {
        ApiResult<Map<String, Object>> tooLarge = service.updateEarnMilestoneTickInterval(
                "idem-h6-tick-bad",
                new GrowthConfigUpdateRequest("tick", "120", "too slow", "superadmin"));
        assertThat(tooLarge.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());

        ApiResult<Map<String, Object>> result = service.updateEarnMilestoneTickInterval(
                "idem-h6-tick",
                new GrowthConfigUpdateRequest("tick", "8", "tune cascade", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("growth.earn_milestone.tick_interval_seconds", "8");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("H6_TICK_INTERVAL_CHANGED");
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
        assertThat(result.getData())
                .containsEntry("dialCount", 8)
                .containsEntry("currentMonth", 7);
        assertThat(result.getData().get("monthlyDials")).asList().hasSize(12);
        assertThat(result.getData().get("controls")).asList().hasSize(3);
        assertThat(result.getData()).containsKey("coverage");
        assertThat(result.getData().get("sunsetExclusions"))
                .asList()
                .contains("Premium", "NEX v2", "Points");
        assertThat(configFacade.values)
                .containsKey("platform.phase.config")
                .containsEntry("growth.phase.month.7.withdrawNexMinBalance", "100")
                .containsEntry("growth.withdraw_nex_gate.min_balance_nex", "100");
    }

    @Test
    void updateRhythmCurrentMonthMirrorsH1PhaseForDownstreamReaders() {
        ApiResult<Map<String, Object>> result = service.updateRhythmParam(
                "idem-h1-rhythm",
                "currentMonth",
                new GrowthConfigUpdateRequest("currentMonth", "11", "advance rhythm", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("H1.rhythm.currentMonth", "11")
                .containsEntry("growth.phase.current_month", "11")
                .containsEntry("growth.phase.current", "P6");
        GrowthRhythmSnapshot snapshot = GrowthRhythmSnapshot.from(configFacade);
        assertThat(snapshot.currentMonth()).isEqualTo(11);
        assertThat(snapshot.currentPhase()).isEqualTo("P6");
    }

    @Test
    void phaseSandboxPreviewIsReadOnlyAndIncludesImpactMatrix() {
        ApiResult<Map<String, Object>> result = service.phaseSandboxPreview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("domain", "H1")
                .containsEntry("mode", "READ_ONLY_SANDBOX")
                .containsEntry("writes", false);
        assertThat(result.getData().get("impactMatrix")).asList().hasSize(5);
        assertThat(result.getData().get("retiredDials").toString()).contains("premiumUnlock", "nexV2Unlock");
    }

    @Test
    void updateCurrentMonthDialWritesMonthlyCellActiveDialMirrorAndAudit() {
        ApiResult<Map<String, Object>> result = service.updatePhaseMonthDial(
                "idem-h1-cell",
                7,
                "withdrawNexMinBalance",
                new GrowthConfigUpdateRequest("withdrawNexMinBalance", "150", "tighten current month", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("growth.phase.month.7.withdrawNexMinBalance", "150")
                .containsEntry("growth.withdraw_nex_gate.min_balance_nex", "150")
                .containsEntry("withdrawal.nex_gate.min_balance_nex", "150");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("H1_MONTH_DIAL_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("month", 7);
    }

    @Test
    void updatePhaseControlWritesConfigAndAudit() {
        ApiResult<Map<String, Object>> result = service.updatePhaseControl(
                "idem-h1-control",
                "pin",
                new GrowthConfigUpdateRequest("pin", "P3 until launch", "pin phase", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("growth.phase.control.pin", "P3 until launch");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("H1_PHASE_CONTROL_CHANGED");
    }

    @Test
    void disablePhaseOverrideWritesStateAndAudit() {
        ApiResult<Map<String, Object>> result = service.updatePhaseOverride(
                "idem-h1-override",
                "2026-W18",
                new GrowthConfigUpdateRequest("disabled", "true", "rollback cohort", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("growth.phase.override.2026-W18.disabled", "true");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("H1_PHASE_OVERRIDE_CHANGED");
    }

    @Test
    void h2ToH6ReadModelsSeedConfigItemsBeforeReturningData() {
        service.trials();
        service.questEvents();
        service.checkIn();

        assertThat(configFacade.values)
                .containsEntry("growth.trial.param.trialDays", "3")
                .containsEntry("growth.trial.param.graceDays", "7")
                .containsEntry("growth.trial.param.extensionDays", "3")
                .containsEntry("growth.trial.param.offsetCap", "$50")
                .containsEntry("growth.trial.session.usr_9921.state", "active")
                .containsKey("growth.quest.day_one.tasks")
                .containsKey("growth.quest.weekly.t1.rows")
                .containsKey("growth.quest.weekly.t2.rows")
                .containsKey("growth.quest.monthly.rows")
                .containsKey("growth.quest.task_monitor")
                .containsEntry("growth.quest.day_one.task.0.reward", "50 NEX")
                .containsKey("growth.event.rows")
                .containsKey("growth.event.trackables")
                .containsKey("growth.wheel.tiers")
                .containsEntry("growth.event.pro-7d.status", "ongoing")
                .containsEntry("growth.checkin.reward_nex", "2")
                .containsEntry("growth.earn_milestone.earn-500.reward_nex", "250");
    }

    @Test
    void vouchersSeedRowsAndPersistCrudToConfigItem() {
        ApiResult<Map<String, Object>> initial = service.vouchers();

        assertThat(initial.getCode()).isZero();
        assertThat(initial.getData().get("vouchers")).asList().hasSize(2);
        assertThat(configFacade.values)
                .containsKey("growth.voucher.rows")
                .containsKey("growth.voucher.sku_options");

        GrowthVoucherRequest request = new GrowthVoucherRequest(
                "vc-test-25",
                "Test Voucher",
                "fixed",
                new BigDecimal("25"),
                null,
                new BigDecimal("200"),
                BigDecimal.ZERO,
                List.of("stellarbox-s1"),
                "all",
                0L,
                0L,
                List.of("home", "store"),
                true,
                false,
                false,
                false,
                "active",
                "create voucher",
                "superadmin");
        ApiResult<Map<String, Object>> created = service.createVoucher("idem-h7-create", request);
        assertThat(created.getCode()).isZero();
        assertThat(configFacade.values.get("growth.voucher.rows")).contains("vc-test-25");

        ApiResult<Map<String, Object>> paused = service.updateVoucherStatus(
                "idem-h7-status",
                "vc-test-25",
                new GrowthConfigUpdateRequest("status", "paused", "pause voucher", "superadmin"));
        assertThat(paused.getCode()).isZero();
        assertThat(configFacade.values.get("growth.voucher.rows")).contains("\"status\":\"paused\"");

        ApiResult<Map<String, Object>> deleted = service.deleteVoucher(
                "idem-h7-delete",
                "vc-test-25",
                new GrowthConfigUpdateRequest("delete", "delete", "delete voucher", "superadmin"));
        assertThat(deleted.getCode()).isZero();
        assertThat(configFacade.values.get("growth.voucher.rows")).doesNotContain("vc-test-25");
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

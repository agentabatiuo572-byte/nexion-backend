package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import ffdd.opsconsole.growth.dto.GrowthEarnMilestoneUpdateRequest;
import ffdd.opsconsole.growth.dto.GrowthConfigUpdateRequest;
import ffdd.opsconsole.growth.dto.GrowthVoucherRequest;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.growth.mapper.GrowthQuestEventMapper;
import ffdd.opsconsole.growth.mapper.GrowthVoucherMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsGrowthServiceTest {
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final FakeEmergencyControlRepository emergencyRepository = new FakeEmergencyControlRepository();
    private final FakeTreasuryCoverageFacade coverageFacade = new FakeTreasuryCoverageFacade();
    private final FakeTreasuryLedgerPostingFacade ledgerPostingFacade = new FakeTreasuryLedgerPostingFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final GrowthQuestEventMapper questEventMapper = mock(GrowthQuestEventMapper.class);
    private final List<Map<String, Object>> trialPolicies = new ArrayList<>();
    private final List<Map<String, Object>> trialSessions = new ArrayList<>();
    private final List<Map<String, Object>> checkInRules = new ArrayList<>();
    private final Map<String, Object> checkInStats = new LinkedHashMap<>();
    private final List<Map<String, Object>> streakMilestones = new ArrayList<>();
    private final List<Map<String, Object>> powerUps = new ArrayList<>();
    private final List<Map<String, Object>> earnMilestones = new ArrayList<>();
    private final Map<String, List<Map<String, Object>>> missionRows = new LinkedHashMap<>();
    private final List<Map<String, Object>> monthlyMissions = new ArrayList<>();
    private final List<Map<String, Object>> taskMonitor = new ArrayList<>();
    private final List<Map<String, Object>> taskContracts = new ArrayList<>();
    private final Map<String, Object> promoBanner = new LinkedHashMap<>();
    private final List<Map<String, Object>> wheelTiers = new ArrayList<>();
    {
        when(questEventMapper.listEvents()).thenReturn(List.of());
        when(questEventMapper.countById(anyString())).thenReturn(0L);
        when(questEventMapper.listTrialPolicies()).thenAnswer(ignored -> trialPolicies);
        when(questEventMapper.trialPolicyValue(anyString())).thenAnswer(invocation -> findValue(trialPolicies, "key", invocation.getArgument(0), "cur"));
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            trialPolicies.removeIf(row -> key.equals(row.get("key")));
            trialPolicies.add(row(
                    "key", key,
                    "name", key,
                    "sub", "",
                    "cur", invocation.getArgument(1),
                    "hot", invocation.getArgument(3),
                    "section", invocation.getArgument(4),
                    "serverOnly", invocation.getArgument(5)));
            return 1;
        }).when(questEventMapper).upsertTrialPolicyValue(anyString(), anyString(), anyString(), anyBoolean(), anyString(), anyBoolean(), anyInt());
        when(questEventMapper.trialSessions(anyInt())).thenAnswer(invocation -> trialSessions.stream().limit((Integer) invocation.getArgument(0)).toList());
        when(questEventMapper.trialSession(anyString())).thenAnswer(invocation -> findRow(trialSessions, "sid", invocation.getArgument(0)));
        doAnswer(invocation -> {
            String sid = invocation.getArgument(0);
            String status = invocation.getArgument(1);
            Map<String, Object> row = findRow(trialSessions, "sid", sid);
            if (row == null) {
                return 0;
            }
            row.put("state", status.toLowerCase());
            return 1;
        }).when(questEventMapper).updateTrialSessionStatus(anyString(), anyString());
        when(questEventMapper.trialStats()).thenAnswer(ignored -> trialSessions.isEmpty()
                ? Map.of()
                : Map.of("activeSessions", trialSessions.size(), "inTrial", trialSessions.size(), "inGrace", 0, "inExtended", 0));
        when(questEventMapper.checkInStats()).thenAnswer(ignored -> checkInStats);
        when(questEventMapper.checkInRules()).thenAnswer(ignored -> checkInRules);
        when(questEventMapper.checkInRuleValue(anyString())).thenAnswer(invocation -> findValue(checkInRules, "key", invocation.getArgument(0), "cur"));
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            checkInRules.removeIf(row -> key.equals(row.get("key")));
            checkInRules.add(row(
                    "key", key,
                    "name", key,
                    "sub", "",
                    "cur", invocation.getArgument(1),
                    "hot", invocation.getArgument(3)));
            return 1;
        }).when(questEventMapper).upsertCheckInRuleValue(anyString(), anyString(), anyString(), anyBoolean(), anyInt());
        when(questEventMapper.streakMilestones()).thenAnswer(ignored -> streakMilestones);
        doAnswer(invocation -> {
            int id = invocation.getArgument(0);
            Map<String, Object> existing = findRow(streakMilestones, "id", String.valueOf(id));
            if (existing == null) {
                return 0;
            }
            existing.put("reward", invocation.getArgument(1, String.class));
            existing.put("kind", invocation.getArgument(2, String.class).toLowerCase());
            return 1;
        }).when(questEventMapper).updateStreakMilestoneReward(anyInt(), anyString(), anyString(), any(BigDecimal.class));
        when(questEventMapper.streakDistribution()).thenReturn(List.of());
        when(questEventMapper.powerUps()).thenAnswer(ignored -> powerUps);
        doAnswer(invocation -> {
            int id = invocation.getArgument(0);
            Map<String, Object> existing = findRow(powerUps, "id", String.valueOf(id));
            if (existing == null) {
                return 0;
            }
            existing.put("day", invocation.getArgument(1));
            return 1;
        }).when(questEventMapper).updatePowerUpDay(anyInt(), anyInt());
        doAnswer(invocation -> {
            int id = invocation.getArgument(0);
            Map<String, Object> existing = findRow(powerUps, "id", String.valueOf(id));
            if (existing == null) {
                return 0;
            }
            existing.put("sub", invocation.getArgument(1));
            return 1;
        }).when(questEventMapper).updatePowerUpNote(anyInt(), anyString());
        when(questEventMapper.earnMilestones()).thenAnswer(ignored -> earnMilestones);
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            Map<String, Object> existing = findRow(earnMilestones, "key", key);
            if (existing == null) {
                return 0;
            }
            existing.put("threshold", invocation.getArgument(1));
            existing.put("nex", invocation.getArgument(2));
            return 1;
        }).when(questEventMapper).updateEarnMilestoneRule(anyString(), any(BigDecimal.class), any(BigDecimal.class));
        when(questEventMapper.missionRows(anyString())).thenAnswer(invocation -> missionRows.getOrDefault(invocation.getArgument(0), List.of()));
        when(questEventMapper.monthlyMissions()).thenAnswer(ignored -> monthlyMissions);
        when(questEventMapper.taskMonitor()).thenAnswer(ignored -> taskMonitor);
        when(questEventMapper.taskContracts()).thenAnswer(ignored -> taskContracts);
        when(questEventMapper.promoBanner()).thenAnswer(ignored -> promoBanner);
        when(questEventMapper.wheelTiers()).thenAnswer(ignored -> wheelTiers);
        when(questEventMapper.wheelGuards()).thenReturn(List.of());
    }
    private final OpsGrowthService service =
            new OpsGrowthService(
                    configFacade,
                    emergencyRepository,
                    coverageFacade,
                    ledgerPostingFacade,
                    auditLogService,
                    new ObjectMapper(),
                    ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
                    Optional.empty(),
                    Optional.of(questEventMapper),
                    Optional.empty());

    @Test
    void checkInUsesNexAndKeepsPointsSunset() {
        seedCheckInConfiguredRows();
        seedEarnMilestones();

        ApiResult<Map<String, Object>> result = service.checkIn();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("rewardAsset", "NEX")
                .containsEntry("pointsSystemStatus", "SUNSET_HISTORY_ONLY");
        assertThat(result.getData().get("disabledOutputs").toString()).contains("Points ledger writes");
        assertThat(result.getData().get("rules")).asList().hasSize(6);
        assertThat(result.getData().get("streakMilestones")).asList().hasSize(7);
        assertThat(result.getData().get("streakDistribution")).asList().isEmpty();
        assertThat(result.getData().get("powerUps")).asList().hasSize(4);
        assertThat(result.getData().get("earnMilestones")).asList().hasSize(5);
        assertThat(result.getData().get("stats"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("todaySign", 12)
                .containsEntry("weekMsNex", "180 NEX");
        assertThat(result.getData()).containsKeys("tickInterval", "coverage");
    }

    @Test
    void trialsMasksServerOnlyFailureRateAndReturnsRuntimeRows() {
        seedTrialPolicies();

        ApiResult<Map<String, Object>> result = service.trials();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("domain", "H2")
                .containsEntry("autoPushKilled", false);
        assertThat(result.getData().toString()).doesNotContain("4.1");
        assertThat(result.getData().get("params")).asList().hasSize(5);
        assertThat(result.getData().get("sessions")).asList().isEmpty();
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
        seedTrialPolicies();
        ApiResult<Map<String, Object>> result = service.updateTrialParam(
                "idem-h2-param",
                "failRate",
                new GrowthConfigUpdateRequest("failRate", "6.5", "tune failure", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).doesNotContainKey("growth.trial.param.failRate");
        assertThat(result.getData().toString()).doesNotContain("6.5");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("H2_TRIAL_PARAM_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("serverOnly", true);
    }

    @Test
    void updateTrialDayParamsWritesSeparateNumericConfigItems() {
        seedTrialPolicies();
        ApiResult<Map<String, Object>> result = service.updateTrialParam(
                "idem-h2-trial-days",
                "trialDays",
                new GrowthConfigUpdateRequest("trialDays", "5", "extend trial window", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values)
                .doesNotContainKey("growth.trial.param.trialDays")
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
    void legacyCombinedTrialDaysValueIsNotMigratedByReadModel() {
        configFacade.values.put("growth.trial.param.days", "4 / 8 / 2 天");

        service.trials();

        assertThat(configFacade.values)
                .containsEntry("growth.trial.param.days", "4 / 8 / 2 天")
                .doesNotContainKeys(
                        "growth.trial.param.trialDays",
                        "growth.trial.param.graceDays",
                        "growth.trial.param.extensionDays");
    }

    @Test
    void cancelTrialSessionWritesTerminalStateAndAudit() {
        seedTrialSession("usr_9921", "active");

        ApiResult<Map<String, Object>> result = service.cancelTrialSession(
                "idem-h2-cancel",
                "usr_9921",
                new GrowthConfigUpdateRequest("cancel", "cancelled", "risk case", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(findRow(trialSessions, "sid", "usr_9921")).containsEntry("state", "cancelled");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("H2_TRIAL_SESSION_CANCELLED");
    }

    @Test
    void chargeTerminalTrialSessionReturns409() {
        seedTrialSession("usr_77D4", "redeemed");

        ApiResult<Map<String, Object>> result = service.chargeTrialSession(
                "idem-h2-charge",
                "usr_77D4",
                new GrowthConfigUpdateRequest("charge", "redeemed", "force charge", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("TRIAL_SESSION_ALREADY_TERMINAL");
    }

    @Test
    void chargeTrialSessionPostsD4LedgerEntry() {
        seedTrialPolicies();
        seedTrialSession("usr_9921", "active");

        ApiResult<Map<String, Object>> result = service.chargeTrialSession(
                "idem-h2-charge",
                "usr_9921",
                new GrowthConfigUpdateRequest("charge", "redeemed", "force charge", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(ledgerPostingFacade.entries).hasSize(1);
        Map<String, Object> entry = ledgerPostingFacade.entries.get(0);
        assertThat(entry)
                .containsEntry("bizNo", "H2-TRIAL-CHARGE-usr_9921")
                .containsEntry("userId", 9921L)
                .containsEntry("bizType", "TRIAL_CHARGE")
                .containsEntry("asset", "USDT")
                .containsEntry("direction", "OUT")
                .containsEntry("status", "SUCCESS");
        assertThat((BigDecimal) entry.get("amount")).isEqualByComparingTo("1299");
    }

    @Test
    void j1TrialKillSwitchBlocksTrialChargeAndIsVisibleInOverview() {
        emergencyRepository.settings.put("killswitch.trial", "disabled");
        seedTrialSession("usr_9921", "active");

        ApiResult<Map<String, Object>> overview = service.trials();
        ApiResult<Map<String, Object>> result = service.chargeTrialSession(
                "idem-h2-charge-blocked",
                "usr_9921",
                new GrowthConfigUpdateRequest("charge", "redeemed", "incident freeze", "superadmin"));

        assertThat(overview.getCode()).isZero();
        assertThat(detailMap(overview.getData().get("j1TrialGate")))
                .containsEntry("enabled", false)
                .containsEntry("blockedBy", "J1_TRIAL_KILL_SWITCH")
                .containsEntry("configKey", "killswitch.trial");
        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("TRIAL_KILL_SWITCH_DISABLED");
        assertThat(findRow(trialSessions, "sid", "usr_9921")).containsEntry("state", "active");
        assertThat(ledgerPostingFacade.entries).isEmpty();
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
        assertThat(result.getData().get("dayOneTasks")).asList().isEmpty();
        assertThat(result.getData().get("weeklyTier1")).asList().isEmpty();
        assertThat(result.getData().get("weeklyTier2")).asList().isEmpty();
        assertThat(result.getData().get("monthlyMissions")).asList().isEmpty();
        assertThat(result.getData().get("taskContracts")).asList().isEmpty();
        assertThat(result.getData().get("events")).asList().isEmpty();
        assertThat(result.getData().get("wheelTiers")).asList().isEmpty();
        assertThat(result.getData().get("trackables")).asList().isEmpty();
        assertThat(result.getData()).containsKeys("h3Stats", "h4Stats", "taskContracts", "promoBanner", "wheelEvUsd", "coverage", "sources");
        assertThat(result.getData().get("promoBanner"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .isEmpty();
    }

    @Test
    void questEventsReturnsEmptyRuntimeModelWhenReadTimeSeedsAreDisabled() {
        FakePlatformConfigFacade emptyConfig = new FakePlatformConfigFacade();
        OpsGrowthService noSeedService = serviceWithConfig(emptyConfig, OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        ApiResult<Map<String, Object>> result = noSeedService.questEvents();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("h3Stats")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).isEmpty();
        assertThat(result.getData().get("h4Stats"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("ongoing", 0L)
                .containsEntry("featuredEv", "--")
                .doesNotContainEntry("wheelToday", "$642 / ");
        assertThat(result.getData().get("dayOneTasks")).asList().isEmpty();
        assertThat(result.getData().get("weeklyTier1")).asList().isEmpty();
        assertThat(result.getData().get("weeklyTier2")).asList().isEmpty();
        assertThat(result.getData().get("weeklyMultipliers")).asList().isEmpty();
        assertThat(result.getData().get("monthlyMissions")).asList().isEmpty();
        assertThat(result.getData().get("events")).asList().isEmpty();
        assertThat(result.getData().get("wheelTiers")).asList().isEmpty();
        assertThat(emptyConfig.values).isEmpty();
    }

    @Test
    void updateQuestWindowCanCreateConfigWithoutSeedingBusinessRows() {
        FakePlatformConfigFacade emptyConfig = new FakePlatformConfigFacade();
        OpsGrowthService noSeedService = serviceWithConfig(emptyConfig, OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        ApiResult<Map<String, Object>> result = noSeedService.updateQuestConfig(
                "idem-h3-empty-window",
                "dayOne.windowMs",
                new GrowthConfigUpdateRequest("dayOne.windowMs", "48h 全额 / 96h 宽限", "initialize empty H3 config", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(emptyConfig.values)
                .containsEntry("growth.quest.day_one.window_ms", "48h 全额 / 96h 宽限")
                .doesNotContainKey("growth.quest.day_one.tasks")
                .doesNotContainKey("growth.event.rows")
                .doesNotContainKey("growth.wheel.tiers");
        assertThat(result.getData()).containsEntry("dayOneWindow", "");
        assertThat(result.getData().get("dayOneTasks")).asList().isEmpty();
    }

    @Test
    void questEventsReadsH3ConfigAndH4EventBusinessRows() {
        seedQuestBusinessRows();
        configFacade.values.put("growth.quest.day_one.tasks",
                "[{\"id\":0,\"task\":\"DB 首日任务\",\"href\":\"/db/day-one\",\"reward\":\"11 NEX\",\"completionType\":\"event\"}]");
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
        configFacade.values.put("growth.quest.day_one.window_ms", "配置窗口不应生效");
        configFacade.values.put("growth.quest.day_one.tri_reward", "配置首日奖励不应生效");
        configFacade.values.put("growth.quest.weekly.champ_bonus", "配置周冠军不应生效");
        configFacade.values.put("growth.quest.promo_banner",
                "{\"baseReward\":\"配置横幅不应生效\",\"multiplier\":\"9.9\",\"countdownDays\":\"9\",\"countdownHours\":\"9\",\"targetDevice\":\"配置设备\",\"targetDaily\":\"99.00\",\"status\":\"config\"}");
        configFacade.values.put("growth.event.rows",
                "[{\"id\":\"mock-event\",\"name\":\"配置活动不应生效\",\"kind\":\"db\",\"state\":\"ongoing\",\"reward\":\"55\",\"featured\":true,\"trackable\":true,\"condition\":\"DB 条件\",\"geo\":\"全区\"}]");
        seedQuestEvents(
                "{\"id\":\"db-event\",\"name\":\"DB 活动\",\"kind\":\"db\",\"state\":\"ongoing\",\"reward\":\"55\",\"featured\":true,\"trackable\":true,\"condition\":\"DB 条件\",\"geo\":\"全区\"}");
        configFacade.values.put("growth.wheel.pool_signature", "配置转盘奖池不应生效");
        configFacade.values.put("growth.wheel.tiers",
                "[{\"tier\":\"DB 奖\",\"reward\":\"$10\",\"prob\":100,\"real\":true,\"kind\":\"真实流出\"}]");
        configFacade.values.put("growth.event.trackables",
                "[{\"id\":\"db-event\",\"name\":\"DB 活动\",\"cond\":\"DB 条件\",\"join\":\"1\",\"done\":\"1\",\"claim\":\"1\",\"geo\":\"全区\"}]");

        ApiResult<Map<String, Object>> result = service.questEvents();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("dayOneWindow", "1 个首日任务定义来自 nx_mission")
                .containsEntry("dayOneTriReward", "11 NEX")
                .containsEntry("weeklyChampionBonus", "22 / 33")
                .containsEntry("wheelSignature", "1 档 · EV $10/spin");
        assertThat(result.getData().get("dayOneTasks")).asList()
                .hasSize(1)
                .first()
                .extracting(row -> ((Map<?, ?>) row).get("task"))
                .isEqualTo("DB 首日任务");
        assertThat(result.getData().get("dayOneTasks")).asList()
                .first()
                .extracting(row -> ((Map<?, ?>) row).get("completionType"))
                .isEqualTo("event");
        assertThat(result.getData().get("weeklyTier1")).asList()
                .hasSize(1)
                .first()
                .extracting(row -> ((Map<?, ?>) row).get("cond"))
                .isEqualTo("DB 一档");
        assertThat(result.getData().get("weeklyTier2")).asList().hasSize(1);
        assertThat(result.getData().get("monthlyMissions")).asList()
                .hasSize(1)
                .first()
                .extracting(row -> ((Map<?, ?>) row).get("theme"))
                .isEqualTo("DB 月度");
        assertThat(result.getData().get("taskMonitor")).asList()
                .singleElement()
                .extracting(row -> ((Map<?, ?>) row).get("note"))
                .isEqualTo("来自 nx_mission");
        assertThat(result.getData().get("taskContracts")).asList()
                .hasSize(1)
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
        assertThat(result.getData().get("events").toString()).doesNotContain("配置活动不应生效");
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
        seedQuestBusinessRows();

        ApiResult<Map<String, Object>> result = service.updateQuestConfig(
                "idem-h3-config",
                "weekly.mult.P3",
                new GrowthConfigUpdateRequest("weekly.mult.P3", "1.3x", "adjust weekly multiplier", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("growth.quest.weekly.mult.P3", "1.3×");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("H3_QUEST_CONFIG_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-h3-config");
    }

    @Test
    void raisingQuestRewardBelowB1RedlineReturns422() {
        configFacade.values.put("growth.quest.weekly.mult.P3", "1.0×");
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));

        ApiResult<Map<String, Object>> result = service.updateQuestConfig(
                "idem-h3-redline",
                "weekly.mult.P3",
                new GrowthConfigUpdateRequest("weekly.mult.P3", "2.0x", "raise multiplier", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
    }

    @Test
    void updateQuestEventStatusWritesBusinessTableAndAudit() {
        seedQuestEvents(
                "{\"id\":\"regional-pk\",\"name\":\"Regional PK\",\"state\":\"draft\",\"reward\":\"10 NEX\",\"featured\":false}");

        ApiResult<Map<String, Object>> result = service.updateQuestEventStatus(
                "idem-h4-status",
                "regional-pk",
                new GrowthConfigUpdateRequest("status", "ongoing", "launch event", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).doesNotContainKey("growth.event.regional-pk.status");
        verify(questEventMapper).updateStatus(anyString(), anyInt(), any());

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("H4_EVENT_STATUS_CHANGED");
    }

    @Test
    void trialKillSwitchBlocksH4EventMutations() {
        emergencyRepository.settings.put("killswitch.trial", "disabled");

        ApiResult<Map<String, Object>> result = service.updateQuestEventStatus(
                "idem-h4-status-killed",
                "regional-pk",
                new GrowthConfigUpdateRequest("status", "ongoing", "launch event", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("J1_TRIAL_KILLSWITCH_DISABLED");
        assertThat(configFacade.values).doesNotContainKey("growth.event.regional-pk.status");
    }

    @Test
    void trialKillSwitchDoesNotBlockH3ConfigUpdates() {
        emergencyRepository.settings.put("killswitch.trial", "disabled");

        ApiResult<Map<String, Object>> result = service.updateQuestConfig(
                "idem-h3-config-killed-trial",
                "dayOne.windowMs",
                new GrowthConfigUpdateRequest("dayOne.windowMs", "48h 全额 / 96h 宽限", "adjust H3 task window", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("growth.quest.day_one.window_ms", "48h 全额 / 96h 宽限");
    }

    @Test
    void settingSecondFeaturedOngoingEventReturns422() {
        seedQuestEvents(
                "{\"id\":\"current-featured\",\"name\":\"Current\",\"state\":\"ongoing\",\"reward\":\"10 NEX\",\"featured\":true}",
                "{\"id\":\"ref-5\",\"name\":\"Referral\",\"state\":\"ongoing\",\"reward\":\"10 NEX\",\"featured\":false}");

        ApiResult<Map<String, Object>> result = service.updateQuestEventFeatured(
                "idem-h4-featured",
                "ref-5",
                new GrowthConfigUpdateRequest("featured", "true", "feature referral", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("EVENT_FEATURED_UNIQUE_VIOLATION");
    }

    @Test
    void disabledBooleanAliasTurnsOffEventFeatured() {
        seedQuestEvents(
                "{\"id\":\"pro-7d\",\"name\":\"Pro 7d\",\"state\":\"ongoing\",\"reward\":\"10 NEX\",\"featured\":true}");

        ApiResult<Map<String, Object>> result = service.updateQuestEventFeatured(
                "idem-h4-featured-off",
                "pro-7d",
                new GrowthConfigUpdateRequest("featured", "disabled", "pause hero placement", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).doesNotContainKey("growth.event.pro-7d.featured");
        verify(questEventMapper).updateFeatured(anyString(), any(), any());
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
        seedCheckInConfiguredRows();

        ApiResult<Map<String, Object>> result = service.updateCheckInRule(
                "idem-h5-lucky",
                "p15",
                new GrowthConfigUpdateRequest("p15", "96", "raise lucky too far", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("LUCKY_PROBABILITY_SUM_EXCEEDS_100");
    }

    @Test
    void updateStreakMilestoneWritesConfigAndAudit() {
        seedCheckInConfiguredRows();

        ApiResult<Map<String, Object>> result = service.updateStreakMilestone(
                "idem-h5-ms",
                1,
                new GrowthConfigUpdateRequest("reward", "+20 NEX", "adjust streak", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).doesNotContainKey("growth.checkin.streak_milestone.1.reward");
        assertThat(findRow(streakMilestones, "id", "1")).containsEntry("reward", "+20 NEX");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("H5_STREAK_MILESTONE_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-h5-ms");
    }

    @Test
    void updatePowerUpWritesThresholdAndAudit() {
        seedCheckInConfiguredRows();

        ApiResult<Map<String, Object>> result = service.updatePowerUp(
                "idem-h5-pu",
                2,
                new GrowthConfigUpdateRequest("day", "35", "adjust power up", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).doesNotContainKey("growth.checkin.power_up.2.day");
        assertThat(findRow(powerUps, "id", "2")).containsEntry("day", 35);

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("H5_POWER_UP_CHANGED");
    }

    @Test
    void unorderedEarnMilestoneThresholdReturns422() {
        seedEarnMilestones();

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
        seedEarnMilestones();

        ApiResult<Map<String, Object>> result = service.updateEarnMilestone(
                "idem-h6-earn",
                "earn-500",
                new GrowthEarnMilestoneUpdateRequest(
                        new BigDecimal("700"), new BigDecimal("300"), "adjust earn milestone", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values)
                .doesNotContainKeys(
                        "growth.earn_milestone.earn-500.threshold_usd",
                        "growth.earn_milestone.earn-500.reward_nex");
        Map<String, Object> updated = findRow(earnMilestones, "key", "earn-500");
        assertThat(updated)
                .containsEntry("threshold", new BigDecimal("700").stripTrailingZeros())
                .containsEntry("nex", new BigDecimal("300").stripTrailingZeros());

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
    void withdrawGateReadsI4DisclosureGateAsBlocked() {
        emergencyRepository.settings.put("disclosure.gate.staking", "true");

        ApiResult<Map<String, Object>> result = service.withdrawGate();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("enabled", false)
                .containsEntry("blockedBy", "I4_DISCLOSURE_GATE");
        assertThat(detailMap(result.getData().get("disclosureGate"))).containsEntry("staking", true);
        assertThat(result.getData().get("sources"))
                .asList()
                .contains("nx_emergency_control_setting:disclosure.gate.staking");
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
    void phaseOverviewDoesNotSeedDialsOrWithdrawGateWhenDatabaseIsEmpty() {
        ApiResult<Map<String, Object>> result = service.phases();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("dialCount", 0)
                .containsEntry("currentMonth", 0);
        assertThat(result.getData().get("monthlyDials")).asList().isEmpty();
        assertThat(result.getData().get("controls")).asList().isEmpty();
        assertThat(result.getData()).containsKey("coverage");
        assertThat(result.getData().get("sunsetExclusions"))
                .asList()
                .isEmpty();
        assertThat(configFacade.values)
                .doesNotContainKeys(
                        "platform.phase.config",
                        "growth.phase.month.7.withdrawNexMinBalance",
                        "growth.withdraw_nex_gate.min_balance_nex");
    }

    @Test
    void phaseOverviewReadsSunsetExclusionsFromConfig() {
        configFacade.values.put("growth.sunset.exclusions", "Premium, NEX v2; Points");

        ApiResult<Map<String, Object>> result = service.phases();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("sunsetExclusions"))
                .asList()
                .containsExactly("Premium", "NEX v2", "Points");
    }

    @Test
    void updateRhythmCurrentMonthMirrorsH1PhaseForDownstreamReaders() {
        configFacade.values.put("H1.rhythm.totalMonths", "12");

        ApiResult<Map<String, Object>> result = service.updateRhythmParam(
                "idem-h1-rhythm",
                "currentMonth",
                new GrowthConfigUpdateRequest("currentMonth", "11", "advance rhythm", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("H1.rhythm.currentMonth", "11")
                .containsEntry("growth.phase.current_month", "11")
                .containsEntry("growth.phase.current", "P6");
        GrowthRhythmSnapshot snapshot = GrowthRhythmSnapshot.from(configFacade, OpsReadTimeSeedPolicy.enabledForDirectConstruction());
        assertThat(snapshot.currentMonth()).isEqualTo(11);
        assertThat(snapshot.currentPhase()).isEqualTo("P6");
    }

    @Test
    void disabledReadTimeSeedsDoNotExposeH1DemoRhythmOrPhaseRows() {
        OpsGrowthService realOnlyService = new OpsGrowthService(
                new FakePlatformConfigFacade(),
                new FakeEmergencyControlRepository(),
                coverageFacade,
                ledgerPostingFacade,
                mock(AuditLogService.class),
                new ObjectMapper(),
                OpsReadTimeSeedPolicy.disabledForDirectConstruction(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        ApiResult<Map<String, Object>> rhythm = realOnlyService.rhythm();
        ApiResult<Map<String, Object>> phases = realOnlyService.phases();

        assertThat(rhythm.getCode()).isZero();
        assertThat(rhythm.getData())
                .containsEntry("totalMonths", 0)
                .containsEntry("currentMonth", 0)
                .containsEntry("currentPhase", "")
                .containsEntry("phaseProgressPct", 0);
        assertThat(phases.getCode()).isZero();
        assertThat(phases.getData())
                .containsEntry("currentMonth", 0)
                .containsEntry("currentPhase", "");
        assertThat(phases.getData().get("monthlyDials")).asList().isEmpty();
        assertThat(phases.getData().get("controls")).asList().isEmpty();
        assertThat(phases.getData().get("overrides")).asList().isEmpty();
        assertThat(phases.getData().get("attribution")).asList().isEmpty();
    }

    @Test
    void disabledReadTimeSeedsDoNotExposeH2OrH5DemoStats() {
        OpsGrowthService realOnlyService = new OpsGrowthService(
                new FakePlatformConfigFacade(),
                new FakeEmergencyControlRepository(),
                coverageFacade,
                ledgerPostingFacade,
                mock(AuditLogService.class),
                new ObjectMapper(),
                OpsReadTimeSeedPolicy.disabledForDirectConstruction(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        ApiResult<Map<String, Object>> trials = realOnlyService.trials();
        ApiResult<Map<String, Object>> checkIn = realOnlyService.checkIn();

        assertThat(trials.getCode()).isZero();
        assertThat(trials.getData().get("stats")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).isEmpty();
        assertThat(checkIn.getCode()).isZero();
        assertThat(checkIn.getData().get("earnMilestones")).asList().isEmpty();
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
        configFacade.values.put("H1.rhythm.totalMonths", "12");
        configFacade.values.put("H1.rhythm.currentMonth", "7");

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
    void h2ToH6ReadModelsDoNotSeedConfigItemsBeforeReturningData() {
        service.trials();
        service.questEvents();
        service.checkIn();

        assertThat(configFacade.values).isEmpty();
    }

    @Test
    void h2ToH7ReadModelsDoNotCreateRuntimeTables() {
        service.trials();
        service.questEvents();
        service.checkIn();

        verify(questEventMapper, never()).createGrowthTrialPolicyTable();
        verify(questEventMapper, never()).createGrowthTrialGateTable();
        verify(questEventMapper, never()).createGrowthCheckinRuleTable();
        verify(questEventMapper, never()).createGrowthWheelTierTable();
        verify(questEventMapper, never()).createGrowthWheelGuardTable();
        verify(questEventMapper, never()).createGrowthPromoBannerTable();
        verify(questEventMapper, never()).addEventQuestGeoScopeColumn();

        GrowthVoucherMapper voucherMapper = mock(GrowthVoucherMapper.class);
        when(voucherMapper.listVouchers()).thenReturn(List.of());
        OpsGrowthService voucherService = new OpsGrowthService(
                configFacade,
                emergencyRepository,
                coverageFacade,
                ledgerPostingFacade,
                auditLogService,
                new ObjectMapper(),
                ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(voucherMapper));

        ApiResult<Map<String, Object>> vouchers = voucherService.vouchers();

        assertThat(vouchers.getCode()).isZero();
        assertThat(vouchers.getData().get("vouchers")).asList().isEmpty();
        verify(voucherMapper, never()).ensureTable();
    }

    @Test
    void vouchersReadAndPersistCrudToBusinessTable() {
        GrowthVoucherMapper voucherMapper = mock(GrowthVoucherMapper.class);
        OpsGrowthService h7Service = new OpsGrowthService(
                configFacade,
                emergencyRepository,
                coverageFacade,
                ledgerPostingFacade,
                auditLogService,
                new ObjectMapper(),
                ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(voucherMapper));
        Map<String, Object> createdRow = voucherDbRow("vc-test-25", "active");
        Map<String, Object> pausedRow = voucherDbRow("vc-test-25", "paused");
        when(voucherMapper.listVouchers()).thenReturn(
                List.of(),
                List.of(),
                List.of(createdRow),
                List.of(createdRow),
                List.of(pausedRow),
                List.of(pausedRow),
                List.of());
        when(voucherMapper.insertVoucher(anyString(), anyString(), anyString(), any(BigDecimal.class),
                any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class), anyString(),
                anyString(), anyLong(), anyLong(), anyString(), anyBoolean(),
                anyBoolean(), anyBoolean(), anyBoolean(), anyString(), anyString())).thenReturn(1);
        when(voucherMapper.updateStatus(anyString(), anyString(), anyString())).thenReturn(1);
        when(voucherMapper.softDelete(anyString(), anyString())).thenReturn(1);

        ApiResult<Map<String, Object>> initial = h7Service.vouchers();

        assertThat(initial.getCode()).isZero();
        assertThat(initial.getData().get("vouchers")).asList().isEmpty();
        assertThat(initial.getData().get("sources").toString()).contains("nx_growth_voucher");

        GrowthVoucherRequest request = new GrowthVoucherRequest(
                "vc-test-25",
                "Test Voucher",
                "fixed",
                new BigDecimal("25"),
                null,
                new BigDecimal("200"),
                BigDecimal.ZERO,
                List.of(),
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
        ApiResult<Map<String, Object>> created = h7Service.createVoucher("idem-h7-create", request);
        assertThat(created.getCode()).as(created.getMessage()).isZero();
        assertThat(created.getData().get("vouchers").toString()).contains("vc-test-25");
        assertThat(configFacade.values).doesNotContainKey("growth.voucher.rows");

        ApiResult<Map<String, Object>> paused = h7Service.updateVoucherStatus(
                "idem-h7-status",
                "vc-test-25",
                new GrowthConfigUpdateRequest("status", "paused", "pause voucher", "superadmin"));
        assertThat(paused.getCode()).isZero();
        assertThat(paused.getData().get("vouchers").toString()).contains("paused");

        ApiResult<Map<String, Object>> deleted = h7Service.deleteVoucher(
                "idem-h7-delete",
                "vc-test-25",
                new GrowthConfigUpdateRequest("delete", "delete", "delete voucher", "superadmin"));
        assertThat(deleted.getCode()).isZero();
        assertThat(deleted.getData().get("vouchers")).asList().isEmpty();
    }

    private static Map<String, Object> voucherDbRow(String voucherId, String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", voucherId);
        row.put("name", "Test Voucher");
        row.put("type", "fixed");
        row.put("amountUSD", new BigDecimal("25"));
        row.put("percent", BigDecimal.ZERO);
        row.put("minPurchaseUSD", new BigDecimal("200"));
        row.put("maxDiscountUSD", BigDecimal.ZERO);
        row.put("applicableSkusJson", "[]");
        row.put("audience", "all");
        row.put("startAt", 0L);
        row.put("endAt", 0L);
        row.put("claimSurfacesJson", "[\"home\",\"store\"]");
        row.put("popupEnabled", 1);
        row.put("stackWithTrial", 0);
        row.put("stackWithOthers", 0);
        row.put("splittable", 0);
        row.put("status", status);
        return row;
    }

    private OpsGrowthService serviceWithConfig(FakePlatformConfigFacade config, OpsReadTimeSeedPolicy seedPolicy) {
        return new OpsGrowthService(
                config,
                emergencyRepository,
                coverageFacade,
                ledgerPostingFacade,
                auditLogService,
                new ObjectMapper(),
                seedPolicy,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
    }

    private static Map<String, Object> findRow(List<Map<String, Object>> rows, String key, String value) {
        return rows.stream()
                .filter(row -> value.equals(String.valueOf(row.get(key))))
                .findFirst()
                .orElse(null);
    }

    private static String findValue(List<Map<String, Object>> rows, String keyField, String key, String valueField) {
        Map<String, Object> row = findRow(rows, keyField, key);
        return row == null ? null : String.valueOf(row.get(valueField));
    }

    @SuppressWarnings("unchecked")
    private void seedQuestEvents(String... rows) {
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> events = new ArrayList<>();
        try {
            for (String raw : rows) {
                events.add(new LinkedHashMap<>(mapper.readValue(raw, Map.class)));
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
        when(questEventMapper.listEvents()).thenReturn(events);
        for (Map<String, Object> event : events) {
            when(questEventMapper.countById(String.valueOf(event.get("id")))).thenReturn(1L);
        }
        doAnswer(invocation -> {
            String eventId = invocation.getArgument(0);
            int status = invocation.getArgument(1);
            events.stream()
                    .filter(event -> eventId.equals(event.get("id")))
                    .findFirst()
                    .ifPresent(event -> event.put("state", status == 0 ? "upcoming" : status == 2 ? "ended" : "ongoing"));
            return 1;
        }).when(questEventMapper).updateStatus(anyString(), anyInt(), any());
        doAnswer(invocation -> {
            String eventId = invocation.getArgument(0);
            String badge = invocation.getArgument(1);
            events.stream()
                    .filter(event -> eventId.equals(event.get("id")))
                    .findFirst()
                    .ifPresent(event -> event.put("featured", "FEATURED".equals(badge)));
            return 1;
        }).when(questEventMapper).updateFeatured(anyString(), any(), any());
        doAnswer(invocation -> {
            String eventId = invocation.getArgument(0);
            String reward = invocation.getArgument(1);
            events.stream()
                    .filter(event -> eventId.equals(event.get("id")))
                    .findFirst()
                    .ifPresent(event -> event.put("reward", reward));
            return 1;
        }).when(questEventMapper).updateReward(anyString(), anyString(), any(), any());
    }

    private void seedEarnMilestones() {
        earnMilestones.clear();
        earnMilestones.add(row("id", 0, "key", "earn-100", "threshold", 100, "nex", 100, "weekTrigger", 912));
        earnMilestones.add(row("id", 1, "key", "earn-500", "threshold", 500, "nex", 250, "weekTrigger", 624));
        earnMilestones.add(row("id", 2, "key", "earn-1000", "threshold", 1000, "nex", 500, "weekTrigger", 388));
        earnMilestones.add(row("id", 3, "key", "earn-5000", "threshold", 5000, "nex", 1500, "weekTrigger", 210));
        earnMilestones.add(row("id", 4, "key", "earn-10000", "threshold", 10000, "nex", 3000, "weekTrigger", 80));
    }

    private void seedCheckInConfiguredRows() {
        checkInStats.clear();
        checkInStats.putAll(row(
                "todaySign", 12,
                "signRate", "60%",
                "lucky15Actual", "16.7%",
                "lucky2Actual", "8.3%",
                "weekRevive", 3,
                "weekMsTrigger", 9,
                "weekMsNex", "180 NEX"));
        checkInRules.clear();
        checkInRules.add(row("key", "baseline", "name", "每日基础 NEX", "sub", "", "cur", "2", "hot", false));
        checkInRules.add(row("key", "bonus7", "name", "连续 7 天加奖", "sub", "", "cur", "5", "hot", false));
        checkInRules.add(row("key", "luckyMultiplierMax", "name", "幸运倍率上限", "sub", "", "cur", "2", "hot", true));
        checkInRules.add(row("key", "p15", "name", "幸运 1.5x 概率", "sub", "", "cur", "15", "hot", true));
        checkInRules.add(row("key", "p2", "name", "幸运 2x 概率", "sub", "", "cur", "5", "hot", true));
        checkInRules.add(row("key", "broken", "name", "断签阈值", "sub", "", "cur", "48", "hot", false));
        streakMilestones.clear();
        for (int i = 0; i < 7; i++) {
            streakMilestones.add(row("id", i, "day", (i + 1) + " 天", "reward", "+" + (i + 1) + " NEX", "kind", "nex"));
        }
        powerUps.clear();
        for (int i = 0; i < 4; i++) {
            powerUps.add(row("id", i, "day", (i + 1) * 7, "label", "power " + i, "sub", "business row", "downstream", "H5"));
        }
    }

    private void seedTrialPolicies() {
        trialPolicies.clear();
        trialPolicies.add(row("key", "trialDays", "name", "trialDays", "sub", "", "cur", "3", "hot", false, "section", "newonly", "serverOnly", false));
        trialPolicies.add(row("key", "graceDays", "name", "graceDays", "sub", "", "cur", "7", "hot", false, "section", "newonly", "serverOnly", false));
        trialPolicies.add(row("key", "extensionDays", "name", "extensionDays", "sub", "", "cur", "3", "hot", false, "section", "newonly", "serverOnly", false));
        trialPolicies.add(row("key", "price", "name", "price", "sub", "", "cur", "$1,299", "hot", true, "section", "newonly", "serverOnly", false));
        trialPolicies.add(row("key", "failRate", "name", "failRate", "sub", "", "cur", "4.1", "hot", true, "section", "live", "serverOnly", true));
    }

    private void seedTrialSession(String sid, String state) {
        trialSessions.removeIf(row -> sid.equals(row.get("sid")));
        trialSessions.add(row("sid", sid, "state", state, "shadow", "$1299", "cardTok", sid));
    }

    private void seedQuestBusinessRows() {
        missionRows.put("DAY_ONE", new ArrayList<>(List.of(row("id", 0, "task", "DB 首日任务", "href", "/db/day-one", "reward", "11 NEX", "completionType", "event"))));
        missionRows.put("WEEKLY_T1", new ArrayList<>(List.of(row("cond", "DB 一档", "reward", "22"))));
        missionRows.put("WEEKLY_T2", new ArrayList<>(List.of(row("cond", "DB 二档", "reward", "33"))));
        monthlyMissions.clear();
        monthlyMissions.add(row("id", "mcx", "theme", "DB 月度", "age", "1 月", "reward", "44", "goals", "DB 目标"));
        taskMonitor.clear();
        taskMonitor.add(row("label", "DB 监控", "note", "来自 nx_mission"));
        taskContracts.clear();
        taskContracts.add(row("taskId", 0, "taskKey", "db.task", "serverEvent", "db.server", "downstream", "db.downstream", "b3", true, "retentionOnly", false, "day7", "DB Day7", "bi", "db.bi", "sample24h", 1, "anomalyPct", "0.1%"));
        promoBanner.clear();
        promoBanner.putAll(row("baseReward", "900", "multiplier", "1.2", "countdownDays", 3, "countdownHours", 6,
                "targetDevice", "DB 设备", "targetDaily", "8.00", "status", "paused"));
        wheelTiers.clear();
        wheelTiers.add(row("tier", "DB 奖", "reward", "$10", "prob", 100, "real", true, "kind", "真实流出"));
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            row.put(String.valueOf(values[i]), values[i + 1]);
        }
        return row;
    }

    private static final class FakeEmergencyControlRepository implements EmergencyControlRepository {
        private final Map<String, String> settings = new LinkedHashMap<>();

        @Override
        public void ensureTables() {
        }

        @Override
        public List<Map<String, Object>> geoCountryPolicies() {
            return List.of();
        }

        @Override
        public void upsertGeoCountryPolicy(String countryCode, String countryName, String status, String reason, String operator) {
        }

        @Override
        public List<Map<String, Object>> geoEndpointCatalogs() {
            return List.of();
        }

        @Override
        public Optional<Map<String, Object>> geoEndpointCatalog(String endpointKey) {
            return Optional.empty();
        }

        @Override
        public List<Map<String, Object>> geoEndpointPolicies() {
            return List.of();
        }

        @Override
        public void replaceGeoEndpointPolicies(String endpointKey, String endpointPath, String label, String biz, String domain,
                                               List<String> countryCodes, String source, String reason, String operator) {
        }

        @Override
        public List<Map<String, Object>> geoHits() {
            return List.of();
        }

        @Override
        public Map<String, Integer> geoEndpointHits() {
            return Map.of();
        }

        @Override
        public List<Map<String, Object>> geoEdgeMetrics() {
            return List.of();
        }

        @Override
        public Optional<String> settingValue(String settingKey) {
            return Optional.ofNullable(settings.get(settingKey));
        }

        @Override
        public void upsertSetting(String settingKey, String settingValue, String valueType, String groupCode, String remark, String operator) {
            settings.put(settingKey, settingValue);
        }

        @Override
        public Map<String, Object> tamperTrend(LocalDateTime now) {
            return Map.of();
        }

        @Override
        public List<Map<String, Object>> tamperPaths() {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> tamperAccounts() {
            return List.of();
        }

        @Override
        public void createTamperReport(String reportId, String window, boolean masked, String status,
                                       Map<String, Object> payload, String operator, String reason) {
        }

        @Override
        public List<Map<String, Object>> playbooks() {
            return List.of();
        }

        @Override
        public Optional<Map<String, Object>> playbook(String code) {
            return Optional.empty();
        }

        @Override
        public void createPlaybook(String code, String name, String scene, boolean emergency, String sla, String state,
                                   String owner, String notifyCampaignNo, String notifyTemplate, String rollback,
                                   boolean drillRequired, boolean draft, List<Map<String, Object>> sequence,
                                   String operator) {
        }

        @Override
        public void updatePlaybook(String code, String name, String scene, Boolean emergency, String sla, String state,
                                   String owner, String notifyCampaignNo, String notifyTemplate, String rollback,
                                   Boolean drillRequired, String summary, List<Map<String, Object>> sequence,
                                   String operator) {
        }

        @Override
        public void markPlaybookDrilled(String code, LocalDateTime drillAt, String operator) {
        }

        @Override
        public Optional<Map<String, Object>> executionByIdempotencyKey(String code, String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public Optional<Map<String, Object>> execution(String executionId) {
            return Optional.empty();
        }

        @Override
        public List<Map<String, Object>> executions(int limit) {
            return List.of();
        }

        @Override
        public void createExecution(Map<String, Object> row) {
        }

        @Override
        public void markExecutionRolledBack(String executionId, LocalDateTime rollbackAt, String reason,
                                            List<Map<String, Object>> rollbackActions) {
        }
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

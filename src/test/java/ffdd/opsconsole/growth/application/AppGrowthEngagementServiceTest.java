package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.facade.VoucherGrantFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.growth.facade.VoucherGrantFacade.VoucherGrantResult;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.Attribution;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.DailyMilestone;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.EarningMilestone;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.EventReward;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.QuestReward;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.StreakPowerUp;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.StreakState;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.VoucherClaimDefinition;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppGrowthEngagementServiceTest {
    private static final ZoneId H5_BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final AppGrowthEngagementMapper mapper = mock(AppGrowthEngagementMapper.class);
    private final VoucherGrantFacade voucher = mock(VoucherGrantFacade.class);
    private final GrowthRhythmFacade rhythm = mock(GrowthRhythmFacade.class);
    private final TreasuryCoverageFacade coverage = mock(TreasuryCoverageFacade.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AppGrowthEngagementService service =
            new AppGrowthEngagementService(mapper, voucher, rhythm, coverage, idempotency, audit, outbox, null);

    @BeforeEach
    void setUp() {
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        when(mapper.findActiveUser(42L)).thenReturn(42L);
        when(mapper.attribution(42L)).thenReturn(new Attribution("P3", 5, "2026-W30"));
        when(rhythm.snapshot()).thenReturn(new GrowthRhythmSnapshot(
                24, 3, "P2", 50, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("0.2"), 30, new BigDecimal("5000"), new BigDecimal("1.5"), false,
                List.of("H1.rhythm.currentMonth", "growth.phase.month.3.questBonusMultiplier")));
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("1.20"), new BigDecimal("1.05"), true));
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class),
                org.mockito.ArgumentMatchers.<Supplier<ApiResult>>any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
    }

    @Test
    void questStateUsesOnlyPersistedDefinitionsUserProgressPromoAndH1Multiplier() {
        when(mapper.questState(42L)).thenReturn(List.of(Map.of(
                "questCode", "H3_FIRST_ORDER_STARTED",
                "name", "Start your first order",
                "layer", "DAY_ONE",
                "rewardNex", 50,
                "status", "CLAIMABLE")));
        when(mapper.questPromoBanner()).thenReturn(Map.of(
                "bannerCode", "HOME_WEEKLY_UPSELL",
                "baseReward", "800",
                "multiplier", "1.5",
                "countdownDays", 4,
                "countdownHours", 12,
                "targetDevice", "StellarBox Pro",
                "targetDaily", "1.5",
                "status", "active"));

        var result = service.questState(42L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("questBonusMultiplier", new BigDecimal("1.5"))
                .containsEntry("rhythmMonth", 3);
        assertThat(result.getData().get("quests")).asList().singleElement()
                .extracting(row -> ((Map<?, ?>) row).get("questCode"))
                .isEqualTo("H3_FIRST_ORDER_STARTED");
        assertThat(result.getData().get("source").toString()).contains("nx_mission", "nx_user_mission");
    }

    @Test
    void questClaimAtomicallyChangesStateCreditsWalletAuditsAndPublishes() {
        when(mapper.lockClaimableQuest(42L, "QUEST-1"))
                .thenReturn(new QuestReward(7L, "QUEST-1", "DAILY", new BigDecimal("10")));
        when(mapper.claimQuest(42L, 7L)).thenReturn(1);
        wallet(new BigDecimal("100"));

        var result = service.claimQuest(42L, "QUEST-1", "quest-key");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("rewardNex", new BigDecimal("15.000000"));
        var ordered = inOrder(mapper, audit, outbox);
        ordered.verify(mapper).claimQuest(42L, 7L);
        ordered.verify(mapper).creditWalletNex(42L, new BigDecimal("15.000000"));
        ordered.verify(mapper).insertNexLedger(
                42L, "QUEST:QUEST-1:42", "QUEST_REWARD", new BigDecimal("15.000000"),
                new BigDecimal("115.000000"), "H3 quest claim");
        ordered.verify(audit).recordRequired(any());
        ordered.verify(outbox).publishUserEvent(
                eq("MISSION"), eq("QUEST-1"), eq("quest.claimed"), eq(42L),
                eq("P3"), eq(5), eq("2026-W30"), any());
    }

    @Test
    void eventJoinAndClaimUsePersistedEventStateAndRealNexLedger() {
        EventReward event = new EventReward(8L, "EV-1", 1, "NEX", new BigDecimal("4"), null);
        when(mapper.lockOpenEvent("EV-1")).thenReturn(event);
        when(mapper.joinEvent(42L, event)).thenReturn(1);

        assertThat(service.joinEvent(42L, "EV-1", "join-key").getCode()).isZero();
        verify(outbox).publishUserEvent(
                eq("EVENT_QUEST"), eq("EV-1"), eq("event.joined"), eq(42L),
                eq("P3"), eq(5), eq("2026-W30"), any());

        when(mapper.lockClaimableEvent(42L, "EV-1")).thenReturn(event);
        when(mapper.claimEvent(42L, "EV-1")).thenReturn(1);
        wallet(new BigDecimal("20"));
        assertThat(service.claimEvent(42L, "EV-1", "claim-key").getCode()).isZero();
        verify(mapper).insertNexLedger(
                42L, "EVENT:EV-1:42", "EVENT_REWARD", new BigDecimal("4"),
                new BigDecimal("24"), "H4 event reward");
        verify(outbox).publishUserEvent(
                eq("EVENT_QUEST"), eq("EV-1"), eq("event.claimed"), eq(42L),
                eq("P3"), eq(5), eq("2026-W30"), any());
    }

    @Test
    void guaranteedLuckyCheckInPersistsServerDecisionAndPublishesLuckyEvent() {
        LocalDate today = LocalDate.now(H5_BUSINESS_ZONE);
        when(mapper.dailyMissionId()).thenReturn(2L);
        when(mapper.lockStreak(42L)).thenReturn(new StreakState(6, 6, 1, today.minusDays(1)));
        when(mapper.checkInRule("baseline")).thenReturn("2");
        when(mapper.checkInRule("bonus7")).thenReturn("5");
        when(mapper.checkInRule("p2")).thenReturn("100");
        when(mapper.checkInRule("p15")).thenReturn("0");
        when(mapper.insertCheckIn(eq(42L), eq(2L), eq(today), eq(2),
                eq(new BigDecimal("2.0")), eq(2), eq(5), eq(9))).thenReturn(1);
        when(mapper.updateStreak(42L, 7, today)).thenReturn(1);
        wallet(new BigDecimal("10"));

        var result = service.checkIn(42L, "daily-key");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("rewardNex", new BigDecimal("9.000000"))
                .containsEntry("multiplier", new BigDecimal("2.0")).containsEntry("streakDays", 7);
        verify(mapper).insertNexLedger(
                42L, "DAILY:42:" + today, "DAILY_CHECK_IN", new BigDecimal("9.000000"),
                new BigDecimal("19.000000"), "H5 daily check-in");
        verify(outbox).publishUserEvent(
                eq("DAILY_CHECK_IN"), anyString(), eq("daily.checkin"), eq(42L),
                eq("P3"), eq(5), eq("2026-W30"), any());
        verify(outbox).publishUserEvent(
                eq("DAILY_CHECK_IN"), anyString(), eq("daily.lucky_triggered"), eq(42L),
                eq("P3"), eq(5), eq("2026-W30"), any());
    }

    @Test
    void streakSaverIsServerAuthoritativeIdempotentAndCannotBeUsedBeforeBreak() {
        LocalDate today = LocalDate.now(H5_BUSINESS_ZONE);
        when(mapper.lockStreak(42L)).thenReturn(
                new StreakState(0, 12, 1, today.minusDays(3)));
        when(mapper.useStreakSaver(42L, 12, today.minusDays(1))).thenReturn(1);

        var result = service.useStreakSaver(42L, "saver-key");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("restoredStreak", 12).containsEntry("streakSavers", 0);
        verify(audit).recordRequired(any());
        verify(outbox).publishUserEvent(
                eq("USER_STREAK"), eq("42"), eq("daily.streak_restored"), eq(42L),
                eq("P3"), eq(5), eq("2026-W30"), any());
    }

    @Test
    void powerUpActivationUsesServerEligibilityBadgeAuditAndOutbox() {
        StreakPowerUp powerUp = new StreakPowerUp(
                8L, "STREAK_BADGE", "STREAK_14_BADGE", 0);
        when(mapper.lockActivatablePowerUp(42L, 8L)).thenReturn(powerUp);
        when(mapper.activatePowerUp(42L, powerUp)).thenReturn(1);
        when(mapper.unlockAchievement(42L, "STREAK_14_BADGE")).thenReturn(1);

        var result = service.activateStreakPowerUp(42L, 8L, "power-key");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("powerUpId", 8L)
                .containsEntry("status", "ACTIVATED");
        verify(outbox).publishUserEvent(
                eq("USER_STREAK_POWER_UP"), eq("42:STREAK_BADGE"),
                eq("daily.power_up_activated"), eq(42L),
                eq("P3"), eq(5), eq("2026-W30"), any());
    }

    @Test
    void dailyAndEarningMilestonesUseSeparateUniqueLedgersAndEvents() {
        DailyMilestone daily = new DailyMilestone(3L, 7, "NEX", new BigDecimal("5"), null);
        when(mapper.lockClaimableDailyMilestone(42L, 3L)).thenReturn(daily);
        when(mapper.claimDailyMilestone(42L, daily)).thenReturn(1);
        wallet(new BigDecimal("10"));
        assertThat(service.claimDailyMilestone(42L, 3L, "daily-ms-key").getCode()).isZero();
        verify(outbox).publishUserEvent(
                eq("DAILY_MILESTONE"), eq("3"), eq("daily.milestone_claimed"), eq(42L),
                eq("P3"), eq(5), eq("2026-W30"), any());

        EarningMilestone earning = new EarningMilestone(
                "M-100", new BigDecimal("100"), new BigDecimal("8"), new BigDecimal("120"));
        when(mapper.lockEligibleEarningMilestones(42L)).thenReturn(List.of(earning));
        when(mapper.insertEarningMilestone(eq(42L), eq(earning), anyString())).thenReturn(1);
        wallet(new BigDecimal("15"));
        assertThat(service.evaluateEarningMilestones(42L, "earning-ms-key").getCode()).isZero();
        verify(outbox).publishUserEvent(
                eq("EARNING_MILESTONE"), anyString(), eq("milestone.fired"), eq(42L),
                eq("P3"), eq(5), eq("2026-W30"), any());
    }

    @Test
    void voucherClaimPublishesAnalyticsOnlyForTheFirstDurableGrant() {
        when(mapper.lockUserClaimableVoucher(eq("V-1"), eq("home"), anyLong()))
                .thenReturn(new VoucherClaimDefinition("V-1", "all"));
        when(voucher.grant(any())).thenReturn(new VoucherGrantResult("G-1", false));

        var result = service.claimVoucher(42L, "V-1", "home", "voucher-key");

        assertThat(result.getCode()).isZero();
        verify(audit).recordRequired(any());
        verify(outbox).publishUserEvent(
                eq("VOUCHER_GRANT"), eq("G-1"), eq("voucher.claimed"), eq(42L),
                eq("P3"), eq(5), eq("2026-W30"), any());
    }

    @Test
    void claimedVoucherRemainsVisibleWhenDefinitionIsPausedOrDeletedAndExpiresByServerTime() {
        when(mapper.voucherState(eq(42L), anyLong())).thenReturn(List.of(Map.ofEntries(
                Map.entry("voucherId", "V-CLAIMED"),
                Map.entry("voucherName", "Already owned"),
                Map.entry("audience", "all"),
                Map.entry("definitionStatus", "paused"),
                Map.entry("definitionDeleted", 1),
                Map.entry("grantId", "G-CLAIMED"),
                Map.entry("grantStatus", "AVAILABLE"),
                Map.entry("endAt", 1L))));

        var result = service.voucherState(42L);
        @SuppressWarnings("unchecked")
        Map<String, Object> row = ((List<Map<String, Object>>) result.getData().get("vouchers")).get(0);

        assertThat(row).containsEntry("grantStatus", "EXPIRED");
        assertThat(row).containsEntry("claimable", false);
        assertThat(row).containsEntry("definitionDeleted", 1);
    }

    @Test
    void outboxFailureIsNotSwallowedSoTransactionCanRollBackRewardAndClaim() {
        when(mapper.lockClaimableQuest(42L, "QUEST-1"))
                .thenReturn(new QuestReward(7L, "QUEST-1", "DAILY", BigDecimal.TEN));
        when(mapper.claimQuest(42L, 7L)).thenReturn(1);
        wallet(BigDecimal.ZERO);
        when(outbox.publishUserEvent(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("outbox unavailable"));

        assertThatThrownBy(() -> service.claimQuest(42L, "QUEST-1", "rollback-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox unavailable");
    }

    @Test
    void inactiveUserCannotReachAnyMutationOrSideEffect() {
        when(mapper.lockActiveUser(42L)).thenReturn(null);

        assertThatThrownBy(() -> service.claimQuest(42L, "QUEST-1", "key"))
                .hasMessage("USER_NOT_FOUND_OR_INACTIVE");
        verify(mapper, never()).claimQuest(any(), any());
        verify(audit, never()).recordRequired(any());
        verify(outbox, never()).publishUserEvent(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void b1BelowRedlineRollsBackClaimBeforeAnyWalletOrOutboxSideEffect() {
        when(mapper.lockClaimableQuest(42L, "QUEST-1"))
                .thenReturn(new QuestReward(7L, "QUEST-1", "DAILY", BigDecimal.TEN));
        when(mapper.claimQuest(42L, 7L)).thenReturn(1);
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("1.01"), new BigDecimal("1.05"), true));

        assertThatThrownBy(() -> service.claimQuest(42L, "QUEST-1", "coverage-key"))
                .hasMessage("B1_COVERAGE_BELOW_REDLINE");
        verify(mapper, never()).creditWalletNex(any(), any());
        verify(outbox, never()).publishUserEvent(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private void wallet(BigDecimal before) {
        when(mapper.lockWalletNex(42L)).thenReturn(before);
        when(mapper.creditWalletNex(eq(42L), any(BigDecimal.class))).thenReturn(1);
        when(mapper.insertNexLedger(eq(42L), anyString(), anyString(), any(BigDecimal.class),
                any(BigDecimal.class), anyString())).thenReturn(1);
    }
}

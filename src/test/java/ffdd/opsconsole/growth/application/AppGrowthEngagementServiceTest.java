package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
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
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.Attribution;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.DailyMilestone;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.DayOneSnapshot;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.DayOneSnapshotQuestState;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.EarningMilestone;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.EventReward;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.QuestReward;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper.QuestClaimState;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

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
            new AppGrowthEngagementService(mapper, voucher, rhythm, coverage, idempotency, audit, outbox, null, null, null,
                    java.util.Optional.empty(), null);

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
    void unsupportedSandboxEngagementFailsClosedBeforeCanonicalEventRead() {
        AppGrowthWheelSandboxService sandbox = mock(AppGrowthWheelSandboxService.class);
        when(sandbox.enabled()).thenReturn(true);
        AppGrowthEngagementService isolated = new AppGrowthEngagementService(
                mapper, voucher, rhythm, coverage, idempotency, audit, outbox, null, sandbox, null,
                java.util.Optional.empty(), null);

        assertThatThrownBy(() -> isolated.eventState(42L))
                .hasMessageContaining("GROWTH_SANDBOX_SCOPE_UNAVAILABLE");
        verify(mapper, never()).eventState(anyLong(), anyString());
    }

    @Test
    void unknownSandboxRuntimeFailsClosedBeforeCanonicalCheckInWrite() {
        AppGrowthWheelSandboxService sandbox = mock(AppGrowthWheelSandboxService.class);
        when(sandbox.unknownProfile()).thenReturn(true);
        AppGrowthEngagementService isolated = new AppGrowthEngagementService(
                mapper, voucher, rhythm, coverage, idempotency, audit, outbox, null, sandbox, null,
                java.util.Optional.empty(), null);

        assertThatThrownBy(() -> isolated.checkIn(42L, "unknown-runtime-key"))
                .hasMessageContaining("WHEEL_RUNTIME_PROFILE_UNSUPPORTED");
        verify(mapper, never()).lockActiveUser(anyLong());
        verify(mapper, never()).insertCheckIn(anyLong(), any(), any(), anyInt(), any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void questStateUsesCurrentWeeklyDefinitionsButOnlyTheImmutableDayOneSnapshot() {
        when(mapper.questState(42L, "en")).thenReturn(List.of(Map.of(
                "questCode", "H3_FIRST_ORDER_STARTED",
                "name", "Start your first order",
                "layer", "WEEKLY_T1",
                "rewardNex", 50,
                "status", "CLAIMABLE")));
        DayOneSnapshot snapshot = dayOneSnapshot("SNAPSHOT", 2,
                LocalDateTime.now().minusHours(2), LocalDateTime.now().plusHours(70),
                "500 / 200 / 0 NEX", new BigDecimal("2"));
        when(mapper.findLatestDayOneSnapshot(42L)).thenReturn(snapshot);
        when(mapper.dayOneSnapshotState(42L, 71L)).thenReturn(List.of(
                Map.of("questCode", "SNAPSHOT_ONLY", "name", "Original member name",
                        "layer", "DAY_ONE", "rewardNex", 10, "instanceKey", snapshot.instanceKey(),
                        "eligibleFrom", "2026-09-09T10:30:15+08:00", "eligibleUntil", "2026-09-12T10:30:15+08:00",
                        "eligible", 1, "status", "CLAIMABLE"),
                Map.of("questCode", "SNAPSHOT_SECOND", "name", "Second original member",
                        "layer", "DAY_ONE", "rewardNex", 20, "instanceKey", snapshot.instanceKey(),
                        "eligibleFrom", "2026-09-09T10:30:15+08:00", "eligibleUntil", "2026-09-12T10:30:15+08:00",
                        "eligible", 1, "status", "PENDING")));
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
                .containsEntry("rhythmMonth", 3)
                .containsEntry("dayOneRewardNex", new BigDecimal("1000.000000"))
                .containsEntry("dayOneRequiredTaskCount", 2)
                .containsEntry("dayOneSnapshotStatus", "SNAPSHOT");
        List<?> questRows = (List<?>) result.getData().get("quests");
        assertThat(questRows).hasSize(3);
        List<Object> rewardValues = questRows.stream().map(row -> (Object) ((Map<?, ?>) row).get("rewardNex")).toList();
        List<Object> questCodes = questRows.stream().map(row -> (Object) ((Map<?, ?>) row).get("questCode")).toList();
        assertThat(rewardValues).containsExactly(50, BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(questCodes).contains("SNAPSHOT_ONLY", "SNAPSHOT_SECOND");
        assertThat(result.getData().get("source").toString()).contains("nx_mission", "nx_user_mission");
    }

    @Test
    void questStateDoesNotReconstructLegacyDayOneFromLiveDefinitionsOrUserProgress() {
        when(mapper.questState(42L, "en")).thenReturn(List.of(
                Map.of("questCode", "CURRENT_WEEK", "layer", "WEEKLY_T1", "status", "PENDING"),
                // A newly enabled definition is not evidence that this legacy user
                // received it when they entered the product.
                Map.of("questCode", "NEW_DAY_ONE", "layer", "DAY_ONE", "status", "PENDING",
                        "eligibleUntil", "2026-09-12T10:30:15+08:00"),
                // Nor can an old user-mission status recreate the old mutable
                // definition after its name, route, reward or window changed.
                Map.of("questCode", "RENAMED_DAY_ONE", "layer", "DAY_ONE", "status", "CLAIMED",
                        "instanceKey", "DAY_ONE:legacy", "eligibleUntil", "2026-09-12T10:30:15+08:00")));
        when(mapper.findLatestDayOneSnapshot(42L)).thenReturn(null);
        when(mapper.questPromoBanner()).thenReturn(Map.of());

        var result = service.questState(42L);

        assertThat(result.getData()).containsEntry("dayOneRequiredTaskCount", null)
                .containsEntry("dayOneSnapshotStatus", "LEGACY_UNVERIFIED");
        List<Object> questCodes = ((List<?>) result.getData().get("quests")).stream()
                .map(row -> (Object) ((Map<?, ?>) row).get("questCode")).toList();
        assertThat(questCodes).containsExactly("CURRENT_WEEK");
        verify(mapper, never()).dayOneSnapshotState(anyLong(), anyLong());
    }

    @Test
    void questStateKeepsIndividuallyVerifiableFrozenHistoryWhenHeaderCountDoesNotMatch() {
        DayOneSnapshot snapshot = dayOneSnapshot("SNAPSHOT", 2,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(71),
                "500 / 200 / 0 NEX", new BigDecimal("2"));
        when(mapper.questState(42L, "en")).thenReturn(List.of(Map.of(
                "questCode", "CURRENT_WEEK", "layer", "WEEKLY_T1")));
        when(mapper.findLatestDayOneSnapshot(42L)).thenReturn(snapshot);
        when(mapper.dayOneSnapshotState(42L, 71L)).thenReturn(List.of(Map.of(
                "questCode", "ONLY_ONE", "name", "Original name", "layer", "DAY_ONE",
                "category", "explore", "actionRoute", "/pages/store/store", "rewardNex", 0,
                "instanceKey", snapshot.instanceKey(), "eligible", 1, "status", "PENDING")));
        when(mapper.questPromoBanner()).thenReturn(Map.of());

        var result = service.questState(42L);

        assertThat(result.getData()).containsEntry("dayOneSnapshotStatus", "LEGACY_UNVERIFIED")
                .containsEntry("dayOneRequiredTaskCount", null)
                .containsEntry("dayOneRewardNex", BigDecimal.ZERO);
        List<Object> questCodes = ((List<?>) result.getData().get("quests")).stream()
                .map(row -> (Object) ((Map<?, ?>) row).get("questCode")).toList();
        assertThat(questCodes).contains("ONLY_ONE");
    }

    @Test
    void questStateDropsDuplicateAndWrongInstanceRowsFromCorruptFrozenHistory() {
        DayOneSnapshot snapshot = dayOneSnapshot("SNAPSHOT", 3,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(71),
                "500 / 200 / 0 NEX", new BigDecimal("2"));
        when(mapper.questState(42L, "en")).thenReturn(List.of(Map.of(
                "questCode", "CURRENT_WEEK", "layer", "WEEKLY_T1")));
        when(mapper.findLatestDayOneSnapshot(42L)).thenReturn(snapshot);
        when(mapper.dayOneSnapshotState(42L, 71L)).thenReturn(List.of(
                Map.of("questCode", "DUPLICATE", "layer", "DAY_ONE", "instanceKey", snapshot.instanceKey()),
                Map.of("questCode", "DUPLICATE", "layer", "DAY_ONE", "instanceKey", snapshot.instanceKey()),
                Map.of("questCode", "WRONG_KEY", "layer", "DAY_ONE", "instanceKey", "DAY_ONE:other"),
                Map.of("questCode", "VERIFIED", "layer", "DAY_ONE", "instanceKey", snapshot.instanceKey())));
        when(mapper.questPromoBanner()).thenReturn(Map.of());

        var result = service.questState(42L);

        assertThat(result.getData()).containsEntry("dayOneSnapshotStatus", "LEGACY_UNVERIFIED")
                .containsEntry("dayOneRequiredTaskCount", null)
                .containsEntry("dayOneRewardNex", BigDecimal.ZERO);
        List<Object> questCodes = ((List<?>) result.getData().get("quests")).stream()
                .map(row -> (Object) ((Map<?, ?>) row).get("questCode")).toList();
        assertThat(questCodes)
                .contains("CURRENT_WEEK", "VERIFIED")
                .doesNotContain("DUPLICATE", "WRONG_KEY");
    }

    @Test
    void questStateReturnsNoDayOneRowsWhenNoCorruptFrozenRowIsIndividuallyVerifiable() {
        DayOneSnapshot snapshot = dayOneSnapshot("SNAPSHOT", 2,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(71),
                "500 / 200 / 0 NEX", new BigDecimal("2"));
        when(mapper.questState(42L, "en")).thenReturn(List.of(Map.of(
                "questCode", "CURRENT_WEEK", "layer", "WEEKLY_T1")));
        when(mapper.findLatestDayOneSnapshot(42L)).thenReturn(snapshot);
        when(mapper.dayOneSnapshotState(42L, 71L)).thenReturn(List.of(
                Map.of("questCode", "DUPLICATE", "layer", "DAY_ONE", "instanceKey", snapshot.instanceKey()),
                Map.of("questCode", "DUPLICATE", "layer", "DAY_ONE", "instanceKey", snapshot.instanceKey()),
                Map.of("questCode", "WRONG_KEY", "layer", "DAY_ONE", "instanceKey", "DAY_ONE:other")));
        when(mapper.questPromoBanner()).thenReturn(Map.of());

        var result = service.questState(42L);

        assertThat(result.getData()).containsEntry("dayOneSnapshotStatus", "LEGACY_UNVERIFIED")
                .containsEntry("dayOneRequiredTaskCount", null)
                .containsEntry("dayOneRewardNex", BigDecimal.ZERO);
        List<Object> questCodes = ((List<?>) result.getData().get("quests")).stream()
                .map(row -> (Object) ((Map<?, ?>) row).get("questCode")).toList();
        assertThat(questCodes).containsExactly("CURRENT_WEEK");
    }
    @Test
    void questStateReadsTheFrozenDayOneHeaderWhenTheCurrentH1DialIsUnavailable() {
        DayOneSnapshot snapshot = dayOneSnapshot("SNAPSHOT", 1,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(71),
                "500 / 200 / 0 NEX", new BigDecimal("2"));
        when(rhythm.snapshot()).thenReturn(new GrowthRhythmSnapshot(
                24, 3, "P2", 50, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("0.2"), 30, new BigDecimal("5000"), new BigDecimal("1.5"), false,
                List.of("H1.rhythm.currentMonth"), false, List.of("growth.phase.month.3.questBonusMultiplier")));
        when(mapper.questState(42L, "en")).thenReturn(List.of(
                Map.of("questCode", "CURRENT_WEEK", "layer", "WEEKLY_T1"),
                Map.of("questCode", "LIVE_DAY_ONE", "layer", "DAY_ONE")));
        when(mapper.findLatestDayOneSnapshot(42L)).thenReturn(snapshot);
        when(mapper.dayOneSnapshotState(42L, 71L)).thenReturn(List.of(Map.of(
                "questCode", "FROZEN_DAY_ONE", "name", "Original name", "layer", "DAY_ONE",
                "category", "explore", "actionRoute", "/pages/store/store",
                "rewardNex", 0, "instanceKey", snapshot.instanceKey(), "eligible", 1,
                "status", "PENDING")));
        when(mapper.questPromoBanner()).thenReturn(Map.of());

        var result = service.questState(42L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("dayOneSnapshotStatus", "SNAPSHOT")
                .containsEntry("dayOneRewardNex", new BigDecimal("1000.000000"))
                .containsEntry("questBonusMultiplier", new BigDecimal("2"))
                .containsEntry("rhythmMonth", 2);
        assertThat(result.getData().get("source")).isEqualTo(
                "nx_mission + nx_user_mission + nx_growth_day_one_instance + nx_growth_day_one_instance_item + day_one_snapshot_only");
        assertThat((List<?>) result.getData().get("quests")).allMatch(
                row -> "DAY_ONE".equals(((Map<?, ?>) row).get("layer")));
    }
    @Test
    void dayOneSnapshotUsesTheBusinessInstantAtTheTwentyFourHourAndExpiryBoundaries() {
        LocalDateTime enteredAt = LocalDateTime.of(2026, 9, 9, 0, 0);
        LocalDateTime eligibleUntil = LocalDateTime.of(2026, 9, 12, 0, 0);
        DayOneSnapshot snapshot = dayOneSnapshot("SNAPSHOT", 1, enteredAt, eligibleUntil,
                "500 / 200 / 0 NEX", new BigDecimal("2"));
        when(mapper.findLatestDayOneSnapshot(42L)).thenReturn(snapshot);
        when(mapper.questState(42L, "en")).thenReturn(List.of());
        when(mapper.dayOneSnapshotState(42L, 71L)).thenReturn(List.of(Map.of(
                "questCode", "FROZEN_DAY_ONE", "name", "Original name", "layer", "DAY_ONE",
                "category", "explore", "actionRoute", "/pages/store/store", "rewardNex", 0,
                "instanceKey", snapshot.instanceKey(), "eligible", 1, "status", "PENDING")));
        when(mapper.questPromoBanner()).thenReturn(Map.of());
        Instant firstGraceInstant = Instant.parse("2026-09-09T16:00:00Z");

        var utcClockResult = serviceAt(Clock.fixed(firstGraceInstant, ZoneOffset.UTC)).questState(42L);
        var differentClockZoneResult = serviceAt(Clock.fixed(firstGraceInstant,
                ZoneId.of("America/Los_Angeles"))).questState(42L);

        assertThat(utcClockResult.getData()).containsEntry("dayOneRewardNex", new BigDecimal("400.000000"));
        assertThat(differentClockZoneResult.getData()).containsEntry("dayOneRewardNex", new BigDecimal("400.000000"));

        when(mapper.lockDayOneSnapshot(42L, snapshot.instanceKey())).thenReturn(snapshot);
        var expiry = serviceAt(Clock.fixed(Instant.parse("2026-09-11T16:00:00Z"), ZoneOffset.UTC))
                .claimQuest(42L, "FROZEN_DAY_ONE", snapshot.instanceKey(), "expiry-boundary");
        assertThat(expiry.getCode()).isEqualTo(409);
        assertThat(expiry.getMessage()).isEqualTo("QUEST_EXPIRED");
        verify(mapper, never()).claimDayOneSnapshotGroup(anyLong(), anyLong(), anyString());
        verify(mapper, never()).creditWalletNex(anyLong(), any());
    }
    @Test
    void questStateDoesNotInventCurrentH1FieldsForAnEmptyHeader() {
        when(rhythm.snapshot()).thenReturn(new GrowthRhythmSnapshot(
                24, 3, "P2", 50, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("0.2"), 30, new BigDecimal("5000"), new BigDecimal("1.5"), false,
                List.of("H1.rhythm.currentMonth"), false, List.of("growth.phase.month.3.questBonusMultiplier")));
        when(mapper.findLatestDayOneSnapshot(42L)).thenReturn(dayOneSnapshot("EMPTY", 0,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(71),
                "500 / 200 / 0 NEX", BigDecimal.ONE));

        assertThatThrownBy(() -> service.questState(42L)).hasMessage("H1_RHYTHM_UNAVAILABLE");
        verify(mapper, never()).questState(anyLong(), anyString());
    }
    @Test
    void questStateReportsPersistedEmptySnapshotWithoutInventingClaimableTasks() {
        when(mapper.questState(42L, "en")).thenReturn(List.of());
        when(mapper.findLatestDayOneSnapshot(42L)).thenReturn(dayOneSnapshot("EMPTY", 0,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(71),
                "500 / 200 / 0 NEX", BigDecimal.ONE));
        when(mapper.questPromoBanner()).thenReturn(Map.of());

        var result = service.questState(42L);

        assertThat(result.getData()).containsEntry("dayOneRequiredTaskCount", 0)
                .containsEntry("dayOneSnapshotStatus", "EMPTY")
                .containsEntry("dayOneRewardNex", BigDecimal.ZERO);
        verify(mapper, never()).dayOneSnapshotState(anyLong(), anyLong());
    }

    @Test
    void questClaimAtomicallyChangesStateCreditsWalletAuditsAndPublishes() {
        when(mapper.lockClaimableQuest(42L, "QUEST-1", "TEST-INSTANCE"))
                .thenReturn(new QuestReward(7L, "QUEST-1", "DAILY", new BigDecimal("10")));
        when(mapper.claimQuest(42L, 7L, "TEST-INSTANCE")).thenReturn(1);
        wallet(new BigDecimal("100"));

        var result = service.claimQuest(42L, "QUEST-1", "TEST-INSTANCE", "quest-key");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("rewardNex", new BigDecimal("15.000000"));
        var ordered = inOrder(mapper, audit, outbox);
        ordered.verify(mapper).claimQuest(42L, 7L, "TEST-INSTANCE");
        ordered.verify(mapper).creditWalletNex(42L, new BigDecimal("15.000000"));
        ordered.verify(mapper).insertNexLedger(
                42L, "QUEST:QUEST-1:42:TEST-INSTANCE", "QUEST_REWARD", new BigDecimal("15.000000"),
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
    void questClaimReturnsTheLockedTerminalReasonWithoutWalletOrOutboxWrites() {
        when(mapper.lockClaimableQuest(42L, "QUEST-1", "TEST-INSTANCE")).thenReturn(null);
        when(mapper.lockQuestClaimState(42L, "QUEST-1", "TEST-INSTANCE"))
                .thenReturn(new QuestClaimState("CLAIMED", 1, 0));

        var result = service.claimQuest(42L, "QUEST-1", "TEST-INSTANCE", "already-claimed-key");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("QUEST_ALREADY_CLAIMED");
        verify(mapper).lockQuestClaimState(42L, "QUEST-1", "TEST-INSTANCE");
        verify(mapper, never()).claimQuest(any(), any(), any());
        verify(mapper, never()).creditWalletNex(any(), any());
        verify(audit, never()).recordRequired(any());
        verify(outbox, never()).publishUserEvent(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void dayOneClaimRejectsAConfiguredSnapshotWhoseExactMemberSetIsNotComplete() {
        DayOneSnapshot snapshot = dayOneSnapshot("SNAPSHOT", 2,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(71),
                "500 / 200 / 0 NEX", new BigDecimal("2"));
        when(mapper.lockDayOneSnapshot(42L, snapshot.instanceKey())).thenReturn(snapshot);
        when(mapper.lockDayOneSnapshotGroup(42L, 71L, snapshot.instanceKey())).thenReturn(List.of(
                new DayOneSnapshotQuestState("DAY-1", "CLAIMABLE"),
                new DayOneSnapshotQuestState("DAY-2", "PENDING")));

        var result = service.claimQuest(42L, "DAY-1", snapshot.instanceKey(), "day-one-early");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("DAY_ONE_GROUP_NOT_CLAIMABLE");
        verify(mapper, never()).lockClaimableQuest(anyLong(), anyString(), anyString());
        verify(mapper, never()).claimDayOneSnapshotGroup(anyLong(), anyLong(), anyString());
        verify(mapper, never()).creditWalletNex(any(), any());
    }

    @Test
    void dayOneClaimRejectsAHeaderWhoseLockedFrozenMemberCountIsShort() {
        DayOneSnapshot snapshot = dayOneSnapshot("SNAPSHOT", 2,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(71),
                "500 / 200 / 0 NEX", new BigDecimal("2"));
        when(mapper.lockDayOneSnapshot(42L, snapshot.instanceKey())).thenReturn(snapshot);
        when(mapper.lockDayOneSnapshotGroup(42L, 71L, snapshot.instanceKey()))
                .thenReturn(List.of(new DayOneSnapshotQuestState("DAY-1", "CLAIMABLE")));

        var result = service.claimQuest(42L, "DAY-1", snapshot.instanceKey(), "short-member-set");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("DAY_ONE_GROUP_NOT_CLAIMABLE");
        verify(mapper, never()).claimDayOneSnapshotGroup(anyLong(), anyLong(), anyString());
        verify(mapper, never()).creditWalletNex(any(), any());
    }

    @Test
    void dayOneClaimUsesTheSnapshotAfterCurrentDefinitionWasRemovedAndH1Changed() {
        DayOneSnapshot snapshot = dayOneSnapshot("SNAPSHOT", 2,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(71),
                "500 / 200 / 0 NEX", new BigDecimal("2"));
        when(mapper.lockDayOneSnapshot(42L, snapshot.instanceKey())).thenReturn(snapshot);
        when(mapper.lockDayOneSnapshotGroup(42L, 71L, snapshot.instanceKey())).thenReturn(List.of(
                new DayOneSnapshotQuestState("DAY-1", "CLAIMABLE"),
                new DayOneSnapshotQuestState("DAY-2", "COMPLETED")));
        when(mapper.claimDayOneSnapshotGroup(42L, 71L, snapshot.instanceKey())).thenReturn(2);
        wallet(new BigDecimal("100"));

        var result = service.claimQuest(42L, "DAY-1", snapshot.instanceKey(), "snapshot-claim");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("rewardNex", new BigDecimal("1000.000000"))
                .containsEntry("instanceKey", snapshot.instanceKey());
        verify(mapper, never()).lockClaimableQuest(anyLong(), anyString(), anyString());
        verify(mapper).claimDayOneSnapshotGroup(42L, 71L, snapshot.instanceKey());
        verify(mapper).insertNexLedger(42L, "QUEST:DAY_ONE:42:" + snapshot.instanceKey(),
                "QUEST_REWARD", new BigDecimal("1000.000000"), new BigDecimal("1100.000000"),
                "H3 quest claim");
    }

    @Test
    void dayOneClaimRejectsEmptyLegacyExpiredAndStaleSnapshotInstancesWithoutWrites() {
        DayOneSnapshot empty = dayOneSnapshot("EMPTY", 0,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(71),
                "500 / 200 / 0 NEX", BigDecimal.ONE);
        when(mapper.lockDayOneSnapshot(42L, empty.instanceKey())).thenReturn(empty);
        assertThat(service.claimQuest(42L, "DAY-1", empty.instanceKey(), "empty-instance").getMessage())
                .isEqualTo("DAY_ONE_EMPTY_INSTANCE");

        DayOneSnapshot expired = dayOneSnapshot("SNAPSHOT", 1,
                LocalDateTime.now().minusHours(73), LocalDateTime.now().minusHours(1),
                "500 / 200 / 0 NEX", BigDecimal.ONE);
        when(mapper.lockDayOneSnapshot(42L, expired.instanceKey())).thenReturn(expired);
        assertThat(service.claimQuest(42L, "DAY-1", expired.instanceKey(), "expired-instance").getMessage())
                .isEqualTo("QUEST_EXPIRED");

        assertThat(service.claimQuest(42L, "DAY-1", "DAY_ONE:missing", "legacy-instance").getMessage())
                .isEqualTo("DAY_ONE_SNAPSHOT_UNAVAILABLE");
        verify(mapper, never()).claimDayOneSnapshotGroup(anyLong(), anyLong(), anyString());
        verify(mapper, never()).creditWalletNex(any(), any());
    }

    @Test
    void dayOneClaimRejectsDuplicateFrozenQuestCodesBeforeTheCasWrite() {
        DayOneSnapshot snapshot = dayOneSnapshot("SNAPSHOT", 2,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(71),
                "500 / 200 / 0 NEX", BigDecimal.ONE);
        when(mapper.lockDayOneSnapshot(42L, snapshot.instanceKey())).thenReturn(snapshot);
        when(mapper.lockDayOneSnapshotGroup(42L, 71L, snapshot.instanceKey())).thenReturn(List.of(
                new DayOneSnapshotQuestState("DAY-1", "CLAIMABLE"),
                new DayOneSnapshotQuestState("DAY-1", "COMPLETED")));

        var result = service.claimQuest(42L, "DAY-1", snapshot.instanceKey(), "duplicate-member");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("DAY_ONE_GROUP_NOT_CLAIMABLE");
        verify(mapper, never()).claimDayOneSnapshotGroup(anyLong(), anyLong(), anyString());
        verify(mapper, never()).creditWalletNex(anyLong(), any());
    }
    @Test
    void dayOneClaimRequiresTheRequestedSnapshotMemberAndTheCasCountPreventsSecondPayout() {
        DayOneSnapshot snapshot = dayOneSnapshot("SNAPSHOT", 2,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(71),
                "500 / 200 / 0 NEX", BigDecimal.ONE);
        when(mapper.lockDayOneSnapshot(42L, snapshot.instanceKey())).thenReturn(snapshot, snapshot);
        when(mapper.lockDayOneSnapshotGroup(42L, 71L, snapshot.instanceKey())).thenReturn(List.of(
                new DayOneSnapshotQuestState("DAY-1", "CLAIMABLE"),
                new DayOneSnapshotQuestState("DAY-2", "COMPLETED")));
        when(mapper.claimDayOneSnapshotGroup(42L, 71L, snapshot.instanceKey())).thenReturn(0);

        assertThat(service.claimQuest(42L, "NOT_A_MEMBER", snapshot.instanceKey(), "not-member").getMessage())
                .isEqualTo("DAY_ONE_SNAPSHOT_MEMBER_NOT_FOUND");
        assertThatThrownBy(() -> service.claimQuest(42L, "DAY-1", snapshot.instanceKey(), "second-writer"))
                .hasMessage("QUEST_CLAIM_CONFLICT");
        verify(mapper, never()).creditWalletNex(any(), any());
    }

    @Test
    void malformedNonDayOneKeyCannotReopenTheRetiredLiveDayOneGroupClaimPath() {
        when(mapper.lockClaimableQuest(42L, "DAY-1", "OLD_DAY_ONE_KEY"))
                .thenReturn(new QuestReward(7L, "DAY-1", "DAY_ONE", BigDecimal.TEN, "OLD_DAY_ONE_KEY"));

        var result = service.claimQuest(42L, "DAY-1", "OLD_DAY_ONE_KEY", "legacy-day-one-key");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("DAY_ONE_SNAPSHOT_UNAVAILABLE");
        verify(mapper, never()).lockDayOneSnapshotGroup(anyLong(), anyLong(), anyString());
        verify(mapper, never()).claimDayOneGroup(anyLong(), anyString());
        verify(mapper, never()).claimQuest(anyLong(), anyLong(), anyString());
        verify(mapper, never()).creditWalletNex(anyLong(), any());
    }
    @Test
    void questClaimBindsTheIdempotencyPayloadToTheRequestedMissionInstance() {
        when(mapper.lockClaimableQuest(42L, "QUEST-1", "TEST-INSTANCE"))
                .thenReturn(new QuestReward(7L, "QUEST-1", "DAILY", BigDecimal.TEN));
        when(mapper.claimQuest(42L, 7L, "TEST-INSTANCE")).thenReturn(1);
        wallet(BigDecimal.ZERO);

        service.claimQuest(42L, "QUEST-1", "TEST-INSTANCE", "instance-bound-key");

        verify(idempotency).execute(eq("APP:QUEST_CLAIM:USER:42"), eq("instance-bound-key"),
                eq(sha256("QUEST-1|TEST-INSTANCE")), eq(ApiResult.class), any());
    }

    @Test
    void questClaimRejectsAStaleRequestedInstanceBeforeAnyRewardWrite() {
        when(mapper.lockClaimableQuest(42L, "QUEST-1", "WEEK:2026-W35"))
                .thenReturn(new QuestReward(7L, "QUEST-1", "DAILY", BigDecimal.TEN, "WEEK:2026-W36"));

        assertThatThrownBy(() -> service.claimQuest(
                42L, "QUEST-1", "WEEK:2026-W35", "stale-instance-key"))
                .hasMessage("QUEST_INSTANCE_MISMATCH");

        verify(mapper, never()).claimQuest(any(), any(), any());
        verify(mapper, never()).creditWalletNex(any(), any());
        verify(audit, never()).recordRequired(any());
        verify(outbox, never()).publishUserEvent(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void contentStatePassesOnlySupportedLocaleToTheLocalizedProjection() {
        when(mapper.questState(42L, "zh")).thenReturn(List.of());
        when(mapper.questPromoBanner()).thenReturn(Map.of());

        assertThat(service.questState(42L, "zh").getCode()).isZero();
        verify(mapper).questState(42L, "zh");

        when(mapper.questState(42L, "en")).thenReturn(List.of());
        assertThat(service.questState(42L, "ja").getCode()).isZero();
        verify(mapper).questState(42L, "en");
    }

    @Test
    void pointStateIncludesCanonicalBadgeAchievements() {
        when(mapper.pointState(eq(42L), any())).thenReturn(Map.of(
                "currentStreak", 0,
                "longestStreak", 0,
                "streakSavers", 0,
                "checkedInToday", 0));
        when(mapper.achievementState(42L)).thenReturn(List.of(Map.of(
                "achievementCode", "FIRST_DEVICE",
                "name", "First device",
                "description", "Activate a device",
                "category", "HARDWARE",
                "iconKey", "hardware",
                "accentColor", "#00C48C",
                "rewardPoints", 0,
                "status", "UNLOCKED")));

        var result = service.pointState(42L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("badgeAchievements")).asList().singleElement()
                .extracting(row -> ((Map<?, ?>) row).get("achievementCode"))
                .isEqualTo("FIRST_DEVICE");
        verify(mapper).achievementState(42L);
    }

    @Test
    void developmentCheckInCreditsTheFixedDevelopmentWalletThroughTheCanonicalReleaseRail() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        EarningsReleaseService earningsRelease = mock(EarningsReleaseService.class);
        AppGrowthEngagementService developmentService = new AppGrowthEngagementService(
                mapper, voucher, rhythm, coverage, idempotency, audit, outbox, earningsRelease,
                null, null, java.util.Optional.empty(), environment);
        LocalDate today = LocalDate.now(H5_BUSINESS_ZONE);
        when(mapper.lockActiveSandboxUser(42L)).thenReturn(42L);
        when(mapper.dailyMissionId()).thenReturn(2L);
        when(mapper.lockStreak(42L)).thenReturn(new StreakState(0, 0, 0, null));
        when(mapper.checkInRule("baseline")).thenReturn("2");
        when(mapper.checkInRule("bonus7")).thenReturn("5");
        when(mapper.checkInRule("p2")).thenReturn("0");
        when(mapper.checkInRule("p15")).thenReturn("0");
        when(mapper.insertCheckIn(eq(42L), eq(2L), eq(today), eq(2), eq(BigDecimal.ONE),
                eq(0), eq(0), eq(2))).thenReturn(1);
        when(mapper.updateStreak(42L, 1, today)).thenReturn(1);
        when(mapper.lockWalletNex(42L)).thenReturn(BigDecimal.ZERO);
        when(mapper.insertNexLedger(
                42L, "DAILY:42:" + today, "DAILY_CHECK_IN", new BigDecimal("2.000000"),
                new BigDecimal("2.000000"), "H5 daily check-in")).thenReturn(1);

        var result = developmentService.checkIn(42L, "development-daily-key");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "");
        verify(earningsRelease).creditReward(
                42L, "DAILY_CHECK_IN", "DAILY:42:" + today, "NEX",
                new BigDecimal("2.000000"), "GROWTH:DAILY:42:" + today + ":NEX");
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
    void explicitEarningMilestoneClaimFiresOnlyTheRequestedEligibleRungAndScopesIdempotencyToIt() {
        EarningMilestone lowest = new EarningMilestone(
                "M-100", new BigDecimal("100"), new BigDecimal("8"), new BigDecimal("500"));
        EarningMilestone requested = new EarningMilestone(
                "M-500", new BigDecimal("500"), new BigDecimal("50"), new BigDecimal("500"));
        when(mapper.lockEligibleEarningMilestones(42L)).thenReturn(List.of(lowest, requested));
        when(mapper.insertEarningMilestone(eq(42L), eq(requested), anyString())).thenReturn(1);
        wallet(BigDecimal.TEN);

        var result = service.evaluateEarningMilestones(42L, "earning-selected-key", "M-500");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("fired")).asList().singleElement()
                .extracting(row -> ((Map<?, ?>) row).get("milestoneId"))
                .isEqualTo("M-500");
        verify(mapper).insertEarningMilestone(eq(42L), eq(requested), anyString());
        verify(mapper, never()).insertEarningMilestone(eq(42L), eq(lowest), anyString());
        verify(idempotency).execute(
                eq("APP:EARNING_MILESTONE_EVALUATE:USER:42"), eq("earning-selected-key"),
                eq(sha256("milestone:M-500")), eq(ApiResult.class), any());
    }

    @Test
    void defaultEarningMilestoneEvaluationStillFiresTheLowestEligibleRung() {
        EarningMilestone lowest = new EarningMilestone(
                "M-100", new BigDecimal("100"), new BigDecimal("8"), new BigDecimal("500"));
        EarningMilestone higher = new EarningMilestone(
                "M-500", new BigDecimal("500"), new BigDecimal("50"), new BigDecimal("500"));
        when(mapper.lockEligibleEarningMilestones(42L)).thenReturn(List.of(lowest, higher));
        when(mapper.insertEarningMilestone(eq(42L), eq(lowest), anyString())).thenReturn(1);
        wallet(BigDecimal.TEN);

        assertThat(service.evaluateEarningMilestones(42L, "earning-default-key").getCode()).isZero();

        verify(mapper).insertEarningMilestone(eq(42L), eq(lowest), anyString());
        verify(mapper, never()).insertEarningMilestone(eq(42L), eq(higher), anyString());
        verify(idempotency).execute(
                eq("APP:EARNING_MILESTONE_EVALUATE:USER:42"), eq("earning-default-key"),
                eq(sha256("eligible-rules")), eq(ApiResult.class), any());
    }

    @Test
    void explicitEarningMilestoneClaimRejectsARequestedRungOutsideTheLockedEligibleSet() {
        EarningMilestone eligible = new EarningMilestone(
                "M-100", new BigDecimal("100"), new BigDecimal("8"), new BigDecimal("120"));
        when(mapper.lockEligibleEarningMilestones(42L)).thenReturn(List.of(eligible));

        assertThatThrownBy(() -> service.evaluateEarningMilestones(42L, "earning-not-eligible-key", "M-500"))
                .hasMessageContaining("EARNING_MILESTONE_NOT_CLAIMABLE");

        verify(mapper, never()).insertEarningMilestone(anyLong(), any(), anyString());
        verify(mapper, never()).creditWalletNex(anyLong(), any());
    }

    @Test
    void voucherClaimPublishesAnalyticsOnlyForTheFirstDurableGrant() {
        when(mapper.lockUserClaimableVoucher(eq("V-1"), eq("home"), anyLong()))
                .thenReturn(new VoucherClaimDefinition("V-1", "all"));
        when(voucher.grant(any())).thenReturn(new VoucherGrantResult("G-1", false));

        var result = service.claimVoucher(42L, "V-1", "home", "voucher-key");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("serverCanonical", true)
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "");
        verify(audit).recordRequired(any());
        verify(outbox).publishUserEvent(
                eq("VOUCHER_GRANT"), eq("G-1"), eq("voucher.claimed"), eq(42L),
                eq("P3"), eq(5), eq("2026-W30"), any());
    }

    @Test
    void developmentAudienceCanClaimTheCanonicalVoucherWithACanonicalAccount() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        ReflectionTestUtils.setField(service, "environment", environment);
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        when(mapper.lockUserClaimableVoucher(eq("V-DEV"), eq("home"), anyLong()))
                .thenReturn(new VoucherClaimDefinition("V-DEV", "all"));
        when(voucher.grant(any())).thenReturn(new VoucherGrantResult("G-DEV", false));

        var result = service.claimVoucher(42L, "V-DEV", "home", "voucher-dev-key");

        assertThat(result.getCode()).isZero();
        verify(mapper).lockActiveUser(42L);
        verify(mapper, never()).lockActiveSandboxUser(42L);
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

        assertThat(result.getData()).containsEntry("serverCanonical", true);
        assertThat(row).containsEntry("grantStatus", "EXPIRED");
        assertThat(row).containsEntry("claimable", false);
        assertThat(row).containsEntry("definitionDeleted", 1);
    }

    @Test
    void outboxFailureIsNotSwallowedSoTransactionCanRollBackRewardAndClaim() {
        when(mapper.lockClaimableQuest(42L, "QUEST-1", "TEST-INSTANCE"))
                .thenReturn(new QuestReward(7L, "QUEST-1", "DAILY", BigDecimal.TEN));
        when(mapper.claimQuest(42L, 7L, "TEST-INSTANCE")).thenReturn(1);
        wallet(BigDecimal.ZERO);
        when(outbox.publishUserEvent(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("outbox unavailable"));

        assertThatThrownBy(() -> service.claimQuest(42L, "QUEST-1", "TEST-INSTANCE", "rollback-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox unavailable");
    }

    @Test
    void inactiveUserCannotReachAnyMutationOrSideEffect() {
        when(mapper.lockActiveUser(42L)).thenReturn(null);

        assertThatThrownBy(() -> service.claimQuest(42L, "QUEST-1", "TEST-INSTANCE", "key"))
                .hasMessage("USER_NOT_FOUND_OR_INACTIVE");
        verify(mapper, never()).claimQuest(any(), any(), any());
        verify(audit, never()).recordRequired(any());
        verify(outbox, never()).publishUserEvent(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void b1BelowRedlineRollsBackClaimBeforeAnyWalletOrOutboxSideEffect() {
        when(mapper.lockClaimableQuest(42L, "QUEST-1", "TEST-INSTANCE"))
                .thenReturn(new QuestReward(7L, "QUEST-1", "DAILY", BigDecimal.TEN));
        when(mapper.claimQuest(42L, 7L, "TEST-INSTANCE")).thenReturn(1);
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("1.01"), new BigDecimal("1.05"), true));

        assertThatThrownBy(() -> service.claimQuest(42L, "QUEST-1", "TEST-INSTANCE", "coverage-key"))
                .hasMessage("B1_COVERAGE_BELOW_REDLINE");
        verify(mapper, never()).creditWalletNex(any(), any());
        verify(outbox, never()).publishUserEvent(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private AppGrowthEngagementService serviceAt(Clock clock) {
        return new AppGrowthEngagementService(mapper, voucher, rhythm, coverage, idempotency, audit, outbox,
                null, null, null, java.util.Optional.empty(), null, clock);
    }
    private DayOneSnapshot dayOneSnapshot(
            String status, int requiredTaskCount, LocalDateTime enteredAt, LocalDateTime eligibleUntil,
            String reward, BigDecimal multiplier) {
        return new DayOneSnapshot(71L, "DAY_ONE:" + status + requiredTaskCount, status, enteredAt,
                72, 24, eligibleUntil, reward, multiplier, 2, requiredTaskCount);
    }

    private void wallet(BigDecimal before) {
        when(mapper.lockWalletNex(42L)).thenReturn(before);
        when(mapper.creditWalletNex(eq(42L), any(BigDecimal.class))).thenReturn(1);
        when(mapper.insertNexLedger(eq(42L), anyString(), anyString(), any(BigDecimal.class),
                any(BigDecimal.class), anyString())).thenReturn(1);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }
}

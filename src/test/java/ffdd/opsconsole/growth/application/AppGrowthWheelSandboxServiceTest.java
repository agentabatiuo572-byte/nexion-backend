package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ffdd.opsconsole.growth.mapper.AppGrowthWheelSandboxMapper;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelSandboxMapper.SandboxSpin;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelSandboxMapper.SandboxTier;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelSandboxMapper.SandboxQuest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppGrowthWheelSandboxServiceTest {
    private final AppGrowthWheelSandboxMapper mapper = mock(AppGrowthWheelSandboxMapper.class);
    private final WheelSandboxProfile profile;
    private final AppGrowthWheelSandboxService service;

    AppGrowthWheelSandboxServiceTest() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("NEXION_ACCEPTANCE_RUN_ID", "WHEEL-SANDBOX-20260815");
        environment.setActiveProfiles("test");
        profile = new WheelSandboxProfile(environment);
        service = new AppGrowthWheelSandboxService(mapper, profile);
    }

    @BeforeEach
    void setUp() {
        when(mapper.findSandboxUser(42L)).thenReturn(42L);
        when(mapper.lockScope("WHEEL-SANDBOX-20260815", 42L)).thenReturn(1L);
        when(mapper.listTiers("WHEEL-SANDBOX-20260815", 42L)).thenReturn(tiers());
        when(mapper.lockTiers("WHEEL-SANDBOX-20260815", 42L)).thenReturn(tiers());
        when(mapper.listHistory("WHEEL-SANDBOX-20260815", 42L, "evt-spring-spin", 20)).thenReturn(List.of());
        when(mapper.countDailySpin(eq("WHEEL-SANDBOX-20260815"), eq(42L), eq("evt-spring-spin"), any())).thenReturn(0);
        when(mapper.countAvailableTickets("WHEEL-SANDBOX-20260815", 42L)).thenReturn(0);
        when(mapper.insertTicket(anyString(), eq(42L), anyString(), anyString(), anyString())).thenReturn(1);
        when(mapper.lockCommand(anyString(), eq(42L), anyString(), anyString())).thenReturn(null);
        when(mapper.insertCommand(anyString(), eq(42L), anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(mapper.insertSpin(anyString(), eq(42L), anyString(), eq("evt-spring-spin"), any(), anyString(), anyString(), any(), eq(false), eq("NONE"))).thenReturn(1);
        when(mapper.rewardBalance(anyString(), eq(42L), anyString())).thenReturn(BigDecimal.ZERO);
        when(mapper.insertReward(anyString(), eq(42L), anyString(), anyString(), any(), any())).thenReturn(1);
        when(mapper.findSpin(anyString(), eq(42L), anyString())).thenReturn(new SandboxSpin(
                "SBX-SPIN-1", LocalDate.of(2026, 8, 15), "DAILY", "2026-08-15", 1L,
                "comfort-nex-5", "NEX", new BigDecimal("5"), "+5 NEX", false, "NONE", null));
    }

    @Test
    void stateAndSpinAreRunScopedMockFacts() {
        var state = service.state(42L, "evt-spring-spin");
        var spin = service.spin(42L, "evt-spring-spin", "wheel-key");

        assertThat(state.getData()).containsEntry("source", "mock")
                .containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("runId", "WHEEL-SANDBOX-20260815");
        assertThat(spin.getData()).containsEntry("source", "mock")
                .containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("runId", "WHEEL-SANDBOX-20260815");
    }

    @Test
    void questStateMirrorsActiveAdminWeeklyDefinitionsBeforeReadingRunScopedProgress() {
        SandboxQuest weekly = new SandboxQuest(
                "H3_LEARNING_COMPLETED", "Complete a learning course", "WEEKLY_T1",
                new BigDecimal("30"), "PENDING", null);
        when(mapper.listQuests("WHEEL-SANDBOX-20260815", 42L)).thenReturn(List.of(weekly));

        var result = service.questState(42L);

        var ordered = inOrder(mapper);
        ordered.verify(mapper).deactivateInactiveWeeklyQuests("WHEEL-SANDBOX-20260815", 42L);
        ordered.verify(mapper).syncActiveWeeklyQuests("WHEEL-SANDBOX-20260815", 42L);
        ordered.verify(mapper).listQuests("WHEEL-SANDBOX-20260815", 42L);
        assertThat(result.getData().get("quests")).asList().singleElement()
                .extracting(row -> ((java.util.Map<?, ?>) row).get("layer"))
                .isEqualTo("WEEKLY_T1");
    }

    @Test
    void questStateFailsClosedBeforeSyncWhenWeeklyCodeCollidesWithDayOneProgress() {
        when(mapper.countActiveWeeklyCodeCollisions("WHEEL-SANDBOX-20260815", 42L)).thenReturn(1);

        assertThatThrownBy(() -> service.questState(42L))
                .hasMessage("QUEST_SANDBOX_CODE_COLLISION");

        verify(mapper, never()).deactivateInactiveWeeklyQuests(anyString(), eq(42L));
        verify(mapper, never()).syncActiveWeeklyQuests(anyString(), eq(42L));
        verify(mapper, never()).listQuests(anyString(), eq(42L));
    }

    @Test
    void milestoneClaimOnlyIssuesSandboxTicket() {
        var result = service.claimMilestone(42L, 7L, "milestone-key");

        assertThat(result.getData()).containsEntry("source", "mock")
                .containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("spinTickets", 1);
    }

    @Test
    void shareEventIsCompletedOnlyInsideTheCurrentUserRun() {
        when(mapper.lockQuest("WHEEL-SANDBOX-20260815", 42L, "invite_friend"))
                .thenReturn(new SandboxQuest("invite_friend", "Invite a friend", "DAY_ONE",
                        new BigDecimal("200"), "PENDING", null));
        when(mapper.completeShareQuest("WHEEL-SANDBOX-20260815", 42L, "invite_friend", "share-1"))
                .thenReturn(1);

        var result = service.recordShareEvent(42L, "WHEEL-SANDBOX-20260815", "share-1", "telegram", "share_sheet", "share-key-1");

        assertThat(result.getData()).containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("runId", "WHEEL-SANDBOX-20260815")
                .containsEntry("serverCanonical", true);
    }

    @Test
    void sandboxDuplicateShareIsReplayButAnotherEventIsRateLimited() {
        when(mapper.lockQuest("WHEEL-SANDBOX-20260815", 42L, "invite_friend"))
                .thenReturn(new SandboxQuest("invite_friend", "Invite a friend", "DAY_ONE",
                        new BigDecimal("200"), "COMPLETED", "share-1"));

        assertThat(service.recordShareEvent(42L, "WHEEL-SANDBOX-20260815", "share-1", "copy", "share_sheet", "share-key-1")
                .getData()).containsEntry("replay", true);
        assertThatThrownBy(() -> service.recordShareEvent(
                42L, "WHEEL-SANDBOX-20260815", "share-2", "copy", "share_sheet", "share-key-2"))
                .hasMessage("SHARE_EVENT_RATE_LIMITED");
    }

    @Test
    void staleRunIdIsRejectedBeforeWritingQuestFact() {
        assertThatThrownBy(() -> service.recordShareEvent(
                42L, "WHEEL-SANDBOX-OLD", "share-3", "copy", "share_sheet", "share-key-3"))
                .hasMessage("SHARE_EVENT_SCOPE_MISMATCH");
    }

    private List<SandboxTier> tiers() {
        return List.of(
                new SandboxTier(1L, "comfort-nex-5", "+5 NEX", new BigDecimal("38"), "nex", new BigDecimal("5"), false, 0),
                new SandboxTier(2L, "points-50", "+50 points", new BigDecimal("24"), "points", new BigDecimal("50"), false, 0),
                new SandboxTier(3L, "nex-30", "+30 NEX", new BigDecimal("33"), "nex", new BigDecimal("30"), false, 0),
                new SandboxTier(4L, "usdt-1", "$1 USDT", new BigDecimal("5"), "usdt", new BigDecimal("1"), false, 0));
    }
}

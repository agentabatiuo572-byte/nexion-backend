package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.application.QuestCompletionFactConsumer.QuestCompletionCommand;
import ffdd.opsconsole.growth.mapper.QuestCompletionFactMapper;
import ffdd.opsconsole.growth.mapper.QuestCompletionFactMapper.CompletionFact;
import ffdd.opsconsole.growth.mapper.QuestCompletionFactMapper.MissionDefinition;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class QuestCompletionFactConsumerTest {
    private final QuestCompletionFactMapper mapper = mock(QuestCompletionFactMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final QuestCompletionFactConsumer consumer = new QuestCompletionFactConsumer(mapper, audit, outbox, null);

    @Test
    void developmentRuntimeCompletesMissionForActiveCanonicalAccount() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        QuestCompletionFactConsumer developmentConsumer =
                new QuestCompletionFactConsumer(mapper, audit, outbox, null, environment);
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        when(mapper.lockMissionInstance(42L, "QUEST-DEV")).thenReturn(new MissionDefinition(8L, "QUEST-DEV", "WEEKLY"));
        when(mapper.insertFact(eq("ORDER"), eq("ORDER-DEV"), anyString(), eq(42L), eq(8L), eq("QUEST-DEV"), eq("TEST-INSTANCE")))
                .thenReturn(1);
        when(mapper.markMissionCompleted(42L, 8L, "TEST-INSTANCE")).thenReturn(1);
        when(mapper.attribution(42L)).thenReturn(Map.of(
                "phase", "P3", "accountAgeMonths", 0, "cohort", "2026-W34"));

        var result = developmentConsumer.consume(
                new QuestCompletionCommand("ORDER", "ORDER-DEV", 42L, "QUEST-DEV"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        verify(mapper).lockActiveUser(42L);
        verify(mapper, never()).lockActiveSandboxUser(42L);
    }

    @Test
    void trustedFactCompletesMissionAndPublishesCanonicalLifecycleEvent() {
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        when(mapper.lockMissionInstance(42L, "QUEST-1")).thenReturn(new MissionDefinition(7L, "QUEST-1", "WEEKLY"));
        when(mapper.insertFact(eq("ORDER"), eq("ORDER-9"), anyString(), eq(42L), eq(7L), eq("QUEST-1"), eq("TEST-INSTANCE")))
                .thenReturn(1);
        when(mapper.markMissionCompleted(42L, 7L, "TEST-INSTANCE")).thenReturn(1);
        when(mapper.attribution(42L)).thenReturn(Map.of(
                "phase", "P3", "accountAgeMonths", 4, "cohort", "2026-W30"));

        var result = consumer.consume(new QuestCompletionCommand("order", "ORDER-9", 42L, "QUEST-1"));

        assertThat(result.replay()).isFalse();
        verify(audit).recordRequired(any());
        verify(outbox).publishUserEvent(
                eq("MISSION"), eq("QUEST-1"), eq("quest.completed"), eq(42L),
                eq("P3"), eq(4), eq("2026-W30"), any());
    }

    @Test
    void untrustedProducerCannotMutateMission() {
        assertThatThrownBy(() -> consumer.consume(
                new QuestCompletionCommand("browser", "EVENT-1", 42L, "QUEST-1")))
                .hasMessage("QUEST_COMPLETION_PRODUCER_NOT_TRUSTED");
        verify(mapper, never()).markMissionCompleted(any(), any(), any());
    }

    @Test
    void configuredButExpiredDayOneInstanceCannotAcceptACompletionFact() {
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        when(mapper.lockMissionInstance(42L, "QUEST-DAY-ONE")).thenReturn(null);
        when(mapper.activeMissionCount("QUEST-DAY-ONE")).thenReturn(1);

        assertThatThrownBy(() -> consumer.consume(
                new QuestCompletionCommand("ORDER", "ORDER-EXPIRED", 42L, "QUEST-DAY-ONE")))
                .hasMessage("QUEST_NOT_ELIGIBLE_FOR_CURRENT_INSTANCE");

        verify(mapper, never()).insertFact(anyString(), anyString(), anyString(), any(), any(), anyString(), anyString());
        verify(mapper, never()).markMissionCompleted(any(), any(), any());
    }

    @Test
    void frozenDayOneSnapshotUsesItsStoredMissionAndWindowInsteadOfCurrentDefinitions() {
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        LocalDateTime eventTs = LocalDateTime.of(2026, 9, 9, 10, 0);
        when(mapper.lockDayOneSnapshotMissionAt(42L, 9L, "visit_earn", "DAY_ONE:20260909T090000", eventTs))
                .thenReturn(new MissionDefinition(9L, "visit_earn", "DAY_ONE", "DAY_ONE:20260909T090000"));
        when(mapper.insertFact(eq("SYSTEM"), eq("EVT:I71:M9"), anyString(), eq(42L), eq(9L),
                eq("visit_earn"), eq("DAY_ONE:20260909T090000"))).thenReturn(1);
        when(mapper.markMissionCompleted(42L, 9L, "DAY_ONE:20260909T090000")).thenReturn(1);
        when(mapper.attribution(42L)).thenReturn(Map.of(
                "phase", "P3", "accountAgeMonths", 0, "cohort", "2026-W34"));

        assertThat(consumer.consume(new QuestCompletionCommand("SYSTEM", "EVT:I71:M9", 42L, "visit_earn",
                eventTs, 9L, "DAY_ONE:20260909T090000")).status()).isEqualTo("COMPLETED");

        verify(mapper, never()).lockMissionInstance(any(), any());
        verify(mapper, never()).lockMissionInstanceAt(any(), any(), any());
    }

    @Test
    void missingFrozenDayOneSnapshotNeverFallsBackToAnActiveMission() {
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        LocalDateTime eventTs = LocalDateTime.of(2026, 9, 9, 10, 0);
        when(mapper.lockDayOneSnapshotMissionAt(42L, 9L, "visit_earn", "DAY_ONE:20260909T090000", eventTs))
                .thenReturn(null);

        assertThatThrownBy(() -> consumer.consume(new QuestCompletionCommand("SYSTEM", "EVT:MISSING", 42L,
                "visit_earn", eventTs, 9L, "DAY_ONE:20260909T090000")))
                .hasMessage("QUEST_NOT_ELIGIBLE_FOR_SNAPSHOT_INSTANCE");

        verify(mapper, never()).lockMissionInstance(any(), any());
        verify(mapper, never()).lockMissionInstanceAt(any(), any(), any());
        verify(mapper, never()).insertFact(anyString(), anyString(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    void exactFactReplayDoesNotPublishTwice() {
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        when(mapper.lockMissionInstance(42L, "QUEST-1")).thenReturn(new MissionDefinition(7L, "QUEST-1", "WEEKLY"));
        when(mapper.insertFact(eq("ORDER"), eq("ORDER-9"), anyString(), eq(42L), eq(7L), eq("QUEST-1"), eq("TEST-INSTANCE")))
                .thenReturn(0);
        when(mapper.lockFact("ORDER", "ORDER-9")).thenAnswer(invocation -> {
            Object hashArgument = org.mockito.Mockito.mockingDetails(mapper).getInvocations().stream()
                    .filter(row -> row.getMethod().getName().equals("insertFact"))
                    .map(row -> row.getArgument(2)).findFirst().orElseThrow();
            String hash = String.valueOf(hashArgument);
            return new CompletionFact("ORDER", "ORDER-9", hash, 42L, 7L, "QUEST-1");
        });

        assertThat(consumer.consume(new QuestCompletionCommand("ORDER", "ORDER-9", 42L, "QUEST-1")).replay())
                .isTrue();
        verify(outbox, never()).publishUserEvent(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void replayFromAnOlderWeeklyInstanceCannotCompleteTheCurrentWeek() {
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        when(mapper.lockMissionInstance(42L, "QUEST-1"))
                .thenReturn(new MissionDefinition(7L, "QUEST-1", "WEEKLY_T1", "WEEK:2026-W36"));
        when(mapper.insertFact(eq("ORDER"), eq("ORDER-OLD-WEEK"), anyString(), eq(42L), eq(7L),
                eq("QUEST-1"), eq("WEEK:2026-W36"))).thenReturn(0);
        when(mapper.lockFact("ORDER", "ORDER-OLD-WEEK")).thenAnswer(invocation -> {
            String hash = org.mockito.Mockito.mockingDetails(mapper).getInvocations().stream()
                    .filter(row -> row.getMethod().getName().equals("insertFact"))
                    .map(row -> row.<String>getArgument(2)).findFirst().orElseThrow();
            return new CompletionFact("ORDER", "ORDER-OLD-WEEK", hash, 42L, 7L, "QUEST-1", "WEEK:2026-W35");
        });

        assertThatThrownBy(() -> consumer.consume(
                new QuestCompletionCommand("ORDER", "ORDER-OLD-WEEK", 42L, "QUEST-1")))
                .hasMessage("QUEST_COMPLETION_STALE_INSTANCE_REPLAY");
        verify(mapper, never()).markMissionCompleted(any(), any(), anyString());
        verify(outbox, never()).publishUserEvent(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void firstDeliveryFromAnOlderWeekCannotCompleteTheCurrentWeek() {
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        when(mapper.lockMissionInstanceAt(eq(42L), eq("QUEST-1"), any(LocalDateTime.class)))
                .thenReturn(new MissionDefinition(7L, "QUEST-1", "WEEKLY_T1", "WEEK:2026-W36"));

        assertThatThrownBy(() -> consumer.consume(new QuestCompletionCommand(
                "ORDER", "ORDER-DELAYED", 42L, "QUEST-1",
                LocalDateTime.of(2026, 8, 30, 23, 59))))
                .hasMessage("QUEST_COMPLETION_STALE_INSTANCE_EVENT");
        verify(mapper, never()).insertFact(anyString(), anyString(), anyString(), any(), any(), anyString(), anyString());
        verify(mapper, never()).markMissionCompleted(any(), any(), anyString());
        verify(outbox, never()).publishUserEvent(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shareWithDifferentEventAfterCompletionIsRateLimited() {
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        when(mapper.lockMissionInstance(42L, "QUEST-1")).thenReturn(new MissionDefinition(7L, "QUEST-1", "WEEKLY"));
        when(mapper.lockUserMissionStatus(42L, 7L, "TEST-INSTANCE")).thenReturn("COMPLETED");
        when(mapper.lockFactForUserMission("SHARE", "SHARE-NEW", 42L, 7L, "QUEST-1", "TEST-INSTANCE"))
                .thenReturn(null);

        assertThatThrownBy(() -> consumer.consume(
                new QuestCompletionCommand("SHARE", "SHARE-NEW", 42L, "QUEST-1")))
                .hasMessage("SHARE_EVENT_RATE_LIMITED");
        verify(mapper, never()).insertFact(anyString(), anyString(), anyString(), any(), any(), anyString(), anyString());
        verify(outbox, never()).publishUserEvent(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void sameShareEventAfterCompletionIsAnExactReplay() {
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        when(mapper.lockMissionInstance(42L, "QUEST-1")).thenReturn(new MissionDefinition(7L, "QUEST-1", "WEEKLY"));
        when(mapper.lockUserMissionStatus(42L, 7L, "TEST-INSTANCE")).thenReturn("COMPLETED");
        when(mapper.lockFactForUserMission("SHARE", "SHARE-OLD", 42L, 7L, "QUEST-1", "TEST-INSTANCE"))
                .thenReturn(new CompletionFact("SHARE", "SHARE-OLD", "hash", 42L, 7L, "QUEST-1"));

        assertThat(consumer.consume(
                new QuestCompletionCommand("SHARE", "SHARE-OLD", 42L, "QUEST-1")).replay()).isTrue();
        verify(mapper, never()).insertFact(anyString(), anyString(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    void shareEventCannotBeReplayedAcrossAccounts() {
        when(mapper.lockActiveUser(43L)).thenReturn(43L);
        when(mapper.lockMissionInstance(43L, "QUEST-1")).thenReturn(new MissionDefinition(7L, "QUEST-1", "WEEKLY"));
        when(mapper.lockUserMissionStatus(43L, 7L, "TEST-INSTANCE")).thenReturn(null);
        when(mapper.insertFact(eq("SHARE"), eq("SHARE-ACCOUNT-1"), anyString(),
                eq(43L), eq(7L), eq("QUEST-1"), eq("TEST-INSTANCE"))).thenReturn(0);
        when(mapper.lockFact("SHARE", "SHARE-ACCOUNT-1"))
                .thenReturn(new CompletionFact("SHARE", "SHARE-ACCOUNT-1", "different-account-hash",
                        42L, 7L, "QUEST-1"));

        assertThatThrownBy(() -> consumer.consume(
                new QuestCompletionCommand("SHARE", "SHARE-ACCOUNT-1", 43L, "QUEST-1")))
                .hasMessage("QUEST_COMPLETION_FACT_CONFLICT");
        verify(mapper, never()).markMissionCompleted(any(), any(), any());
    }
}

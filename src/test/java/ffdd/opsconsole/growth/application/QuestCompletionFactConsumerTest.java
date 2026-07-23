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
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuestCompletionFactConsumerTest {
    private final QuestCompletionFactMapper mapper = mock(QuestCompletionFactMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final QuestCompletionFactConsumer consumer = new QuestCompletionFactConsumer(mapper, audit, outbox);

    @Test
    void trustedFactCompletesMissionAndPublishesCanonicalLifecycleEvent() {
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        when(mapper.lockMission("QUEST-1")).thenReturn(new MissionDefinition(7L, "QUEST-1", "WEEKLY"));
        when(mapper.insertFact(eq("ORDER"), eq("ORDER-9"), anyString(), eq(42L), eq(7L), eq("QUEST-1")))
                .thenReturn(1);
        when(mapper.markMissionCompleted(42L, 7L)).thenReturn(1);
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
        verify(mapper, never()).markMissionCompleted(any(), any());
    }

    @Test
    void exactFactReplayDoesNotPublishTwice() {
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        when(mapper.lockMission("QUEST-1")).thenReturn(new MissionDefinition(7L, "QUEST-1", "WEEKLY"));
        when(mapper.insertFact(eq("ORDER"), eq("ORDER-9"), anyString(), eq(42L), eq(7L), eq("QUEST-1")))
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
}

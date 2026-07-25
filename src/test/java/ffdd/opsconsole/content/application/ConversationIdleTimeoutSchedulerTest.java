package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.ConversationIdleCandidate;
import ffdd.opsconsole.content.domain.ConversationTimeoutPolicy;
import ffdd.opsconsole.content.mapper.ConversationTimeoutPolicyMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ConversationIdleTimeoutSchedulerTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 25, 10, 0);
    private static final LocalDateTime ACTIVITY = LocalDateTime.of(2026, 7, 25, 9, 50);

    private final ConversationTimeoutPolicyMapper mapper = mock(ConversationTimeoutPolicyMapper.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final ConversationIdleTimeoutScheduler scheduler = new ConversationIdleTimeoutScheduler(
            mapper,
            auditLogService,
            publisher,
            Clock.fixed(Instant.parse("2026-07-25T10:00:00Z"), ZoneId.of("UTC")));

    @Test
    void sweepWarnsAndClosesOnlyStillIdleOpenConversations() {
        ConversationTimeoutPolicy policy = new ConversationTimeoutPolicy(
                "GLOBAL", 5, 10, 3L, "superadmin", "调整", NOW);
        ConversationIdleCandidate warning = candidate("CV-WARN", LocalDateTime.of(2026, 7, 25, 9, 54));
        ConversationIdleCandidate closing = candidate("CV-CLOSE", ACTIVITY);
        when(mapper.selectPolicy()).thenReturn(policy);
        when(mapper.selectDueWarningCandidates(
                LocalDateTime.of(2026, 7, 25, 9, 55),
                LocalDateTime.of(2026, 7, 25, 9, 50),
                100)).thenReturn(List.of(warning));
        when(mapper.selectDueCloseCandidates(LocalDateTime.of(2026, 7, 25, 9, 50), 100))
                .thenReturn(List.of(closing));
        when(mapper.lockCandidate("CV-WARN")).thenReturn(warning);
        when(mapper.lockCandidate("CV-CLOSE")).thenReturn(closing);
        when(mapper.insertEvent(eq("CV-WARN"), eq("WARN"), any(), eq(3L), eq(NOW))).thenReturn(1);
        when(mapper.insertEvent(eq("CV-CLOSE"), eq("CLOSE"), any(), eq(3L), eq(NOW))).thenReturn(1);
        when(mapper.closeIfStillIdle("CV-CLOSE", ACTIVITY, "会话已因用户闲置 10 分钟自动结束,可重新发起会话。", NOW))
                .thenReturn(1);

        ConversationIdleTimeoutScheduler.SweepResult result = scheduler.sweep();

        assertThat(result.warned()).isEqualTo(1);
        assertThat(result.closed()).isEqualTo(1);
        verify(mapper).insertSystemMessage(eq(42L), eq("CV-WARN"), contains("5 分钟后自动结束"), eq(NOW));
        verify(mapper).insertSystemMessage(eq(42L), eq("CV-CLOSE"), contains("自动结束"), eq(NOW));
        verify(auditLogService).recordRequired(any());
    }

    @Test
    void sweepDoesNotCloseWhenActivityChangedAfterCandidateQuery() {
        ConversationTimeoutPolicy policy = new ConversationTimeoutPolicy(
                "GLOBAL", 5, 10, 3L, "superadmin", "调整", NOW);
        ConversationIdleCandidate stale = candidate("CV-RACE", ACTIVITY);
        ConversationIdleCandidate refreshed = new ConversationIdleCandidate(
                42L, "CV-RACE", "OPEN", LocalDateTime.of(2026, 7, 25, 9, 59));
        when(mapper.selectPolicy()).thenReturn(policy);
        when(mapper.selectDueWarningCandidates(any(), any(), eq(100))).thenReturn(List.of());
        when(mapper.selectDueCloseCandidates(any(), eq(100))).thenReturn(List.of(stale));
        when(mapper.lockCandidate("CV-RACE")).thenReturn(refreshed);

        ConversationIdleTimeoutScheduler.SweepResult result = scheduler.sweep();

        assertThat(result.closed()).isZero();
        verify(mapper, never()).closeIfStillIdle(any(), any(), any(), any());
        verify(mapper, never()).insertSystemMessage(any(), any(), any(), any());
    }

    @Test
    void sweepPublishesSseEventsOnlyAfterTheDatabaseTransactionCommits() {
        ConversationTimeoutPolicy policy = new ConversationTimeoutPolicy(
                "GLOBAL", 5, 10, 3L, "superadmin", "调整", NOW);
        ConversationIdleCandidate warning = candidate("CV-WARN", LocalDateTime.of(2026, 7, 25, 9, 54));
        ConversationIdleCandidate closing = candidate("CV-CLOSE", ACTIVITY);
        when(mapper.selectPolicy()).thenReturn(policy);
        when(mapper.selectDueWarningCandidates(any(), any(), eq(100))).thenReturn(List.of(warning));
        when(mapper.selectDueCloseCandidates(any(), eq(100))).thenReturn(List.of(closing));
        when(mapper.lockCandidate("CV-WARN")).thenReturn(warning);
        when(mapper.lockCandidate("CV-CLOSE")).thenReturn(closing);
        when(mapper.insertEvent(eq("CV-WARN"), eq("WARN"), any(), eq(3L), eq(NOW))).thenReturn(1);
        when(mapper.insertEvent(eq("CV-CLOSE"), eq("CLOSE"), any(), eq(3L), eq(NOW))).thenReturn(1);
        when(mapper.closeIfStillIdle(eq("CV-CLOSE"), eq(ACTIVITY), any(), eq(NOW))).thenReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        try {
            scheduler.sweep();
            verify(publisher, never()).publishEvent(any(ConversationMessageEvent.class));

            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            verify(publisher, times(2)).publishEvent(any(ConversationMessageEvent.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private ConversationIdleCandidate candidate(String no, LocalDateTime activity) {
        return new ConversationIdleCandidate(42L, no, "OPEN", activity);
    }
}

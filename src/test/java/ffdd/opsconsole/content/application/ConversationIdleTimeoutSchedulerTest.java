package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.ConversationIdleCandidate;
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
    private final ProductionSupportPathGuard productionPathGuard = enabledGuard();
    private final ConversationIdleTimeoutScheduler scheduler = new ConversationIdleTimeoutScheduler(
            mapper,
            auditLogService,
            publisher,
            Clock.fixed(Instant.parse("2026-07-25T10:00:00Z"), ZoneId.of("UTC")),
            productionPathGuard);

    private ProductionSupportPathGuard enabledGuard() {
        ProductionSupportPathGuard guard = mock(ProductionSupportPathGuard.class);
        when(guard.productionSupportAutomationAllowed()).thenReturn(true);
        return guard;
    }

    @Test
    void sweepWarnsAndClosesOnlyStillIdleOpenConversations() {
        ConversationIdleCandidate warning = candidate("CV-WARN", LocalDateTime.of(2026, 7, 25, 9, 54));
        ConversationIdleCandidate closing = candidate("CV-CLOSE", ACTIVITY);
        when(mapper.selectDueWarningCandidates(NOW,100)).thenReturn(List.of(warning));
        when(mapper.selectDueCloseCandidates(NOW, 100))
                .thenReturn(List.of(closing));
        when(mapper.lockCandidate("CV-WARN")).thenReturn(warning);
        when(mapper.lockCandidate("CV-CLOSE")).thenReturn(closing);
        when(mapper.insertEvent(eq("CV-WARN"), eq("WARN"), any(), eq(3L), eq(NOW))).thenReturn(1);
        when(mapper.insertEvent(eq("CV-CLOSE"), eq("CLOSE"), any(), eq(3L), eq(NOW))).thenReturn(1);
        when(mapper.closeIfStillIdle("CV-CLOSE", ACTIVITY, 7L, "会话已因客户静默 10 分钟自动结束,可重新发起会话。", NOW))
                .thenReturn(1);

        ConversationIdleTimeoutScheduler.SweepResult result = scheduler.sweep();

        assertThat(result.warned()).isEqualTo(1);
        assertThat(result.closed()).isEqualTo(1);
        verify(mapper).insertSystemMessage(eq(42L), eq("CV-WARN"), contains("5 分钟后自动结束"), eq(NOW));
        verify(mapper).insertSystemMessage(eq(42L), eq("CV-CLOSE"), contains("自动结束"), eq(NOW));
        verify(auditLogService).recordRequired(any());
    }

    @Test
    void isolatedAutomationSweepDoesNotReadOrWriteOfficialConversationFacts() {
        ProductionSupportPathGuard disabled = mock(ProductionSupportPathGuard.class);
        ConversationIdleTimeoutScheduler isolated = new ConversationIdleTimeoutScheduler(mapper, auditLogService, publisher,
                Clock.systemUTC(), disabled);
        assertThat(isolated.sweep()).isEqualTo(new ConversationIdleTimeoutScheduler.SweepResult(0, 0));
        verifyNoInteractions(mapper, auditLogService, publisher);
    }

    @Test
    void sweepDoesNotCloseWhenActivityChangedAfterCandidateQuery() {
        ConversationIdleCandidate stale = candidate("CV-RACE", ACTIVITY);
        ConversationIdleCandidate refreshed = new ConversationIdleCandidate(
                42L, "CV-RACE", "OPEN", LocalDateTime.of(2026, 7, 25, 9, 59),7L,3L,5,10);
        when(mapper.selectDueWarningCandidates(any(), eq(100))).thenReturn(List.of());
        when(mapper.selectDueCloseCandidates(any(), eq(100))).thenReturn(List.of(stale));
        when(mapper.lockCandidate("CV-RACE")).thenReturn(refreshed);

        ConversationIdleTimeoutScheduler.SweepResult result = scheduler.sweep();

        assertThat(result.closed()).isZero();
        verify(mapper, never()).closeIfStillIdle(any(), any(), any(), any(), any());
        verify(mapper, never()).insertSystemMessage(any(), any(), any(), any());
    }

    @Test
    void sweepPublishesSseEventsOnlyAfterTheDatabaseTransactionCommits() {
        ConversationIdleCandidate warning = candidate("CV-WARN", LocalDateTime.of(2026, 7, 25, 9, 54));
        ConversationIdleCandidate closing = candidate("CV-CLOSE", ACTIVITY);
        when(mapper.selectDueWarningCandidates(any(), eq(100))).thenReturn(List.of(warning));
        when(mapper.selectDueCloseCandidates(any(), eq(100))).thenReturn(List.of(closing));
        when(mapper.lockCandidate("CV-WARN")).thenReturn(warning);
        when(mapper.lockCandidate("CV-CLOSE")).thenReturn(closing);
        when(mapper.insertEvent(eq("CV-WARN"), eq("WARN"), any(), eq(3L), eq(NOW))).thenReturn(1);
        when(mapper.insertEvent(eq("CV-CLOSE"), eq("CLOSE"), any(), eq(3L), eq(NOW))).thenReturn(1);
        when(mapper.closeIfStillIdle(eq("CV-CLOSE"), eq(ACTIVITY), eq(7L), any(), eq(NOW))).thenReturn(1);

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

    @Test void pendingReplyStopsBothWarningAndCloseUsingCurrentRead() {
        var warning=candidate("CV-WARN",NOW.minusMinutes(6));
        var closing=candidate("CV-CLOSE",NOW.minusMinutes(10));
        when(mapper.selectDueWarningCandidates(NOW,100)).thenReturn(List.of(warning));
        when(mapper.selectDueCloseCandidates(NOW,100)).thenReturn(List.of(closing));
        when(mapper.lockCandidate("CV-WARN")).thenReturn(warning);
        when(mapper.lockCandidate("CV-CLOSE")).thenReturn(closing);
        when(mapper.pendingRepliesCurrent(any())).thenReturn(List.of(77L));
        assertThat(scheduler.sweep()).isEqualTo(new ConversationIdleTimeoutScheduler.SweepResult(0,0));
        verify(mapper,never()).insertEvent(any(),any(),any(),any(),any());
        verify(mapper,never()).closeIfStillIdle(any(),any(),any(),any(),any());
    }

    @Test void oldSegmentUsesItsOwnThresholdAndNeverReadsLatestGlobalPolicy() {
        var old=new ConversationIdleCandidate(42L,"CV-OLD","OPEN",NOW.minusMinutes(6),7L,1L,1,120);
        when(mapper.selectDueCloseCandidates(NOW,100)).thenReturn(List.of(old));
        when(mapper.lockCandidate("CV-OLD")).thenReturn(old);
        assertThat(scheduler.sweep().closed()).isZero();
        verify(mapper,never()).selectPolicy();
        verify(mapper,never()).closeIfStillIdle(any(),any(),any(),any(),any());
    }

    @Test void changedHeaderVersionAtSameTimestampAndMissingSnapshotFailClosed() {
        var candidate=candidate("CV-SAME",ACTIVITY);
        when(mapper.selectDueCloseCandidates(NOW,100)).thenReturn(List.of(candidate));
        when(mapper.lockCandidate("CV-SAME")).thenReturn(new ConversationIdleCandidate(42L,"CV-SAME","OPEN",ACTIVITY,8L,3L,5,10));
        assertThat(scheduler.sweep().closed()).isZero();
        when(mapper.lockCandidate("CV-SAME")).thenReturn(new ConversationIdleCandidate(42L,"CV-SAME","OPEN",ACTIVITY,7L,null,null,null));
        assertThat(scheduler.sweep().closed()).isZero();
        verify(mapper,never()).closeIfStillIdle(any(),any(),any(),any(),any());
    }

    private ConversationIdleCandidate candidate(String no, LocalDateTime activity) {
        return new ConversationIdleCandidate(42L, no, "OPEN", activity,7L,3L,5,10);
    }
}

package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.bi.mapper.BehaviorAnalyticsMapper;
import ffdd.opsconsole.platform.mapper.EventGovernanceMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.apache.ibatis.annotations.Update;

class A4EventRetentionServiceTest {
    @Test
    void onlyTerminalAnalyticsFactsAreCleanedAndTheRunIsAudited() {
        EventGovernanceMapper mapper = mock(EventGovernanceMapper.class);
        BehaviorAnalyticsMapper behaviorMapper = mock(BehaviorAnalyticsMapper.class);
        AuditLogService audit = mock(AuditLogService.class);
        A4RuntimePolicyService policy = mock(A4RuntimePolicyService.class);
        when(policy.eventRetentionMonths()).thenReturn(18);
        when(mapper.tryAcquireRetentionLock()).thenReturn(1);
        when(mapper.deleteTerminalAnalyticsEventsBefore(any(LocalDateTime.class), any(Integer.class)))
                .thenReturn(200, 5);
        when(behaviorMapper.deleteExpiredFacts(any(LocalDateTime.class), any(Integer.class)))
                .thenReturn(200, 7);
        A4EventRetentionService service = new A4EventRetentionService(mapper, behaviorMapper, audit, policy);

        A4EventRetentionService.RetentionRun run = service.runNow();

        assertThat(run.retentionMonths()).isEqualTo(18);
        assertThat(run.outboxRows()).isEqualTo(205);
        assertThat(run.behaviorFactRows()).isEqualTo(207);
        assertThat(run.affectedRows()).isEqualTo(412);
        verify(mapper).releaseRetentionLock();
        verify(audit).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void scheduledAndManualRetentionRunsAreAtomic() throws Exception {
        assertThat(A4EventRetentionService.class.getDeclaredMethod("runNow").getAnnotation(Transactional.class))
                .isNotNull();
        assertThat(A4EventRetentionService.class.getDeclaredMethod("scheduledCleanup").getAnnotation(Transactional.class))
                .isNotNull();
    }

    @Test
    void retentionSqlNeverDeletesPendingOrFailedDeliveryWork() throws Exception {
        org.apache.ibatis.annotations.Delete delete = EventGovernanceMapper.class
                .getDeclaredMethod("deleteTerminalAnalyticsEventsBefore", LocalDateTime.class, int.class)
                .getAnnotation(org.apache.ibatis.annotations.Delete.class);
        String sql = String.join(" ", delete.value());
        assertThat(sql).contains("analytics_event=1").contains("'PUBLISHED','DEAD'")
                .doesNotContain("'PENDING'").doesNotContain("'FAILED'");
    }

    @Test
    void overlappingSchedulerLosesDatabaseLockWithoutDeletingOrAuditing() {
        EventGovernanceMapper mapper = mock(EventGovernanceMapper.class);
        BehaviorAnalyticsMapper behaviorMapper = mock(BehaviorAnalyticsMapper.class);
        AuditLogService audit = mock(AuditLogService.class);
        A4RuntimePolicyService policy = mock(A4RuntimePolicyService.class);
        when(policy.eventRetentionMonths()).thenReturn(18);
        when(mapper.tryAcquireRetentionLock()).thenReturn(0);
        A4EventRetentionService service = new A4EventRetentionService(mapper, behaviorMapper, audit, policy);

        A4EventRetentionService.RetentionRun run = service.runNow();
        assertThat(run.lockAcquired()).isFalse();
        assertThat(run.retentionMonths()).isEqualTo(18);
        verify(behaviorMapper, never()).deleteExpiredFacts(any(), any(Integer.class));
        verify(audit, never()).recordRequired(any());
    }
}

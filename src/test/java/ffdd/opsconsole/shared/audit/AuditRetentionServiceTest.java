package ffdd.opsconsole.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.shared.audit.mapper.AuditLogMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class AuditRetentionServiceTest {
    @Test
    void cleanupArchivesOnlyExplicitlyExpiringNewRowsInBoundedIdempotentBatches() {
        AuditLogMapper mapper = mock(AuditLogMapper.class);
        AuditLogService audit = mock(AuditLogService.class);
        A2RuntimePolicy policy = mock(A2RuntimePolicy.class);
        when(policy.retentionMonths()).thenReturn(24);
        when(mapper.tryAcquireRetentionLock()).thenReturn(1);
        when(mapper.lockExpiredForArchive(any(LocalDateTime.class), any(Integer.class)))
                .thenReturn(List.of(41L, 42L), List.of());
        when(mapper.insertArchiveFromHot(41L)).thenReturn(1);
        when(mapper.insertArchiveFromHot(42L)).thenReturn(0); // replay: cold copy already exists
        when(mapper.deleteHotAfterArchive(41L)).thenReturn(1);
        when(mapper.deleteHotAfterArchive(42L)).thenReturn(1);
        AuditRetentionService service = new AuditRetentionService(mapper, audit, policy);

        AuditRetentionService.RetentionRun run = service.runNow();

        assertThat(run.retentionMonths()).isEqualTo(24);
        assertThat(run.affectedRows()).isEqualTo(2);
        assertThat(run.archivedRows()).isEqualTo(1);
        verify(mapper).lockExpiredForArchive(run.cutoff(), 200);
        verify(audit).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void secondRunIsIdempotentAndNeverBackfillsLegacyRowsWithoutExpireAt() {
        AuditLogMapper mapper = mock(AuditLogMapper.class);
        AuditLogService audit = mock(AuditLogService.class);
        A2RuntimePolicy policy = mock(A2RuntimePolicy.class);
        when(policy.retentionMonths()).thenReturn(13);
        when(mapper.tryAcquireRetentionLock()).thenReturn(1);
        when(mapper.lockExpiredForArchive(any(LocalDateTime.class), any(Integer.class)))
                .thenReturn(List.of(9L), List.of());
        when(mapper.insertArchiveFromHot(9L)).thenReturn(1);
        when(mapper.deleteHotAfterArchive(9L)).thenReturn(1);
        AuditRetentionService service = new AuditRetentionService(mapper, audit, policy);

        assertThat(service.runNow().affectedRows()).isEqualTo(1);
        assertThat(service.runNow().affectedRows()).isZero();

        verify(mapper, times(1)).insertArchiveFromHot(9L);
        verify(mapper, times(1)).deleteHotAfterArchive(9L);
    }

    @Test
    void scheduledAndManualRetentionRunsAreAtomic() throws Exception {
        assertThat(AuditRetentionService.class.getDeclaredMethod("runNow").getAnnotation(Transactional.class))
                .isNotNull();
        assertThat(AuditRetentionService.class.getDeclaredMethod("scheduledCleanup").getAnnotation(Transactional.class))
                .isNotNull();
    }

    @Test
    void concurrentRunReportsTheConfiguredPolicyButDoesNotArchiveOrWriteExecutionAudit() {
        AuditLogMapper mapper = mock(AuditLogMapper.class);
        AuditLogService audit = mock(AuditLogService.class);
        A2RuntimePolicy policy = mock(A2RuntimePolicy.class);
        when(policy.retentionMonths()).thenReturn(13);
        when(mapper.tryAcquireRetentionLock()).thenReturn(0);
        AuditRetentionService service = new AuditRetentionService(mapper, audit, policy);

        AuditRetentionService.RetentionRun run = service.runNow();

        assertThat(run.lockAcquired()).isFalse();
        assertThat(run.retentionMonths()).isEqualTo(13);
        assertThat(run.affectedRows()).isZero();
        assertThat(run.archivedRows()).isZero();
        verify(mapper, org.mockito.Mockito.never()).lockExpiredForArchive(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(audit, org.mockito.Mockito.never()).recordRequired(any());
    }
}

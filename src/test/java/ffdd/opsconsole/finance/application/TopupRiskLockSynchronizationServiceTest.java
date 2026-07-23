package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.domain.DepositOpsRepository;
import ffdd.opsconsole.finance.domain.TopupRiskLockSnapshot;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TopupRiskLockSynchronizationServiceTest {
    @Test
    void auditsEveryNewAutomaticLockAndFailsClosedWhenRequiredAuditFails() {
        DepositOpsRepository repository = mock(DepositOpsRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        TopupRiskLockSnapshot lock = new TopupRiskLockSnapshot(
                "BIN", "424242", "ACTIVE", "AUTO", "24h failed attempts=5",
                LocalDateTime.now().plusHours(24), true);
        when(repository.activeRiskLockSnapshotsForUpdate()).thenReturn(List.of(), List.of(lock));
        TopupRiskLockSynchronizationService service = new TopupRiskLockSynchronizationService(repository, audit);

        service.synchronize(5, 24);

        verify(repository).syncAutomaticRiskLocks(5, 24);
        verify(audit).recordRequired(any());

        when(repository.activeRiskLockSnapshotsForUpdate()).thenReturn(List.of(), List.of(lock));
        doThrow(new IllegalStateException("AUDIT_DOWN")).when(audit).recordRequired(any());
        assertThatThrownBy(() -> service.synchronize(5, 24))
                .isInstanceOf(IllegalStateException.class).hasMessage("AUDIT_DOWN");
    }
}

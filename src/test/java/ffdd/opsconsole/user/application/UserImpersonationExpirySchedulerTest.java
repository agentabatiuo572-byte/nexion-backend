package ffdd.opsconsole.user.application;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.user.domain.UserImpersonationSessionView;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class UserImpersonationExpirySchedulerTest {
    @Test
    void expiresDueSessionAndWritesRequiredAuditAndOutbox() {
        UserOpsRepository repository = mock(UserOpsRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        EventOutboxService outbox = mock(EventOutboxService.class);
        UserImpersonationSessionView session = new UserImpersonationSessionView(
                "IMP-EXPIRED", 7L, "U00000007", "User 7", "ACTIVE", 15, "support",
                "support investigation", LocalDateTime.now().minusMinutes(1), LocalDateTime.now().minusMinutes(16),
                null, null, null, 0L);
        when(repository.expiredActiveImpersonations(100)).thenReturn(List.of(session));
        when(repository.expireActiveImpersonation("IMP-EXPIRED", "TTL_EXPIRED", "system")).thenReturn(true);

        new UserImpersonationExpiryScheduler(repository, audit, outbox).expireDueSessions();

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(audit).recordRequired(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getAction())
                .isEqualTo("C2_USER_IMPERSONATION_TERMINATED");
        verify(outbox).publish(eq("USER_IMPERSONATION"), eq("IMP-EXPIRED"),
                eq("admin.user_impersonation_ended"), org.mockito.ArgumentMatchers.anyMap());
    }
}

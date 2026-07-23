package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.infrastructure.UserLoginGuardRecord;
import ffdd.opsconsole.auth.mapper.UserLoginGuardMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AppUserSecurityVerificationGuardTest {
    private final UserLoginGuardMapper mapper = mock(UserLoginGuardMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AppUserSecurityVerificationGuard guard =
            new AppUserSecurityVerificationGuard(mapper, audit);

    @Test
    void activeLockRejectsFurtherOnlinePasswordGuessing() {
        UserLoginGuardRecord row = row(5, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusMinutes(10));
        when(mapper.lock(anyString())).thenReturn(row);

        assertThat(guard.allowed(42L, "PASSWORD_CHANGE")).isFalse();
        verify(mapper).initialize(anyString(), any());
        verify(mapper).bindUser(anyString(), eq(42L));
        ArgumentCaptor<AuditLogWriteRequest> auditRequest = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(audit).recordRequired(auditRequest.capture());
        assertThat(auditRequest.getValue().getDetail().toString())
                .contains("blocked=true", "rateLimited=true", "PASSWORD_CHANGE")
                .doesNotContain("password=");
    }

    @Test
    void unlockedGuardIsAlwaysBoundToItsUserBeforeTheDecisionIsReturned() {
        when(mapper.lock(anyString())).thenReturn(row(0, LocalDateTime.now(), null));

        assertThat(guard.allowed(42L, "TWO_FACTOR_UPDATE")).isTrue();

        verify(mapper).bindUser(anyString(), eq(42L));
    }

    @Test
    void fifthFailureCreatesDurableLockAndRequiredHighRiskAudit() {
        UserLoginGuardRecord row = row(4, LocalDateTime.now().minusMinutes(2), null);
        when(mapper.lock(anyString())).thenReturn(row);

        var result = guard.recordFailure(42L, "TWO_FACTOR_UPDATE");

        assertThat(result.failedCount()).isEqualTo(5);
        assertThat(result.rateLimited()).isTrue();
        verify(mapper).bindUser(anyString(), eq(42L));
        verify(mapper).recordFailure(anyString(), eq(5), any(), any());
        ArgumentCaptor<AuditLogWriteRequest> auditRequest = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(audit).recordRequired(auditRequest.capture());
        assertThat(auditRequest.getValue().getAction()).isEqualTo("USER_SECURITY_PASSWORD_VERIFICATION_REJECTED");
        assertThat(auditRequest.getValue().getResult()).isEqualTo("REJECTED");
        assertThat(auditRequest.getValue().getRiskLevel()).isEqualTo("HIGH");
        assertThat(auditRequest.getValue().getDetail().toString()).doesNotContain("password");
    }

    @Test
    void expiredWindowRestartsAtOneAndSuccessfulVerificationCanClearOnlyItsGuardKey() {
        UserLoginGuardRecord row = row(4, LocalDateTime.now().minusMinutes(20), null);
        when(mapper.lock(anyString())).thenReturn(row);

        var result = guard.recordFailure(42L, "PASSWORD_CHANGE");
        guard.clear(42L);

        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.rateLimited()).isFalse();
        verify(mapper).recordFailure(anyString(), eq(1), any(), eq(null));
        verify(mapper).clear(anyString());
    }

    private UserLoginGuardRecord row(int failures, LocalDateTime startedAt, LocalDateTime lockedUntil) {
        UserLoginGuardRecord row = new UserLoginGuardRecord();
        row.setFailedCount(failures);
        row.setWindowStartedAt(startedAt);
        row.setLockedUntil(lockedUntil);
        return row;
    }
}

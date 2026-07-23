package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.dto.AppPasswordChangeRequest;
import ffdd.opsconsole.auth.dto.AppTwoFactorUpdateRequest;
import ffdd.opsconsole.auth.mapper.AppUserSecurityMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

class AppUserSecurityServiceTest {
    private final AppUserSecurityMapper security = mock(AppUserSecurityMapper.class);
    private final AuthSessionMapper sessions = mock(AuthSessionMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final UserOtpDeliveryService otpDelivery = mock(UserOtpDeliveryService.class);
    private final AppUserSecurityVerificationGuard verificationGuard = mock(AppUserSecurityVerificationGuard.class);
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final AppUserSecurityService service = new AppUserSecurityService(
            security, sessions, passwords, audit, otpDelivery, verificationGuard);

    @BeforeEach
    void defaults() {
        when(security.passwordHashForUpdate(42L)).thenReturn(passwords.encode("OldPassword1"));
        when(security.twoFactorEnabled(42L)).thenReturn(false);
        when(otpDelivery.available()).thenReturn(true);
        when(verificationGuard.allowed(eq(42L), any())).thenReturn(true);
        when(verificationGuard.recordFailure(eq(42L), any()))
                .thenReturn(new AppUserSecurityVerificationGuard.VerificationFailure(1, false));
    }

    @Test
    void securityMutationsCommitGuardCountersAndAuditsWhenBusinessValidationRejects() throws Exception {
        for (String method : List.of("changePassword", "updateTwoFactor")) {
            Class<?>[] parameters = method.equals("changePassword")
                    ? new Class<?>[] {Long.class, String.class, AppPasswordChangeRequest.class}
                    : new Class<?>[] {Long.class, AppTwoFactorUpdateRequest.class};
            Transactional transactional = AppUserSecurityService.class
                    .getMethod(method, parameters)
                    .getAnnotation(Transactional.class);

            assertThat(transactional).isNotNull();
            assertThat(Arrays.asList(transactional.noRollbackFor()))
                    .containsExactly(AppUserSecurityService.PreWriteRejection.class);
        }
    }

    @Test
    void overviewUsesOnlyServerSessionsAndMarksTheJwtSessionCurrent() {
        LocalDateTime now = LocalDateTime.now();
        UserSessionEntity current = session("current", "Nexion H5", "203.0.113.8", now);
        UserSessionEntity other = session("other", "Chrome on Windows", "198.51.100.9", now.minusHours(2));
        when(sessions.listActiveUserSessions(42L, 30)).thenReturn(List.of(current, other));
        when(security.passwordChangedAt(42L)).thenReturn(now.minusDays(3));

        var state = service.overview(42L, "current");

        assertThat(state.sessions()).hasSize(2);
        assertThat(state.sessions().get(0).current()).isTrue();
        assertThat(state.sessions().get(0).ipMasked()).isEqualTo("203.0.113.*");
        assertThat(state.sessions().get(1).current()).isFalse();
        assertThat(state.passwordChangedAt()).isEqualTo(now.minusDays(3));
    }

    @Test
    void revokeOneIsScopedToTheAuthenticatedUserAndCannotRevokeCurrentSession() {
        assertThatThrownBy(() -> service.revokeSession(42L, "current", "current"))
                .hasMessage("CURRENT_SESSION_REVOKE_FORBIDDEN");
        verify(sessions, never()).revokeOwnedUserSession(any(), any());

        when(sessions.revokeOwnedUserSession(42L, "other")).thenReturn(1);
        var result = service.revokeSession(42L, "current", "other");

        assertThat(result.revokedSessionCount()).isEqualTo(1);
        verify(sessions).revokeOwnedUserSession(42L, "other");
        verify(audit).recordRequired(any());
    }

    @Test
    void missingOrForeignSessionNeverReportsSuccess() {
        when(sessions.revokeOwnedUserSession(42L, "foreign")).thenReturn(0);

        assertThatThrownBy(() -> service.revokeSession(42L, "current", "foreign"))
                .hasMessage("SESSION_NOT_ACTIVE_OR_NOT_OWNED");
        verify(audit, never()).recordRequired(any());
    }

    @Test
    void malformedSessionIdIsRejectedBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.revokeSession(42L, "current", "../foreign session"))
                .hasMessage("SESSION_ID_INVALID");

        verify(sessions, never()).revokeOwnedUserSession(any(), any());
    }

    @Test
    void revokeOthersKeepsTheCurrentJwtSession() {
        when(sessions.revokeOtherUserSessions(42L, "current")).thenReturn(3);

        var result = service.revokeOtherSessions(42L, "current");

        assertThat(result.revokedSessionCount()).isEqualTo(3);
        verify(sessions).revokeOtherUserSessions(42L, "current");
        verify(audit).recordRequired(any());
    }

    @Test
    void passwordChangeVerifiesCurrentPasswordThenRevokesOtherSessions() {
        when(security.updatePasswordHash(eq(42L), any())).thenReturn(1);
        when(security.markPasswordChanged(42L)).thenReturn(1);
        when(sessions.revokeOtherUserSessions(42L, "current")).thenReturn(2);

        var result = service.changePassword(
                42L, "current", new AppPasswordChangeRequest("OldPassword1", "NewPassword2"));

        assertThat(result.revokedSessionCount()).isEqualTo(2);
        verify(security).updatePasswordHash(eq(42L), any());
        verify(security).markPasswordChanged(42L);
        verify(audit).recordRequired(any());
    }

    @Test
    void wrongCurrentPasswordCannotChangePasswordOrRevokeSessions() {
        assertThatThrownBy(() -> service.changePassword(
                42L, "current", new AppPasswordChangeRequest("wrong", "NewPassword2")))
                .hasMessage("CURRENT_PASSWORD_INVALID");

        verify(security, never()).updatePasswordHash(any(), any());
        verify(sessions, never()).revokeOtherUserSessions(any(), any());
        verify(verificationGuard).recordFailure(42L, "PASSWORD_CHANGE");
    }

    @Test
    void rateLimitedCurrentPasswordCheckStopsBeforeHashVerification() {
        when(verificationGuard.allowed(42L, "TWO_FACTOR_UPDATE")).thenReturn(false);

        assertThatThrownBy(() -> service.updateTwoFactor(
                42L, new AppTwoFactorUpdateRequest(true, "OldPassword1")))
                .hasMessage("USER_SECURITY_VERIFICATION_RATE_LIMITED");

        verify(security, never()).passwordHashForUpdate(any());
        verify(security, never()).upsertTwoFactor(any(), anyBoolean());
        verify(verificationGuard).allowed(42L, "TWO_FACTOR_UPDATE");
    }

    @Test
    void oversizedCurrentPasswordIsRejectedBeforeHashVerification() {
        String oversized = "x".repeat(65);

        assertThatThrownBy(() -> service.changePassword(
                42L, "current", new AppPasswordChangeRequest(oversized, "NewPassword2")))
                .hasMessage("CURRENT_PASSWORD_INVALID");
        assertThatThrownBy(() -> service.updateTwoFactor(
                42L, new AppTwoFactorUpdateRequest(true, oversized)))
                .hasMessage("CURRENT_PASSWORD_INVALID");

        verify(security, never()).updatePasswordHash(any(), any());
        verify(security, never()).upsertTwoFactor(any(), anyBoolean());
    }

    @Test
    void twoFactorUpdateRequiresPasswordAndAvailableDeliveryBeforeEnabling() {
        when(otpDelivery.available()).thenReturn(false);
        assertThatThrownBy(() -> service.updateTwoFactor(
                42L, new AppTwoFactorUpdateRequest(true, "OldPassword1")))
                .hasMessage("USER_OTP_DELIVERY_UNAVAILABLE");
        verify(security, never()).upsertTwoFactor(any(), anyBoolean());

        when(otpDelivery.available()).thenReturn(true);
        when(security.upsertTwoFactor(42L, true)).thenReturn(1);
        var result = service.updateTwoFactor(42L, new AppTwoFactorUpdateRequest(true, "OldPassword1"));

        assertThat(result.twoFactorEnabled()).isTrue();
        verify(security).upsertTwoFactor(42L, true);
        verify(audit).recordRequired(any());
    }

    private UserSessionEntity session(String id, String device, String ip, LocalDateTime lastActiveAt) {
        UserSessionEntity row = new UserSessionEntity();
        row.setUserId(42L);
        row.setRefreshTokenId(id);
        row.setDeviceName(device);
        row.setClientIp(ip);
        row.setLastActiveAt(lastActiveAt);
        row.setExpiresAt(lastActiveAt.plusDays(30));
        row.setCreatedAt(lastActiveAt.minusMinutes(1));
        row.setIsDeleted(0);
        return row;
    }
}

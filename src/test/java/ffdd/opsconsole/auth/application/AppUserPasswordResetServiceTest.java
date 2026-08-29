package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.dto.UserOtpLoginRequest;
import ffdd.opsconsole.auth.dto.UserPasswordResetOtpCompleteRequest;
import ffdd.opsconsole.auth.infrastructure.UserOtpSendGuardRecord;
import ffdd.opsconsole.auth.mapper.AppUserSecurityMapper;
import ffdd.opsconsole.auth.mapper.UserLoginGuardMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.UserAccountBlocklistVerifier;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AppUserPasswordResetServiceTest {
    private final UserOpsMapper users = mock(UserOpsMapper.class);
    private final AppUserSecurityMapper security = mock(AppUserSecurityMapper.class);
    private final AuthSessionMapper sessions = mock(AuthSessionMapper.class);
    private final UserLoginGuardMapper guards = mock(UserLoginGuardMapper.class);
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final UserAccountBlocklistVerifier blocklist = mock(UserAccountBlocklistVerifier.class);
    private final UserOtpDeliveryService delivery = mock(UserOtpDeliveryService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final AppUserPasswordResetService service = new AppUserPasswordResetService(
            users, security, sessions, guards, passwords, blocklist, delivery, audit, outbox, environment);

    @BeforeEach
    void defaults() {
        environment.setActiveProfiles("dev");
        when(delivery.available()).thenReturn(true);
        when(delivery.verificationCode()).thenReturn("123456");
        UserOtpSendGuardRecord guard = new UserOtpSendGuardRecord();
        guard.setWindowStartedAt(LocalDateTime.now().minusMinutes(20));
        guard.setWindowSendCount(0);
        guard.setDayStartedAt(LocalDate.now());
        guard.setDaySendCount(0);
        when(guards.lockOtpSendGuard(any())).thenReturn(guard);
        when(guards.recordOtpSend(any(), any(), any(), anyInt(), any(), anyInt())).thenReturn(1);
    }

    @Test
    void resetChallengesUseDedicatedPurposeAndNeverIssueASession() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.createLoginOtpChallenge(eq(42L), any(), any(), anyInt())).thenReturn(1);

        var result = service.send(new UserOtpLoginRequest("+81", "9012345678"), "203.0.113.1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().challengeNo()).startsWith("RESET-");
        verify(users).invalidateOpenPasswordResetChallenges(42L);
        verify(delivery).deliver(eq("+81"), eq("9012345678"),
                eq(result.getData().challengeNo()), any(), eq(5));
        verify(sessions, never()).insert(any(UserSessionEntity.class));
    }

    @Test
    void validResetConsumesExactChallengeChangesHashAndRevokesEverySession() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.consumeValidLoginOtp(42L, "RESET-0123456789abcdef0123456789abcdef", "123456")).thenReturn(1);
        when(security.passwordHashForUpdate(42L)).thenReturn(passwords.encode("OldPassword1!"));
        when(security.updatePasswordHash(eq(42L), any())).thenReturn(1);
        when(security.markPasswordChanged(42L)).thenReturn(1);
        when(sessions.revokeAllUserSessions(42L)).thenReturn(3);

        var result = service.complete(new UserPasswordResetOtpCompleteRequest(
                "+81", "9012345678", "RESET-0123456789abcdef0123456789abcdef",
                "123456", "NewPassword2!"), "203.0.113.1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("status", "PASSWORD_RESET")
                .containsEntry("revokedSessionCount", 3);
        verify(security).updatePasswordHash(eq(42L), any());
        verify(sessions).revokeAllUserSessions(42L);
        verify(audit).recordRequired(any());
        verify(outbox).publish(eq("USER_SECURITY"), eq("42"), eq("auth.password_reset_completed"), any());
    }

    @Test
    void loginChallengeCannotBeRepurposedForPasswordReset() {
        var result = service.complete(new UserPasswordResetOtpCompleteRequest(
                "+81", "9012345678", "LOGIN-0123456789abcdef0123456789abcdef",
                "123456", "NewPassword2!"), "203.0.113.1");

        assertThat(result.getCode()).isEqualTo(422);
        verify(users, never()).consumeValidLoginOtp(any(), any(), any());
        verify(security, never()).updatePasswordHash(any(), any());
    }

    private UserEntity activeUser() {
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setCountryCode("+81");
        user.setPhone("9012345678");
        user.setStatus("ACTIVE");
        user.setSandbox(0);
        user.setIsDeleted(0);
        return user;
    }
}

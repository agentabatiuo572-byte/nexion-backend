package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.dto.UserOtpLoginRequest;
import ffdd.opsconsole.auth.dto.UserOtpLoginVerifyRequest;
import ffdd.opsconsole.auth.dto.UserPasswordResetOtpCompleteRequest;
import ffdd.opsconsole.auth.captcha.CaptchaOtpGate;
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
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
    private final ffdd.opsconsole.platform.facade.PlatformConfigFacade configFacade = mock(ffdd.opsconsole.platform.facade.PlatformConfigFacade.class);
    private final CaptchaOtpGate captchaGate = mock(CaptchaOtpGate.class);
    private final AppUserPasswordResetService service = new AppUserPasswordResetService(
            users, security, sessions, guards, passwords, blocklist, delivery, audit, outbox, environment, configFacade, captchaGate);

    @BeforeEach
    void defaults() {
        environment.setActiveProfiles("dev");
        when(configFacade.activeValue(any())).thenReturn(Optional.empty());
        when(delivery.available(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(delivery.verificationCode(org.mockito.ArgumentMatchers.anyString())).thenReturn("123456");
        UserOtpSendGuardRecord guard = new UserOtpSendGuardRecord();
        guard.setWindowStartedAt(LocalDateTime.now().minusMinutes(20));
        guard.setWindowSendCount(0);
        guard.setDayStartedAt(LocalDateTime.now().minusHours(25));
        guard.setDaySendCount(0);
        when(guards.lockOtpSendGuard(any())).thenReturn(guard);
        when(guards.recordOtpSend(any(), any(), any(), anyInt(), any(), anyInt())).thenReturn(1);
        when(guards.countRecentOtpSendEvents(any(), any())).thenReturn(0);
        when(guards.insertOtpSendEvent(any(), any())).thenReturn(1);
        when(captchaGate.checkAndConsume(any(), any(), any(), anyInt()))
                .thenReturn(new CaptchaOtpGate.Decision(true, 0, "OK"));
    }

    @Test
    void passwordResetOtpRejectsCountryCodesOutsideTheFormalAppWhitelist() {
        var result = service.send(
                new UserOtpLoginRequest("+39", "3123456789"), "203.0.113.1");

        assertThat(result.getCode()).isEqualTo(422);
        verify(guards, never()).initializeOtpSendGuard(any(), any());
        verify(delivery, never()).deliver(any(), any(), any(), any(), anyInt());
    }

    @Test
    void resetCaptchaRejectionPrecedesChallengeAndSmsCreation() {
        when(captchaGate.checkAndConsume(any(), any(), any(), anyInt()))
                .thenReturn(new CaptchaOtpGate.Decision(false, 428, "USER_CAPTCHA_REQUIRED"));

        var result = service.send(new UserOtpLoginRequest("+84", "901234567"), "203.0.113.2");

        assertThat(result.getCode()).isEqualTo(428);
        assertThat(result.getMessage()).isEqualTo("USER_CAPTCHA_REQUIRED");
        verify(delivery, never()).deliver(any(), any(), any(), any(), anyInt());
    }

    @Test
    void passwordResetOtpRejectsInvalidNationalNumberBeforeRateLimitMutation() {
        var result = service.send(
                new UserOtpLoginRequest("+86", "12800138000"), "203.0.113.1");

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("USER_PASSWORD_RESET_REQUEST_INVALID");
        verify(guards, never()).initializeOtpSendGuard(any(), any());
        verify(delivery, never()).deliver(any(), any(), any(), any(), anyInt());
    }

    @Test
    void resetChallengesUseDedicatedPurposeAndNeverIssueASession() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.createLoginOtpChallenge(eq(42L), any(), any(), anyInt())).thenReturn(1);

        var result = service.send(new UserOtpLoginRequest("+84", "901234567"), "203.0.113.1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().challengeNo()).startsWith("RESET-");
        verify(users).invalidateOpenPasswordResetChallenges(42L);
        verify(delivery).deliver(eq("+84"), eq("901234567"),
                eq(result.getData().challengeNo()), any(), eq(5));
        verify(sessions, never()).insert(any(UserSessionEntity.class));
    }

    @Test
    void equivalentLocalPhoneFormsShareOnePasswordResetOtpRateLimitIdentity() {
        when(users.selectOne(any())).thenReturn(activeUser());
        when(users.createLoginOtpChallenge(eq(42L), any(), any(), anyInt())).thenReturn(1);

        var localForm = service.send(
                new UserOtpLoginRequest("+84", "0901234567"), "198.51.100.41");
        var internationalForm = service.send(
                new UserOtpLoginRequest("84", "901234567"), "198.51.100.42");

        assertThat(localForm.getCode()).isZero();
        assertThat(internationalForm.getCode()).isZero();
        ArgumentCaptor<String> otpRateKey = ArgumentCaptor.forClass(String.class);
        verify(guards, times(4)).initializeOtpSendGuard(otpRateKey.capture(), any());
        assertThat(otpRateKey.getAllValues().get(1)).isEqualTo(otpRateKey.getAllValues().get(3));
        assertThat(otpRateKey.getAllValues().get(0)).isNotEqualTo(otpRateKey.getAllValues().get(2));
    }

    @Test
    void resetChallengeConsumesConfiguredK2TtlAndCooldown() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.createLoginOtpChallenge(eq(42L), any(), any(), eq(7))).thenReturn(1);
        when(configFacade.activeValue("auth.risk.otp_ttl_minutes")).thenReturn(Optional.of("7"));
        when(configFacade.activeValue("auth.risk.otp_send_cooldown_seconds")).thenReturn(Optional.of("90"));
        when(configFacade.activeValue("auth.risk.otp_send_day_limit")).thenReturn(Optional.of("6"));

        var result = service.send(new UserOtpLoginRequest("+84", "901234567"), "203.0.113.1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().resendAfterSec()).isEqualTo(90);
        verify(delivery).deliver(eq("+84"), eq("901234567"), eq(result.getData().challengeNo()), any(), eq(7));
    }

    @Test
    void deliveryFailureInvalidatesTheCreatedResetChallengeAndFailsClosed() {
        when(users.selectOne(any())).thenReturn(activeUser());
        when(users.createLoginOtpChallenge(eq(42L), any(), any(), anyInt())).thenReturn(1);
        doThrow(new IllegalStateException("provider unavailable"))
                .when(delivery).deliver(any(), any(), any(), any(), anyInt());

        var result = service.send(
                new UserOtpLoginRequest("+84", "901234567"), "198.51.100.43");

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("USER_OTP_DELIVERY_FAILED");
        verify(users, times(2)).invalidateOpenPasswordResetChallenges(42L);
    }

    @Test
    void resetGuardRejectsWhenTrailingTwentyFourHourEventsReachTheConfiguredLimit() {
        UserOtpSendGuardRecord guard = new UserOtpSendGuardRecord();
        guard.setWindowStartedAt(LocalDateTime.now().minusMinutes(20));
        guard.setWindowSendCount(0);
        when(guards.lockOtpSendGuard(any())).thenReturn(guard);
        when(guards.countRecentOtpSendEvents(any(), any())).thenReturn(0, 5);
        when(configFacade.activeValue("auth.risk.otp_send_day_limit")).thenReturn(Optional.of("5"));

        var result = service.send(new UserOtpLoginRequest("+84", "901234567"), "203.0.113.1");

        assertThat(result.getCode()).isEqualTo(429);
        assertThat(result.getMessage()).isEqualTo("USER_OTP_SEND_RATE_LIMITED");
        verify(delivery, never()).deliver(any(), any(), any(), any(), anyInt());
        // The caller quota was consumed, but the exhausted destination quota
        // must not record another destination send.
        verify(guards, times(1)).insertOtpSendEvent(any(), any());
    }

    @Test
    void publicResetSendHasAnIndependentClientWindowBeforeDestinationQuota() {
        UserOtpSendGuardRecord clientGuard = new UserOtpSendGuardRecord();
        clientGuard.setWindowStartedAt(LocalDateTime.now().minusMinutes(1));
        clientGuard.setWindowSendCount(10);
        when(guards.lockOtpSendGuard(any())).thenReturn(clientGuard);

        var result = service.send(
                new UserOtpLoginRequest("+84", "901234567"), "203.0.113.9");

        assertThat(result.getCode()).isEqualTo(429);
        assertThat(result.getMessage()).isEqualTo("USER_PASSWORD_RESET_CLIENT_RATE_LIMITED");
        verify(users, never()).selectOne(any());
        verify(delivery, never()).deliver(any(), any(), any(), any(), anyInt());
        verify(guards, never()).insertOtpSendEvent(any(), any());
    }

    @Test
    void validResetConsumesExactChallengeChangesHashAndRevokesEverySession() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.countValidLoginOtp(42L, "RESET-0123456789abcdef0123456789abcdef", "123456")).thenReturn(1);
        when(users.consumeValidLoginOtp(42L, "RESET-0123456789abcdef0123456789abcdef", "123456")).thenReturn(1);
        when(security.passwordHashForUpdate(42L)).thenReturn(passwords.encode("OldPassword1!"));
        when(security.updatePasswordHash(eq(42L), any())).thenReturn(1);
        when(security.markPasswordChanged(42L)).thenReturn(1);
        when(sessions.revokeAllUserSessions(42L)).thenReturn(3);

        var result = service.complete(new UserPasswordResetOtpCompleteRequest(
                "+84", "901234567", "RESET-0123456789abcdef0123456789abcdef",
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
    void validResetOtpCanBeVerifiedWithoutConsumptionOrSecurityMutation() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.countValidLoginOtp(42L, "RESET-0123456789abcdef0123456789abcdef", "123456"))
                .thenReturn(1);

        var result = service.verify(new UserOtpLoginVerifyRequest(
                "+84", "901234567", "RESET-0123456789abcdef0123456789abcdef", "123456"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("status", "PASSWORD_RESET_OTP_VERIFIED");
        verify(users, never()).consumeValidLoginOtp(any(), any(), any());
        verify(security, never()).updatePasswordHash(any(), any());
        verify(sessions, never()).revokeAllUserSessions(any());
    }

    @Test
    void invalidResetOtpIsRejectedAndRecordsTheAttemptWithoutConsumption() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.countValidLoginOtp(42L, "RESET-0123456789abcdef0123456789abcdef", "654321"))
                .thenReturn(0);

        var result = service.verify(new UserOtpLoginVerifyRequest(
                "+84", "901234567", "RESET-0123456789abcdef0123456789abcdef", "654321"));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("USER_PASSWORD_RESET_CHALLENGE_INVALID");
        verify(users).recordInvalidLoginOtpAttempt(42L, "RESET-0123456789abcdef0123456789abcdef");
        verify(users, never()).consumeValidLoginOtp(any(), any(), any());
    }

    @Test
    void samePasswordIsRejectedAfterOtpValidationWithoutConsumingTheChallenge() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.countValidLoginOtp(42L, "RESET-0123456789abcdef0123456789abcdef", "123456")).thenReturn(1);
        when(security.passwordHashForUpdate(42L)).thenReturn(passwords.encode("OldPassword1!"));

        var result = service.complete(new UserPasswordResetOtpCompleteRequest(
                "+84", "901234567", "RESET-0123456789abcdef0123456789abcdef",
                "123456", "OldPassword1!"), "203.0.113.1");

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("USER_NEW_PASSWORD_MUST_DIFFER");
        verify(users, never()).consumeValidLoginOtp(any(), any(), any());
        verify(security, never()).updatePasswordHash(any(), any());
        verify(sessions, never()).revokeAllUserSessions(any());
    }

    @Test
    void loginChallengeCannotBeRepurposedForPasswordReset() {
        var result = service.complete(new UserPasswordResetOtpCompleteRequest(
                "+84", "901234567", "LOGIN-0123456789abcdef0123456789abcdef",
                "123456", "NewPassword2!"), "203.0.113.1");

        assertThat(result.getCode()).isEqualTo(422);
        verify(users, never()).consumeValidLoginOtp(any(), any(), any());
        verify(security, never()).updatePasswordHash(any(), any());
    }

    private UserEntity activeUser() {
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setCountryCode("+84");
        user.setPhone("901234567");
        user.setStatus("ACTIVE");
        user.setSandbox(0);
        user.setIsDeleted(0);
        return user;
    }
}

package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.dto.UserLoginRequest;
import ffdd.opsconsole.auth.dto.UserOtpLoginRequest;
import ffdd.opsconsole.auth.dto.UserOtpLoginVerifyRequest;
import ffdd.opsconsole.auth.dto.UserPasswordResetCompleteRequest;
import ffdd.opsconsole.auth.dto.UserRefreshRequest;
import ffdd.opsconsole.auth.dto.UserTwoFactorLoginRequest;
import ffdd.opsconsole.auth.captcha.CaptchaOtpGate;
import ffdd.opsconsole.auth.mapper.UserLoginGuardMapper;
import ffdd.opsconsole.auth.infrastructure.UserLoginGuardRecord;
import ffdd.opsconsole.auth.infrastructure.UserOtpSendGuardRecord;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.security.JwtProperties;
import ffdd.opsconsole.shared.security.JwtTokenProvider;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import ffdd.opsconsole.shared.security.UserAccountBlocklistVerifier;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.util.List;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AppUserAuthServiceTest {
    private final UserOpsMapper users = mock(UserOpsMapper.class);
    private final AuthSessionMapper sessions = mock(AuthSessionMapper.class);
    private final UserLoginGuardMapper loginGuards = mock(UserLoginGuardMapper.class);
    private final JwtTokenProvider tokens = mock(JwtTokenProvider.class);
    private final JwtProperties properties = new JwtProperties();
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final UserAccountBlocklistVerifier blocklistVerifier = mock(UserAccountBlocklistVerifier.class);
    private final PlatformConfigFacade configFacade = mock(PlatformConfigFacade.class);
    private final UserOtpDeliveryService otpDelivery = mock(UserOtpDeliveryService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final CaptchaOtpGate captchaGate = mock(CaptchaOtpGate.class);
    private final AppUserAuthService service;

    AppUserAuthServiceTest() {
        environment.setActiveProfiles("dev");
        properties.setTtlMinutes(120);
        when(configFacade.activeValue(any())).thenReturn(Optional.empty());
        when(otpDelivery.available(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(otpDelivery.verificationCode(org.mockito.ArgumentMatchers.anyString())).thenReturn("123456");
        when(users.createLoginOtpChallenge(any(), any(), any(), any(Integer.class))).thenReturn(1);
        when(loginGuards.lockOtpSendGuard(any())).thenAnswer(ignored -> freshOtpSendGuard());
        when(loginGuards.recordOtpSend(any(), any(), any(), any(Integer.class), any(), any(Integer.class))).thenReturn(1);
        when(loginGuards.countRecentOtpSendEvents(any(), any())).thenReturn(0);
        when(loginGuards.insertOtpSendEvent(any(), any())).thenReturn(1);
        service = new AppUserAuthService(
                users, sessions, loginGuards, passwords, tokens, properties, blocklistVerifier, configFacade, captchaGate, otpDelivery, outbox, environment);
        when(captchaGate.checkAndConsume(any(), any(), any(), anyInt()))
                .thenReturn(new CaptchaOtpGate.Decision(true, 0, "OK"));
    }

    @Test
    void validDatabaseUserGetsSessionBackedUserToken() {
        environment.setActiveProfiles("dev");
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setCountryCode("+84");
        user.setPhone("901234567");
        user.setNickname("Nexion user");
        user.setStatus("ACTIVE");
        user.setIsDeleted(0);
        user.setSandbox(0);
        user.setPasswordHash(passwords.encode("secret"));
        when(users.selectOne(any())).thenReturn(user);
        when(users.isOnboardingComplete(42L)).thenReturn(true);
        when(tokens.createUserToken(eq(42L), eq("901234567"), eq(List.of()), any(), any(Duration.class), eq(UserAuthEnvironment.PRODUCTION)))
                .thenReturn("signed-token");

        var result = service.login(new UserLoginRequest("84", "901234567", "secret"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().accessToken()).isEqualTo("signed-token");
        assertThat(result.getData().user().onboardingComplete()).isTrue();
        ArgumentCaptor<UserSessionEntity> saved = ArgumentCaptor.forClass(UserSessionEntity.class);
        verify(sessions).insert(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(42L);
        assertThat(saved.getValue().getRefreshTokenId()).isNotBlank();
        assertThat(saved.getValue().getExpiresAt()).isNotNull();
    }

    @Test
    void wrongPasswordDoesNotCreateSession() {
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setCountryCode("+84");
        user.setPhone("901234567");
        user.setStatus("ACTIVE");
        user.setIsDeleted(0);
        user.setPasswordHash(passwords.encode("secret"));
        when(users.selectOne(any())).thenReturn(user);

        var result = service.login(new UserLoginRequest("+84", "901234567", "wrong"));

        assertThat(result.getCode()).isEqualTo(401);
        assertThat(result.getMessage()).isEqualTo("USER_CREDENTIAL_INVALID");
    }

    @Test
    void validCredentialsAreRejectedWhenC2BlocklistIsActive() {
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setCountryCode("+84");
        user.setPhone("901234567");
        user.setStatus("ACTIVE");
        user.setIsDeleted(0);
        user.setPasswordHash(passwords.encode("secret"));
        when(users.selectOne(any())).thenReturn(user);
        when(blocklistVerifier.isBlocked(42L)).thenReturn(true);

        var result = service.login(new UserLoginRequest("+84", "901234567", "secret"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("ACCOUNT_BLOCKLISTED");
        org.mockito.Mockito.verifyNoInteractions(sessions);
    }

    @Test
    void lockedAccountKeyIsRejectedBeforePasswordVerification() {
        UserLoginGuardRecord guard = new UserLoginGuardRecord();
        guard.setFailedCount(5);
        guard.setWindowStartedAt(LocalDateTime.now().minusMinutes(1));
        guard.setLockedUntil(LocalDateTime.now().plusMinutes(14));
        when(loginGuards.lock(any())).thenReturn(null, guard);

        var result = service.login(new UserLoginRequest("+84", "901234567", "secret"));

        assertThat(result.getCode()).isEqualTo(429);
        assertThat(result.getMessage()).isEqualTo("USER_LOGIN_TEMPORARILY_LOCKED");
    }

    @Test
    void c2AllowlistNeverBypassesTheAccountAuthenticationLock() {
        UserLoginGuardRecord guard = new UserLoginGuardRecord();
        guard.setFailedCount(5);
        guard.setWindowStartedAt(LocalDateTime.now().minusMinutes(1));
        guard.setLockedUntil(LocalDateTime.now().plusMinutes(14));
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setCountryCode("+84");
        user.setPhone("901234567");
        user.setNickname("Trusted user");
        user.setStatus("ACTIVE");
        user.setIsDeleted(0);
        user.setPasswordHash(passwords.encode("secret"));
        when(loginGuards.lock(any())).thenReturn(null, guard);
        when(users.selectOne(any())).thenReturn(user);
        when(blocklistVerifier.isAllowlisted(42L)).thenReturn(true);
        var result = service.login(new UserLoginRequest("+84", "901234567", "secret"));

        assertThat(result.getCode()).isEqualTo(429);
        assertThat(result.getMessage()).isEqualTo("USER_LOGIN_TEMPORARILY_LOCKED");
        verify(tokens, never()).createUserToken(any(), any(), any(), any(), any(), any());
    }

    @Test
    void fifthFailureStartsTheFifteenMinuteLock() {
        UserLoginGuardRecord guard = new UserLoginGuardRecord();
        guard.setFailedCount(4);
        guard.setWindowStartedAt(LocalDateTime.now().minusMinutes(1));
        when(loginGuards.lock(any())).thenReturn(null, guard);

        var result = service.login(new UserLoginRequest("+84", "901234567", "wrong"));

        assertThat(result.getCode()).isEqualTo(401);
        verify(loginGuards).recordFailure(any(), eq(5), eq(guard.getWindowStartedAt()), any(LocalDateTime.class));
    }

    @Test
    void malformedEqualLongThresholdFailsSafeToTheNextCountInsteadOfImmediateLongLock() {
        UserLoginGuardRecord guard = new UserLoginGuardRecord();
        guard.setFailedCount(4);
        guard.setWindowStartedAt(LocalDateTime.now().minusMinutes(1));
        when(loginGuards.lock(any())).thenReturn(null, guard);
        when(configFacade.activeValue("auth.risk.login_lock_threshold")).thenReturn(Optional.of("5"));
        when(configFacade.activeValue("auth.risk.login_long_lock_threshold")).thenReturn(Optional.of("5"));
        LocalDateTime before = LocalDateTime.now();

        service.login(new UserLoginRequest("+84", "901234567", "wrong"));

        ArgumentCaptor<LocalDateTime> lockedUntil = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(loginGuards).recordFailure(any(), eq(5), eq(guard.getWindowStartedAt()), lockedUntil.capture());
        assertThat(lockedUntil.getValue()).isBetween(before.plusMinutes(14), before.plusMinutes(16));
    }

    @Test
    void lockedClientAddressIsRateLimitedBeforeAccountRowsAreCreated() {
        UserLoginGuardRecord guard = new UserLoginGuardRecord();
        guard.setFailedCount(60);
        guard.setWindowStartedAt(LocalDateTime.now().minusSeconds(10));
        guard.setLockedUntil(LocalDateTime.now().plusSeconds(50));
        when(loginGuards.lock(any())).thenReturn(guard);

        var result = service.login(new UserLoginRequest("+84", "901234567", "secret"), "203.0.113.9");

        assertThat(result.getCode()).isEqualTo(429);
        assertThat(result.getMessage()).isEqualTo("USER_LOGIN_RATE_LIMITED");
    }

    @Test
    void twoFactorEnabledUserNeverReceivesTokenWithoutSecondFactor() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.isTwoFactorEnabled(42L)).thenReturn(true);

        var result = service.login(new UserLoginRequest("+84", "901234567", "secret"));

        assertThat(result.getCode()).isEqualTo(428);
        assertThat(result.getMessage()).isEqualTo("USER_TWO_FACTOR_VERIFICATION_REQUIRED");
        assertThat(result.getData().challengeNo()).startsWith("OTP-");
        verify(users).createLoginOtpChallenge(eq(42L), eq(result.getData().challengeNo()), any(), eq(5));
        verify(otpDelivery).deliver(eq("+84"), eq("901234567"), eq(result.getData().challengeNo()), any(), eq(5));
        org.mockito.Mockito.verifyNoInteractions(sessions, tokens);
    }

    @Test
    void loginOtpCaptchaRejectionPrecedesChallengeAndSmsCreation() {
        when(captchaGate.checkAndConsume(any(), any(), any(), anyInt()))
                .thenReturn(new CaptchaOtpGate.Decision(false, 428, "USER_CAPTCHA_REQUIRED"));

        var result = service.beginOtpLogin(new UserOtpLoginRequest("+84", "901234567"), "198.51.100.1");

        assertThat(result.getCode()).isEqualTo(428);
        assertThat(result.getMessage()).isEqualTo("USER_CAPTCHA_REQUIRED");
        verify(otpDelivery, never()).deliver(any(), any(), any(), any(), any(Integer.class));
    }

    @Test
    void registeredSessionRejectsAnIdentityOutsideThePhoneCountryAllowlist() {
        UserEntity user = activeUser();
        user.setCountryCode("+1");

        assertThatThrownBy(() -> service.issueRegisteredSession(user, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .hasMessage("USER_REGISTRATION_COUNTRY_CODE_FORBIDDEN");
        verify(sessions, never()).insert(any(UserSessionEntity.class));
        verify(tokens, never()).createUserToken(any(), any(), any(), any(), any(), any());
    }

    @Test
    void registeredSessionRejectsAnIdentityWithAnInvalidSupportedCountryPhone() {
        UserEntity user = activeUser();
        user.setCountryCode("+86");
        user.setPhone("12800138000");

        assertThatThrownBy(() -> service.issueRegisteredSession(user, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .hasMessage("USER_REGISTRATION_PHONE_INVALID");
        verify(sessions, never()).insert(any(UserSessionEntity.class));
        verify(tokens, never()).createUserToken(any(), any(), any(), any(), any(), any());
    }

    @Test
    void twoFactorChallengeUsesTheSharedOtpCooldownBeforeSending() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.isTwoFactorEnabled(42L)).thenReturn(true);
        UserOtpSendGuardRecord cooldown = freshOtpSendGuard();
        cooldown.setLastSentAt(LocalDateTime.now());
        when(loginGuards.lockOtpSendGuard(any())).thenReturn(cooldown);

        var result = service.login(new UserLoginRequest("+84", "901234567", "secret"));

        assertThat(result.getCode()).isEqualTo(429);
        assertThat(result.getMessage()).isEqualTo("USER_OTP_SEND_RATE_LIMITED");
        verify(users, never()).createLoginOtpChallenge(any(), any(), any(), any(Integer.class));
        verify(otpDelivery, never()).deliver(any(), any(), any(), any(), anyInt());
    }

    @Test
    void passwordLoginForwardsOpaqueCaptchaTicketToTheTwoFactorOtpGate() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.isTwoFactorEnabled(42L)).thenReturn(true);

        var result = service.login(new UserLoginRequest(
                "+84", "901234567", "secret", "captcha-provider-ticket"), "203.0.113.9");

        assertThat(result.getCode()).isEqualTo(428);
        assertThat(result.getMessage()).isEqualTo("USER_TWO_FACTOR_VERIFICATION_REQUIRED");
        verify(captchaGate).checkAndConsume(
                eq(ffdd.opsconsole.auth.captcha.CaptchaScene.LOGIN),
                eq("captcha-provider-ticket"), eq("203.0.113.9"), eq(0));
    }

    @Test
    void twoFactorDeliveryFailureConsumesThrottleAndInvalidatesTheChallenge() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.isTwoFactorEnabled(42L)).thenReturn(true);
        doThrow(new IllegalStateException("ambiguous provider timeout"))
                .when(otpDelivery).deliver(any(), any(), any(), any(), any(Integer.class));

        var result = service.login(new UserLoginRequest("+84", "901234567", "secret"));

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("USER_OTP_DELIVERY_FAILED");
        verify(loginGuards).insertOtpSendEvent(any(), any());
        verify(users, times(2)).invalidateOpenLoginOtpChallenges(42L);
    }

    @Test
    void passwordResetRequiredUserMustCompleteResetBeforeSessionIssuance() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.isPasswordResetRequired(42L)).thenReturn(true);

        var result = service.login(new UserLoginRequest("+84", "901234567", "secret"));

        assertThat(result.getCode()).isEqualTo(428);
        assertThat(result.getMessage()).isEqualTo("USER_PASSWORD_RESET_REQUIRED");
        org.mockito.Mockito.verifyNoInteractions(sessions, tokens);
    }

    @Test
    void configuredAccessAndRefreshTtlsDriveIssuedTokenAndSession() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(configFacade.activeValue("auth.session.access_ttl_hours")).thenReturn(Optional.of("4"));
        when(configFacade.activeValue("auth.session.refresh_ttl_days")).thenReturn(Optional.of("30"));
        when(tokens.createUserToken(eq(42L), eq("901234567"), eq(List.of()), any(), eq(Duration.ofHours(4)), eq(UserAuthEnvironment.PRODUCTION)))
                .thenReturn("configured-token");

        LocalDateTime before = LocalDateTime.now();
        var result = service.login(new UserLoginRequest("+84", "901234567", "secret"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<UserSessionEntity> saved = ArgumentCaptor.forClass(UserSessionEntity.class);
        verify(sessions).insert(saved.capture());
        assertThat(saved.getValue().getExpiresAt()).isBetween(before.plusDays(30), LocalDateTime.now().plusDays(30));
    }

    @Test
    void forcedPasswordResetChangesHashClearsFlagAndIssuesSession() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.isPasswordResetRequired(42L)).thenReturn(true);
        when(users.updatePasswordHash(eq(42L), any())).thenReturn(1);
        when(users.clearPasswordResetRequired(42L)).thenReturn(1);
        when(tokens.createUserToken(eq(42L), eq("901234567"), eq(List.of()), any(), any(Duration.class), eq(UserAuthEnvironment.PRODUCTION)))
                .thenReturn("reset-token");

        var result = service.completePasswordReset(new UserPasswordResetCompleteRequest(
                "+84", "901234567", "secret", "NewSecure@2026"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().accessToken()).isEqualTo("reset-token");
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(users).updatePasswordHash(eq(42L), hash.capture());
        assertThat(passwords.matches("NewSecure@2026", hash.getValue())).isTrue();
        verify(users).clearPasswordResetRequired(42L);
        verify(sessions).insert(any(UserSessionEntity.class));
    }

    @Test
    void twoFactorLoginConsumesServerChallengeBeforeIssuingToken() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.isTwoFactorEnabled(42L)).thenReturn(true);
        String challengeNo = "OTP-0123456789abcdef0123456789abcdef";
        when(users.consumeValidLoginOtp(42L, challengeNo, "123456")).thenReturn(1);
        when(tokens.createUserToken(eq(42L), eq("901234567"), eq(List.of()), any(), any(Duration.class), eq(UserAuthEnvironment.PRODUCTION)))
                .thenReturn("mfa-token");

        var result = service.completeTwoFactorLogin(new UserTwoFactorLoginRequest(
                "+84", "901234567", "secret", challengeNo, "123456"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().accessToken()).isEqualTo("mfa-token");
        verify(users).consumeValidLoginOtp(42L, challengeNo, "123456");
        verify(sessions).insert(any(UserSessionEntity.class));
    }

    @Test
    void twoFactorLoginRejectsRegistrationAndPasswordlessChallengePurposes() {
        var loginPurpose = service.completeTwoFactorLogin(new UserTwoFactorLoginRequest(
                "+84", "901234567", "secret", "LOGIN-0123456789abcdef0123456789abcdef", "123456"));
        var registrationPurpose = service.completeTwoFactorLogin(new UserTwoFactorLoginRequest(
                "+84", "901234567", "secret", "REG-0123456789abcdef0123456789abcdef", "123456"));

        assertThat(loginPurpose.getCode()).isEqualTo(422);
        assertThat(registrationPurpose.getCode()).isEqualTo(422);
        verify(users, never()).consumeValidLoginOtp(any(), any(), any());
        verify(sessions, never()).insert(any(UserSessionEntity.class));
    }

    @Test
    void otpLoginRequiresItsOwnServerChallengeBeforeIssuingToken() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.consumeValidLoginOtp(eq(42L), any(), eq("123456"))).thenReturn(1);
        when(tokens.createUserToken(eq(42L), eq("901234567"), eq(List.of()), any(), any(Duration.class), eq(UserAuthEnvironment.PRODUCTION)))
                .thenReturn("otp-login-token");

        var sent = service.beginOtpLogin(new UserOtpLoginRequest("+84", "901234567"));
        var verified = service.completeOtpLogin(new UserOtpLoginVerifyRequest(
                "+84", "901234567", sent.getData().challengeNo(), "123456"));

        assertThat(sent.getCode()).isZero();
        assertThat(sent.getData().challengeNo()).startsWith("LOGIN-");
        assertThat(verified.getCode()).isZero();
        assertThat(verified.getData().accessToken()).isEqualTo("otp-login-token");
        verify(users).consumeValidLoginOtp(eq(42L), eq(sent.getData().challengeNo()), eq("123456"));
    }

    @Test
    void otpLoginCannotConsumeASecondFactorChallenge() {
        var result = service.completeOtpLogin(new UserOtpLoginVerifyRequest(
                "+84", "901234567", "OTP-42", "123456"));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("USER_OTP_LOGIN_CHALLENGE_INVALID");
    }

    @Test
    void otpLoginStartDoesNotRevealWhetherAnAccountCanUseThePath() {
        var unknown = service.beginOtpLogin(new UserOtpLoginRequest("+84", "801234567"));

        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.isTwoFactorEnabled(42L)).thenReturn(true);
        var twoFactorProtected = service.beginOtpLogin(new UserOtpLoginRequest("+84", "901234567"));

        assertThat(unknown.getCode()).isZero();
        assertThat(unknown.getData().challengeNo()).matches("LOGIN-[a-f0-9]{32}");
        assertThat(twoFactorProtected.getCode()).isZero();
        assertThat(twoFactorProtected.getData().challengeNo()).matches("LOGIN-[a-f0-9]{32}");
        verify(otpDelivery, never()).deliver(any(), any(), eq(twoFactorProtected.getData().challengeNo()), any(), any(Integer.class));
    }

    @Test
    void otpLoginRejectsCountryCodesOutsideTheFormalAppWhitelist() {
        var result = service.beginOtpLogin(new UserOtpLoginRequest("+81", "09012345678"));

        assertThat(result.getCode()).isEqualTo(422);
        verify(loginGuards, never()).initializeOtpSendGuard(any(), any());
        verify(otpDelivery, never()).deliver(any(), any(), any(), any(), any(Integer.class));
    }

    @Test
    void otpLoginRejectsInvalidNationalNumberBeforeRateLimitMutation() {
        var result = service.beginOtpLogin(new UserOtpLoginRequest("+86", "12800138000"));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("USER_OTP_LOGIN_REQUEST_INVALID");
        verify(loginGuards, never()).initializeOtpSendGuard(any(), any());
        verify(otpDelivery, never()).deliver(any(), any(), any(), any(), any(Integer.class));
    }

    @Test
    void otpLoginConsumesConfiguredK2TtlAndCooldownWithoutChangingEnumerationResponse() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(configFacade.activeValue("auth.risk.otp_ttl_minutes")).thenReturn(Optional.of("7"));
        when(configFacade.activeValue("auth.risk.otp_send_cooldown_seconds")).thenReturn(Optional.of("90"));
        when(configFacade.activeValue("auth.risk.otp_send_day_limit")).thenReturn(Optional.of("6"));

        var known = service.beginOtpLogin(new UserOtpLoginRequest("+84", "901234567"));
        when(users.selectOne(any())).thenReturn(null);
        var unknown = service.beginOtpLogin(new UserOtpLoginRequest("+84", "801234567"));

        assertThat(known.getCode()).isZero();
        assertThat(known.getData().resendAfterSec()).isEqualTo(90);
        assertThat(unknown.getCode()).isZero();
        assertThat(unknown.getData().resendAfterSec()).isEqualTo(90);
        verify(users).createLoginOtpChallenge(eq(42L), eq(known.getData().challengeNo()), any(), eq(7));
    }

    @Test
    void otpGuardRejectsWhenTheTrailingTwentyFourHourEventQueryIsAtTheConfiguredLimit() {
        UserOtpSendGuardRecord guard = freshOtpSendGuard();
        when(loginGuards.lockOtpSendGuard(any())).thenReturn(guard);
        when(loginGuards.countRecentOtpSendEvents(any(), any())).thenReturn(5);
        when(configFacade.activeValue("auth.risk.otp_send_day_limit")).thenReturn(Optional.of("5"));
        LocalDateTime before = LocalDateTime.now();

        var result = service.beginOtpLogin(new UserOtpLoginRequest("+84", "901234567"));

        assertThat(result.getCode()).isEqualTo(429);
        assertThat(result.getMessage()).isEqualTo("USER_OTP_SEND_RATE_LIMITED");
        verify(otpDelivery, never()).deliver(any(), any(), any(), any(), any(Integer.class));
        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(loginGuards).countRecentOtpSendEvents(any(), since.capture());
        assertThat(since.getValue()).isBetween(before.minusHours(24).minusSeconds(1), LocalDateTime.now().minusHours(24).plusSeconds(1));
        verify(loginGuards, never()).insertOtpSendEvent(any(), any());
    }

    @Test
    void otpSendThrottleSurvivesIpRotationAndStopsSmsBombing() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        UserOtpSendGuardRecord fresh = freshOtpSendGuard();
        UserOtpSendGuardRecord cooldown = freshOtpSendGuard();
        cooldown.setLastSentAt(LocalDateTime.now());
        when(loginGuards.lockOtpSendGuard(any())).thenReturn(fresh, cooldown);

        var first = service.beginOtpLogin(new UserOtpLoginRequest("+84", "901234567"), "198.51.100.10");
        var rotatedIp = service.beginOtpLogin(new UserOtpLoginRequest("+84", "901234567"), "203.0.113.20");

        assertThat(first.getCode()).isZero();
        assertThat(rotatedIp.getCode()).isEqualTo(429);
        assertThat(rotatedIp.getMessage()).isEqualTo("USER_OTP_SEND_RATE_LIMITED");
        verify(otpDelivery, times(1)).deliver(eq("+84"), eq("901234567"), any(), any(), any(Integer.class));
    }

    @Test
    void equivalentLocalPhoneFormsShareOneOtpRateLimitIdentity() {
        when(users.selectOne(any())).thenReturn(activeUser());

        var localForm = service.beginOtpLogin(
                new UserOtpLoginRequest("+84", "0901234567"), "198.51.100.21");
        var internationalForm = service.beginOtpLogin(
                new UserOtpLoginRequest("84", "901234567"), "198.51.100.22");

        assertThat(localForm.getCode()).isZero();
        assertThat(internationalForm.getCode()).isZero();
        ArgumentCaptor<String> otpRateKey = ArgumentCaptor.forClass(String.class);
        verify(loginGuards, times(2)).initializeOtpSendGuard(otpRateKey.capture(), any());
        assertThat(otpRateKey.getAllValues()).containsOnly(otpRateKey.getAllValues().get(0));
    }

    @Test
    void issuingNewLoginOtpInvalidatesEveryOlderOpenLoginChallengeFirst() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);

        var result = service.beginOtpLogin(new UserOtpLoginRequest("+84", "901234567"), "198.51.100.30");

        assertThat(result.getCode()).isZero();
        var order = inOrder(users);
        order.verify(users).invalidateOpenLoginOtpChallenges(42L);
        order.verify(users).createLoginOtpChallenge(eq(42L), eq(result.getData().challengeNo()), any(), any(Integer.class));
    }

    @Test
    void deliveryFailureInvalidatesTheCreatedLoginChallengeAndFailsClosed() {
        when(users.selectOne(any())).thenReturn(activeUser());
        doThrow(new IllegalStateException("provider unavailable"))
                .when(otpDelivery).deliver(any(), any(), any(), any(), any(Integer.class));

        var result = service.beginOtpLogin(
                new UserOtpLoginRequest("+84", "901234567"), "198.51.100.33");

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("USER_OTP_DELIVERY_FAILED");
        verify(users, times(2)).invalidateOpenLoginOtpChallenges(42L);
    }

    @Test
    void allowlistCannotBypassAccountOtpLockAndFifthErrorCreatesThatLock() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(blocklistVerifier.isAllowlisted(42L)).thenReturn(true);
        UserLoginGuardRecord fourFailures = new UserLoginGuardRecord();
        fourFailures.setFailedCount(4);
        fourFailures.setWindowStartedAt(LocalDateTime.now().minusMinutes(1));
        when(loginGuards.lock(any())).thenReturn(null, fourFailures);
        String challengeNo = "LOGIN-22222222222222222222222222222222";

        var fifth = service.completeOtpLogin(new UserOtpLoginVerifyRequest(
                "+84", "901234567", challengeNo, "999999"), "198.51.100.40");

        assertThat(fifth.getCode()).isEqualTo(422);
        ArgumentCaptor<LocalDateTime> lockUntil = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(loginGuards, times(2)).recordFailure(any(), any(Integer.class), any(), lockUntil.capture());
        assertThat(lockUntil.getAllValues().get(1)).isAfter(LocalDateTime.now());

        UserLoginGuardRecord locked = new UserLoginGuardRecord();
        locked.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        when(loginGuards.lock(any())).thenReturn(null, locked);
        var blockedSend = service.beginOtpLogin(
                new UserOtpLoginRequest("+84", "901234567"), "203.0.113.40");
        assertThat(blockedSend.getCode()).isEqualTo(429);
        assertThat(blockedSend.getMessage()).isEqualTo("USER_LOGIN_TEMPORARILY_LOCKED");
    }

    @Test
    void expiredOrReplayedOtpChallengeNeverCreatesASession() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.consumeValidLoginOtp(42L, "LOGIN-00000000000000000000000000000000", "123456")).thenReturn(0);

        var result = service.completeOtpLogin(new UserOtpLoginVerifyRequest(
                "+84", "901234567", "LOGIN-00000000000000000000000000000000", "123456"));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("USER_OTP_LOGIN_CHALLENGE_INVALID");
        verify(users).recordInvalidLoginOtpAttempt(
                42L, "LOGIN-00000000000000000000000000000000");
        verify(sessions, never()).insert(any(UserSessionEntity.class));
    }

    @Test
    void allowlistNeverBypassesAtomicOtpChallengeAttemptCounting() {
        UserEntity user = activeUser();
        String challengeNo = "LOGIN-11111111111111111111111111111111";
        when(users.selectOne(any())).thenReturn(user);
        when(blocklistVerifier.isAllowlisted(42L)).thenReturn(true);
        when(users.consumeValidLoginOtp(42L, challengeNo, "999999")).thenReturn(0);
        when(users.recordInvalidLoginOtpAttempt(42L, challengeNo)).thenReturn(1);

        var result = service.completeOtpLogin(new UserOtpLoginVerifyRequest(
                "+84", "901234567", challengeNo, "999999"));

        assertThat(result.getCode()).isEqualTo(422);
        verify(users).recordInvalidLoginOtpAttempt(42L, challengeNo);
        verify(sessions, never()).insert(any(UserSessionEntity.class));
    }

    @Test
    void refreshRotatesOpaqueTokenWithoutPersistingEitherRawSecret() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(tokens.createUserToken(eq(42L), eq("901234567"), eq(List.of()), any(), any(Duration.class), eq(UserAuthEnvironment.PRODUCTION)))
                .thenReturn("initial-token", "refreshed-token");

        var login = service.login(new UserLoginRequest("+84", "901234567", "secret"));
        ArgumentCaptor<UserSessionEntity> initialCaptor = ArgumentCaptor.forClass(UserSessionEntity.class);
        verify(sessions).insert(initialCaptor.capture());
        UserSessionEntity initial = initialCaptor.getValue();
        initial.setId(101L);

        when(sessions.findRefreshForUpdate(initial.getRefreshTokenId())).thenReturn(initial);
        when(sessions.markRefreshRotated(eq(101L), any())).thenReturn(1);
        when(users.selectById(42L)).thenReturn(user);

        var refreshed = service.refresh(new UserRefreshRequest(login.getData().refreshToken()));

        assertThat(refreshed.getCode()).isZero();
        assertThat(refreshed.getData().accessToken()).isEqualTo("refreshed-token");
        assertThat(refreshed.getData().refreshToken()).isNotBlank()
                .isNotEqualTo(login.getData().refreshToken());
        assertThat(initial.getRefreshTokenId()).hasSize(64)
                .isNotEqualTo(login.getData().refreshToken());

        ArgumentCaptor<UserSessionEntity> allSessions = ArgumentCaptor.forClass(UserSessionEntity.class);
        verify(sessions, times(2)).insert(allSessions.capture());
        UserSessionEntity rotated = allSessions.getAllValues().get(1);
        assertThat(rotated.getRefreshTokenId()).hasSize(64)
                .isNotEqualTo(refreshed.getData().refreshToken());
        assertThat(rotated.getSessionChainId()).isEqualTo(initial.getSessionChainId());
        verify(sessions).markRefreshRotated(eq(101L), eq(rotated.getRefreshTokenId()));
    }

    @Test
    void refreshRejectsAnAccountWhosePersistedCountryCodeIsOutsideThePhoneAllowlist() {
        UserEntity user = activeUser();
        user.setCountryCode("+1");
        UserSessionEntity current = new UserSessionEntity();
        current.setId(101L);
        current.setUserId(42L);
        current.setSessionChainId("unsupported-country-chain");
        current.setExpiresAt(LocalDateTime.now().plusDays(1));
        current.setLastActiveAt(LocalDateTime.now());
        when(sessions.findRefreshForUpdate(any())).thenReturn(current);
        when(users.selectById(42L)).thenReturn(user);

        var result = service.refresh(new UserRefreshRequest("unsupported-country-refresh"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("USER_REFRESH_COUNTRY_CODE_FORBIDDEN");
        verify(sessions).revokeAllUserSessions(42L);
        verify(sessions, never()).markRefreshRotated(any(), any());
        verify(sessions, never()).insert(any(UserSessionEntity.class));
        verify(tokens, never()).createUserToken(any(), any(), any(), any(), any(), any());
    }

    @Test
    void refreshRevokesAllSessionsForAnInvalidSupportedCountryPhone() {
        UserEntity user = activeUser();
        user.setCountryCode("+86");
        user.setPhone("12800138000");
        UserSessionEntity current = new UserSessionEntity();
        current.setId(102L);
        current.setUserId(42L);
        current.setSessionChainId("invalid-phone-chain");
        current.setExpiresAt(LocalDateTime.now().plusDays(1));
        current.setLastActiveAt(LocalDateTime.now());
        when(sessions.findRefreshForUpdate(any())).thenReturn(current);
        when(users.selectById(42L)).thenReturn(user);

        var result = service.refresh(new UserRefreshRequest("invalid-phone-refresh"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("USER_REFRESH_PHONE_INVALID");
        verify(sessions).revokeAllUserSessions(42L);
        verify(sessions, never()).markRefreshRotated(any(), any());
        verify(sessions, never()).insert(any(UserSessionEntity.class));
        verify(tokens, never()).createUserToken(any(), any(), any(), any(), any(), any());
    }

    @Test
    void refreshReuseRevokesTheWholeChainAndPublishesSecurityEvent() {
        UserSessionEntity used = new UserSessionEntity();
        used.setId(101L);
        used.setUserId(42L);
        used.setSessionChainId("chain-42");
        used.setRotationRedeemedAt(LocalDateTime.now().minusSeconds(1));
        when(sessions.findRefreshForUpdate(any())).thenReturn(used);

        var result = service.refresh(new UserRefreshRequest("already-used-refresh-secret"));

        assertThat(result.getCode()).isEqualTo(401);
        assertThat(result.getMessage()).isEqualTo("USER_REFRESH_TOKEN_REUSE_DETECTED");
        verify(sessions).revokeRefreshChain("chain-42");
        verify(outbox).publish(eq("USER_SECURITY"), eq("42"),
                eq("auth.refresh_token_reuse_detected"), any());
    }

    @Test
    void logoutRevokesTheRefreshChainWithoutPersistingOrReturningTheRawSecret() {
        UserSessionEntity current = new UserSessionEntity();
        current.setUserId(42L);
        current.setSessionChainId("chain-42");
        when(sessions.findRefreshForUpdate(any())).thenReturn(current);

        var result = service.logout(new UserRefreshRequest("raw-refresh-secret"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("revoked", true);
        verify(sessions).findRefreshForUpdate(org.mockito.ArgumentMatchers.argThat(
                tokenId -> tokenId.length() == 64 && !tokenId.equals("raw-refresh-secret")));
        verify(sessions).revokeRefreshChain("chain-42");
    }

    @ParameterizedTest
    @MethodSource("crossEnvironmentAccounts")
    void passwordLoginRejectsBothCrossEnvironmentDirectionsBeforeTokenOrSession(String profile, int accountSandbox) {
        environment.setActiveProfiles(profile);
        UserEntity user = activeUser();
        user.setSandbox(accountSandbox);
        when(users.selectOne(any())).thenReturn(user);

        var result = service.login(new UserLoginRequest("+84", "901234567", "secret"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("USER_AUTH_ENVIRONMENT_FORBIDDEN");
        assertThat(result.getData()).isNull();
        verify(sessions, never()).insert(any(UserSessionEntity.class));
        verify(tokens, never()).createUserToken(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @MethodSource("crossEnvironmentAccounts")
    void twoFactorCompletionRejectsBothCrossEnvironmentDirectionsBeforeOtpOrSession(String profile, int accountSandbox) {
        environment.setActiveProfiles(profile);
        UserEntity user = activeUser();
        user.setSandbox(accountSandbox);
        when(users.selectOne(any())).thenReturn(user);
        when(users.isTwoFactorEnabled(42L)).thenReturn(true);
        String challengeNo = "OTP-0123456789abcdef0123456789abcdef";

        var result = service.completeTwoFactorLogin(new UserTwoFactorLoginRequest(
                "+84", "901234567", "secret", challengeNo, "123456"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("USER_AUTH_ENVIRONMENT_FORBIDDEN");
        assertThat(result.getData()).isNull();
        verify(users, never()).consumeValidLoginOtp(any(), any(), any());
        verify(sessions, never()).insert(any(UserSessionEntity.class));
        verify(tokens, never()).createUserToken(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @MethodSource("crossEnvironmentAccounts")
    void passwordlessOtpCompletionRejectsBothCrossEnvironmentDirectionsBeforeConsumingChallenge(String profile, int accountSandbox) {
        environment.setActiveProfiles(profile);
        UserEntity user = activeUser();
        user.setSandbox(accountSandbox);
        when(users.selectOne(any())).thenReturn(user);
        String challengeNo = "LOGIN-0123456789abcdef0123456789abcdef";

        var result = service.completeOtpLogin(new UserOtpLoginVerifyRequest(
                "+84", "901234567", challengeNo, "123456"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("USER_AUTH_ENVIRONMENT_FORBIDDEN");
        assertThat(result.getData()).isNull();
        verify(users, never()).consumeValidLoginOtp(any(), any(), any());
        verify(sessions, never()).insert(any(UserSessionEntity.class));
        verify(tokens, never()).createUserToken(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @MethodSource("crossEnvironmentAccounts")
    void refreshRejectsAndRevokesBothCrossEnvironmentDirections(String profile, int accountSandbox) {
        environment.setActiveProfiles(profile);
        UserEntity user = activeUser();
        user.setSandbox(accountSandbox);
        UserSessionEntity current = new UserSessionEntity();
        current.setId(101L);
        current.setUserId(42L);
        current.setSessionChainId("environment-chain");
        current.setExpiresAt(LocalDateTime.now().plusDays(1));
        current.setLastActiveAt(LocalDateTime.now());
        when(sessions.findRefreshForUpdate(any())).thenReturn(current);
        when(users.selectById(42L)).thenReturn(user);

        var result = service.refresh(new UserRefreshRequest("cross-environment-refresh"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("USER_REFRESH_ENVIRONMENT_FORBIDDEN");
        assertThat(result.getData()).isNull();
        verify(sessions).revokeRefreshChain("environment-chain");
        verify(sessions, never()).markRefreshRotated(any(), any());
        verify(sessions, never()).insert(any(UserSessionEntity.class));
    }

    @Test
    void passwordResetCompletionRejectsBeforeMutatingACrossEnvironmentAccount() {
        environment.setActiveProfiles("test");
        UserEntity user = activeUser();
        user.setSandbox(0);
        when(users.selectOne(any())).thenReturn(user);
        when(users.isPasswordResetRequired(42L)).thenReturn(true);

        var result = service.completePasswordReset(new UserPasswordResetCompleteRequest(
                "+84", "901234567", "secret", "NewSecure@2026"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("USER_AUTH_ENVIRONMENT_FORBIDDEN");
        verify(users, never()).updatePasswordHash(any(), any());
        verify(users, never()).clearPasswordResetRequired(any());
        verify(sessions, never()).insert(any(UserSessionEntity.class));
        verify(tokens, never()).createUserToken(any(), any(), any(), any(), any(), any());
    }

    @Test
    void mixedOrUnknownProfilesFailClosedBeforeAnySessionIssue() {
        environment.setActiveProfiles("prod", "dev");
        UserEntity user = activeUser();
        user.setSandbox(0);
        when(users.selectOne(any())).thenReturn(user);

        var result = service.login(new UserLoginRequest("+84", "901234567", "secret"));

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("USER_AUTH_ENVIRONMENT_FORBIDDEN");
        verify(sessions, never()).insert(any(UserSessionEntity.class));
        verify(tokens, never()).createUserToken(any(), any(), any(), any(), any(), any());
    }

    @Test
    void otpLoginRejectsMixedProfilesBeforeAnyRateOrChallengeMutation() {
        environment.setActiveProfiles("prod", "dev");

        var result = service.beginOtpLogin(
                new UserOtpLoginRequest("+84", "901234567"), "198.51.100.84");

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("USER_AUTH_ENVIRONMENT_FORBIDDEN");
        verify(loginGuards, never()).initialize(any(), any());
        verify(loginGuards, never()).recordOtpSend(any(), any(), any(), anyInt(), any(), anyInt());
        verify(users, never()).createLoginOtpChallenge(any(), any(), any(), anyInt());
        verify(otpDelivery, never()).deliver(any(), any(), any(), any(), any(Integer.class));
    }

    private static Stream<Arguments> crossEnvironmentAccounts() {
        return Stream.of(
                Arguments.of("prod", 1),
                Arguments.of("dev", 1),
                Arguments.of("test", 0));
    }

    private UserEntity activeUser() {
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setCountryCode("+84");
        user.setPhone("901234567");
        user.setNickname("Nexion user");
        user.setStatus("ACTIVE");
        user.setSandbox(0);
        user.setIsDeleted(0);
        user.setPasswordHash(passwords.encode("secret"));
        return user;
    }

    private UserOtpSendGuardRecord freshOtpSendGuard() {
        UserOtpSendGuardRecord guard = new UserOtpSendGuardRecord();
        guard.setWindowStartedAt(LocalDateTime.now().minusMinutes(16));
        guard.setWindowSendCount(0);
        guard.setDayStartedAt(LocalDateTime.now().minusHours(25));
        guard.setDaySendCount(0);
        return guard;
    }
}

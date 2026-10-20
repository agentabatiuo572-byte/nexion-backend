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
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
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
import org.junit.jupiter.params.provider.ValueSource;
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
    private final ffdd.opsconsole.auth.mapper.AppLoginAttemptMapper attempts = mock(ffdd.opsconsole.auth.mapper.AppLoginAttemptMapper.class);
    private final ffdd.opsconsole.content.application.SupportActivityService supportActivity = mock(ffdd.opsconsole.content.application.SupportActivityService.class);
    private final AppUserAuthService service;

    AppUserAuthServiceTest() {
        environment.setActiveProfiles("dev");
        properties.setTtlMinutes(120);
        when(configFacade.activeValue(any())).thenReturn(Optional.empty());
        when(tokens.sessionSyncKey(any())).thenReturn("a".repeat(64));
        when(otpDelivery.available(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(otpDelivery.verificationCode(org.mockito.ArgumentMatchers.anyString())).thenReturn("123456");
        when(users.createLoginOtpChallenge(any(), any(), any(), any(Integer.class))).thenReturn(1);
        when(loginGuards.lockOtpSendGuard(any())).thenAnswer(ignored -> freshOtpSendGuard());
        when(loginGuards.recordOtpSend(any(), any(), any(), any(Integer.class), any(), any(Integer.class))).thenReturn(1);
        when(loginGuards.countRecentOtpSendEvents(any(), any())).thenReturn(0);
        when(loginGuards.insertOtpSendEvent(any(), any())).thenReturn(1);
        var phone=mock(ffdd.opsconsole.common.boundary.PhoneExecutionSessionContract.class);
        var audit=mock(ffdd.opsconsole.shared.audit.AuditLogService.class);
        var single=new AppSingleSessionService(sessions,attempts,tokens,phone,audit,outbox,configFacade,users,blocklistVerifier,environment);
        service = new AppUserAuthService(users, sessions, loginGuards, passwords, tokens, properties, blocklistVerifier,
                configFacade, captchaGate, otpDelivery, outbox, environment, supportActivity, single,
                new ffdd.opsconsole.shared.security.UserSessionRevocationService(sessions,phone));
        when(sessions.lockAccount(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(call->call.getArgument(0));
        when(users.selectById(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(call->users.selectOne(null));
        when(users.findLoginUserForUpdate(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(call->users.selectById((Long)call.getArgument(0)));
        when(sessions.insert(any(UserSessionEntity.class))).thenReturn(1);
        when(sessions.advanceAppState(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong(),any(),any(),org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(1);
        when(attempts.insert(any())).thenReturn(1);
        when(tokens.refreshSuccessor(any(),any())).thenReturn("initial-refresh-secret");
        when(sessions.countFixedContext(any(),org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);
        when(captchaGate.checkAndConsume(any(), any(), any(), anyInt()))
                .thenReturn(new CaptchaOtpGate.Decision(true, 0, "OK"));
    }

    private static AppLoginContext context(AppSessionSurface group,String method) {
        return new AppLoginContext(group,group==AppSessionSurface.APP?"test-installation":"browser-installation",group==AppSessionSurface.APP,
                "test-attempt-0123456789","r".repeat(64),method,"test-auth-reference");
    }
    @Test void unknownLegacyContextCannotAuthenticate() {
        assertThatThrownBy(()->service.login(new UserLoginRequest("+84","901234567","secret")))
                .hasMessage("SESSION_CONTEXT_REAUTH_REQUIRED");
        verify(sessions,never()).insert(any(UserSessionEntity.class));
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

        var result = service.login(new UserLoginRequest("84", "901234567", "secret"), "127.0.0.1", context(AppSessionSurface.APP,"PASSWORD"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().accessToken()).isEqualTo("signed-token");
        assertThat(result.getData().user().onboardingComplete()).isTrue();
        ArgumentCaptor<UserSessionEntity> saved = ArgumentCaptor.forClass(UserSessionEntity.class);
        verify(sessions).insert(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(42L);
        assertThat(saved.getValue().getDeviceName()).isEqualTo("UVEL Phone App");
        assertThat(saved.getValue().getRefreshTokenId()).isNotBlank();
        assertThat(saved.getValue().getExpiresAt()).isNotNull();
        verify(supportActivity).interactiveLogin(42L,saved.getValue().getSessionChainId());
    }

    @Test
    void h5SessionKeepsItsWebLabel() {
        when(users.selectOne(any())).thenReturn(activeUser());

        var result = service.login(new UserLoginRequest("+84", "901234567", "secret"), "127.0.0.1", context(AppSessionSurface.H5,"PASSWORD"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<UserSessionEntity> saved = ArgumentCaptor.forClass(UserSessionEntity.class);
        verify(sessions).insert(saved.capture());
        assertThat(saved.getValue().getDeviceName()).isEqualTo("UVEL H5");
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

        var result = service.login(new UserLoginRequest("+84", "901234567", "wrong"), "127.0.0.1", context(AppSessionSurface.APP,"PASSWORD"));

        assertThat(result.getCode()).isEqualTo(401);
        assertThat(result.getMessage()).isEqualTo("USER_CREDENTIAL_INVALID");
        verify(sessions, never()).insert(any(UserSessionEntity.class));
        verify(supportActivity, never()).interactiveLogin(any(),any());
    }

    @Test
    void registrationAndDevelopmentOAuthSessionDoNotFabricateInteractiveActivity() {
        when(users.selectById(42L)).thenReturn(activeUser());
        var result=service.issueRegisteredSession(activeUser(), "127.0.0.1", context(AppSessionSurface.APP,"REGISTRATION"));
        assertThat(result.getCode()).isZero();
        verify(supportActivity,never()).interactiveLogin(any(),any());
    }

    @Test
    void activityFailurePropagatesToRollBackLoginTransaction() {
        when(users.selectOne(any())).thenReturn(activeUser());
        doThrow(new IllegalStateException("capture unavailable")).when(supportActivity).interactiveLogin(any(),any());
        assertThatThrownBy(()->service.login(new UserLoginRequest("+84","901234567","secret"), "local", context(AppSessionSurface.APP,"PASSWORD")))
                .hasMessage("capture unavailable");
    }

    @Test
    void legitimateSandboxLoginSucceedsWithoutCanonicalSupportActivity() {
        environment.setActiveProfiles("test");
        UserEntity user=activeUser();
        user.setSandbox(1);
        when(users.selectOne(any())).thenReturn(user);
        when(tokens.createUserToken(eq(42L),eq("901234567"),eq(List.of()),any(),any(Duration.class),eq(UserAuthEnvironment.SANDBOX)))
                .thenReturn("sandbox-token");

        var result=service.login(new UserLoginRequest("+84","901234567","secret"), "local", context(AppSessionSurface.APP,"PASSWORD"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().accessToken()).isEqualTo("sandbox-token");
        verify(sessions).insert(any(UserSessionEntity.class));
        verify(supportActivity,never()).interactiveLogin(any(),any());
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

        var result = service.login(new UserLoginRequest("+84", "901234567", "secret"), "local", context(AppSessionSurface.APP,"PASSWORD"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("ACCOUNT_BLOCKLISTED");
        verify(sessions,never()).insert(any(UserSessionEntity.class));
    }

    @Test
    void lockedAccountKeyIsRejectedBeforePasswordVerification() {
        UserLoginGuardRecord guard = new UserLoginGuardRecord();
        guard.setFailedCount(5);
        guard.setWindowStartedAt(LocalDateTime.now().minusMinutes(1));
        guard.setLockedUntil(LocalDateTime.now().plusMinutes(14));
        when(loginGuards.lock(any())).thenReturn(null, guard);

        var result = service.login(new UserLoginRequest("+84", "901234567", "secret"), "local", context(AppSessionSurface.APP,"PASSWORD"));

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
        var result = service.login(new UserLoginRequest("+84", "901234567", "secret"), "local", context(AppSessionSurface.APP,"PASSWORD"));

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

        var result = service.login(new UserLoginRequest("+84", "901234567", "wrong"), "local", context(AppSessionSurface.APP,"PASSWORD"));

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

        service.login(new UserLoginRequest("+84", "901234567", "wrong"), "local", context(AppSessionSurface.APP,"PASSWORD"));

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

        var result = service.login(new UserLoginRequest("+84", "901234567", "secret"), "203.0.113.9", context(AppSessionSurface.APP,"PASSWORD"));

        assertThat(result.getCode()).isEqualTo(429);
        assertThat(result.getMessage()).isEqualTo("USER_LOGIN_RATE_LIMITED");
    }

    @Test
    void twoFactorEnabledUserNeverReceivesTokenWithoutSecondFactor() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.isTwoFactorEnabled(42L)).thenReturn(true);

        var result = service.login(new UserLoginRequest("+84", "901234567", "secret"), "local", context(AppSessionSurface.APP,"PASSWORD"));

        assertThat(result.getCode()).isEqualTo(428);
        assertThat(result.getMessage()).isEqualTo("USER_TWO_FACTOR_VERIFICATION_REQUIRED");
        assertThat(result.getData().challengeNo()).startsWith("OTP-");
        verify(users).createLoginOtpChallenge(eq(42L), eq(result.getData().challengeNo()), any(), eq(5));
        verify(otpDelivery).deliver(eq("+84"), eq("901234567"), eq(result.getData().challengeNo()), any(), eq(5));
        verify(sessions,never()).insert(any(UserSessionEntity.class));
        org.mockito.Mockito.verifyNoInteractions(tokens);
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

        assertThatThrownBy(() -> service.issueRegisteredSession(user, "127.0.0.1", context(AppSessionSurface.APP,"REGISTRATION")))
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

        assertThatThrownBy(() -> service.issueRegisteredSession(user, "127.0.0.1", context(AppSessionSurface.APP,"REGISTRATION")))
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

        var result = service.login(new UserLoginRequest("+84", "901234567", "secret"), "local", context(AppSessionSurface.APP,"PASSWORD"));

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
                "+84", "901234567", "secret", "captcha-provider-ticket"), "203.0.113.9", context(AppSessionSurface.APP,"PASSWORD"));

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

        var result = service.login(new UserLoginRequest("+84", "901234567", "secret"), "local", context(AppSessionSurface.APP,"PASSWORD"));

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

        var result = service.login(new UserLoginRequest("+84", "901234567", "secret"), "local", context(AppSessionSurface.APP,"PASSWORD"));

        assertThat(result.getCode()).isEqualTo(428);
        assertThat(result.getMessage()).isEqualTo("USER_PASSWORD_RESET_REQUIRED");
        verify(sessions,never()).insert(any(UserSessionEntity.class));
        org.mockito.Mockito.verifyNoInteractions(tokens);
    }

    @Test
    void configuredAccessAndRefreshTtlsDriveIssuedTokenAndSession() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(configFacade.activeValue("auth.session.access_ttl_hours")).thenReturn(Optional.of("4"));
        when(configFacade.activeValue("auth.session.refresh_ttl_days")).thenReturn(Optional.of("30"));
        when(tokens.createUserToken(eq(42L), eq("901234567"), eq(List.of()), any(), eq(Duration.ofHours(4)), eq(UserAuthEnvironment.PRODUCTION)))
                .thenReturn("configured-token");

        LocalDateTime before = LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE);
        var result = service.login(new UserLoginRequest("+84", "901234567", "secret"), "local", context(AppSessionSurface.APP,"PASSWORD"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<UserSessionEntity> saved = ArgumentCaptor.forClass(UserSessionEntity.class);
        verify(sessions).insert(saved.capture());
        LocalDateTime after = LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE);
        assertThat(saved.getValue().getLastActiveAt()).isBetween(before, after);
        assertThat(saved.getValue().getExpiresAt()).isBetween(before.plusDays(30), after.plusDays(30));
    }

    @Test
    void forcedPasswordResetRecoversAfterAccountLockEvenWhenTheOriginalPasswordHasChanged() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.isPasswordResetRequired(42L)).thenReturn(true);
        when(users.updatePasswordHash(eq(42L), any())).thenAnswer(call -> {
            user.setPasswordHash(call.getArgument(1)); return 1;
        });
        when(users.clearPasswordResetRequired(42L)).thenAnswer(call -> {
            when(users.isPasswordResetRequired(42L)).thenReturn(false); return 1;
        });
        when(tokens.createUserToken(eq(42L), eq("901234567"), eq(List.of()), any(), any(Duration.class), eq(UserAuthEnvironment.PRODUCTION)))
                .thenReturn("original-reset-access");
        var input = new UserPasswordResetCompleteRequest("+84", "901234567", "secret", "NewSecure@2026");
        var context = context(AppSessionSurface.APP, "PASSWORD_RESET");
        var original = service.completePasswordReset(input, "local", context);
        var savedAttempt = ArgumentCaptor.forClass(ffdd.opsconsole.auth.mapper.AppLoginAttemptMapper.Attempt.class);
        var savedSession = ArgumentCaptor.forClass(UserSessionEntity.class);
        verify(attempts).insert(savedAttempt.capture());
        verify(sessions).insert(savedSession.capture());
        // Emulate the loser: its initial nonlocking lookup saw no attempt, but the winner committed before its user lock.
        when(attempts.findLocked(context.loginAttemptId())).thenReturn(savedAttempt.getValue());
        when(sessions.findRefreshForUpdate(savedAttempt.getValue().sessionId())).thenReturn(savedSession.getValue());

        var replay = service.completePasswordReset(input, "local", context);

        assertThat(replay.getCode()).isZero();
        assertThat(replay.getData().refreshToken()).isEqualTo(original.getData().refreshToken());
        assertThat(replay.getData().sessionChainId()).isEqualTo(original.getData().sessionChainId());
        assertThat(passwords.matches("secret", user.getPasswordHash())).isFalse();
        verify(users, times(1)).updatePasswordHash(eq(42L), any());
        verify(users, times(1)).clearPasswordResetRequired(42L);
        verify(sessions, times(1)).insert(any(UserSessionEntity.class));
        verify(attempts, times(1)).insert(any());
    }

    @Test
    void forcedPasswordResetChangesHashClearsFlagAndIssuesSession() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.isPasswordResetRequired(42L)).thenReturn(true);
        when(users.updatePasswordHash(eq(42L), any())).thenReturn(1);
        when(users.clearPasswordResetRequired(42L)).thenAnswer(call->{
            when(users.isPasswordResetRequired(42L)).thenReturn(false); return 1;
        });
        when(tokens.createUserToken(eq(42L), eq("901234567"), eq(List.of()), any(), any(Duration.class), eq(UserAuthEnvironment.PRODUCTION)))
                .thenReturn("reset-token");

        var result = service.completePasswordReset(new UserPasswordResetCompleteRequest(
                "+84", "901234567", "secret", "NewSecure@2026"), "local", context(AppSessionSurface.APP,"PASSWORD_RESET"));

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
                "+84", "901234567", "secret", challengeNo, "123456"), "127.0.0.1", context(AppSessionSurface.APP,"TWO_FACTOR"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().accessToken()).isEqualTo("mfa-token");
        verify(users).consumeValidLoginOtp(42L, challengeNo, "123456");
        ArgumentCaptor<UserSessionEntity> saved = ArgumentCaptor.forClass(UserSessionEntity.class);
        verify(sessions).insert(saved.capture());
        assertThat(saved.getValue().getDeviceName()).isEqualTo("UVEL Phone App");
    }

    @Test
    void twoFactorLoginRejectsRegistrationAndPasswordlessChallengePurposes() {
        var loginPurpose = service.completeTwoFactorLogin(new UserTwoFactorLoginRequest(
                "+84", "901234567", "secret", "LOGIN-0123456789abcdef0123456789abcdef", "123456"), "local", context(AppSessionSurface.APP,"TWO_FACTOR"));
        var registrationPurpose = service.completeTwoFactorLogin(new UserTwoFactorLoginRequest(
                "+84", "901234567", "secret", "REG-0123456789abcdef0123456789abcdef", "123456"), "local", context(AppSessionSurface.APP,"TWO_FACTOR"));

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
                "+84", "901234567", sent.getData().challengeNo(), "123456"), "127.0.0.1", context(AppSessionSurface.APP,"OTP"));

        assertThat(sent.getCode()).isZero();
        assertThat(sent.getData().challengeNo()).startsWith("LOGIN-");
        assertThat(verified.getCode()).isZero();
        assertThat(verified.getData().accessToken()).isEqualTo("otp-login-token");
        verify(users).consumeValidLoginOtp(eq(42L), eq(sent.getData().challengeNo()), eq("123456"));
        ArgumentCaptor<UserSessionEntity> saved = ArgumentCaptor.forClass(UserSessionEntity.class);
        verify(sessions).insert(saved.capture());
        assertThat(saved.getValue().getDeviceName()).isEqualTo("UVEL Phone App");
    }

    @Test
    void otpLoginCannotConsumeASecondFactorChallenge() {
        var result = service.completeOtpLogin(new UserOtpLoginVerifyRequest(
                "+84", "901234567", "OTP-42", "123456"), "local", context(AppSessionSurface.APP,"OTP"));

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
                "+84", "901234567", challengeNo, "999999"), "198.51.100.40", context(AppSessionSurface.APP,"OTP"));

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
                "+84", "901234567", "LOGIN-00000000000000000000000000000000", "123456"), "local", context(AppSessionSurface.APP,"OTP"));

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
                "+84", "901234567", challengeNo, "999999"), "local", context(AppSessionSurface.APP,"OTP"));

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

        var login = service.login(new UserLoginRequest("+84", "901234567", "secret"), "127.0.0.1", context(AppSessionSurface.APP,"PASSWORD"));
        ArgumentCaptor<UserSessionEntity> initialCaptor = ArgumentCaptor.forClass(UserSessionEntity.class);
        verify(sessions).insert(initialCaptor.capture());
        UserSessionEntity initial = initialCaptor.getValue();
        initial.setId(101L);

        when(sessions.findRefreshForUpdate(initial.getRefreshTokenId())).thenReturn(initial);
        when(sessions.findSession(initial.getRefreshTokenId())).thenReturn(initial);
        when(sessions.markRefreshRotated(eq(101L), any())).thenReturn(1);
        when(users.selectById(42L)).thenReturn(user);

        var refreshed = service.refresh(new UserRefreshRequest(login.getData().refreshToken()));

        assertThat(refreshed.getCode()).isZero();
        assertThat(refreshed.getData().accessToken()).isEqualTo("refreshed-token");
        verify(supportActivity,times(1)).interactiveLogin(eq(42L),any());
        assertThat(login.getData().sessionSyncKey()).isEqualTo("a".repeat(64));
        assertThat(refreshed.getData().sessionSyncKey()).isEqualTo(login.getData().sessionSyncKey());
        verify(tokens, times(2)).sessionSyncKey(initial.getSessionChainId());
        assertThat(refreshed.getData().refreshToken()).isNotBlank()
                .isNotEqualTo(login.getData().refreshToken());
        assertThat(initial.getRefreshTokenId()).hasSize(64)
                .isNotEqualTo(login.getData().refreshToken());

        ArgumentCaptor<UserSessionEntity> allSessions = ArgumentCaptor.forClass(UserSessionEntity.class);
        verify(sessions, times(2)).insert(allSessions.capture());
        UserSessionEntity rotated = allSessions.getAllValues().get(1);
        assertThat(rotated.getRefreshTokenId()).hasSize(64)
                .isNotEqualTo(refreshed.getData().refreshToken());
        assertThat(rotated.getLastActiveAt()).isBetween(
                LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE).minusMinutes(1),
                LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE).plusMinutes(1));
        assertThat(rotated.getSessionChainId()).isEqualTo(initial.getSessionChainId());
        assertThat(rotated.getDeviceName()).isEqualTo("UVEL Phone App");
        verify(sessions).markRefreshRotated(eq(101L), eq(rotated.getRefreshTokenId()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void interruptedRotationRecoversOnlyItsImmediateLiveSuccessorWithTheSameNonce(boolean cookieMode) {
        String nonce = "a".repeat(64);
        UserEntity user = activeUser();
        UserSessionEntity original = new UserSessionEntity();
        original.setId(101L);
        original.setUserId(42L);
        original.setSessionChainId("chain-42");
        original.setLastActiveAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE));
        original.setExpiresAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE).plusDays(1));
        when(users.selectById(42L)).thenReturn(user);
        when(tokens.refreshSuccessor("original-cookie", nonce)).thenReturn("successor-cookie");
        when(tokens.createUserToken(eq(42L), eq(user.getPhone()), eq(List.of()), any(),
                any(Duration.class), eq(UserAuthEnvironment.PRODUCTION))).thenReturn("access");
        when(sessions.findRefreshForUpdate(any())).thenReturn(original);
        when(sessions.findSession(any())).thenReturn(original);
        when(sessions.markRefreshRotated(eq(101L), any())).thenReturn(1);

        var first = service.refresh(new UserRefreshRequest("original-cookie"), nonce, cookieMode);
        assertThat(first.getCode()).isZero();
        assertThat(first.getData().refreshToken()).isEqualTo("successor-cookie");
        ArgumentCaptor<UserSessionEntity> inserted = ArgumentCaptor.forClass(UserSessionEntity.class);
        verify(sessions).insert(inserted.capture());
        UserSessionEntity successor = inserted.getValue();
        successor.setId(102L);
        original.setRotatedToId(successor.getRefreshTokenId());
        original.setRotationRedeemedAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE));
        original.setRevokedAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE));
        when(sessions.findRefreshForUpdate(original.getRotatedToId())).thenReturn(successor);
        when(sessions.findSession(original.getRotatedToId())).thenReturn(successor);

        var recovered = service.refresh(new UserRefreshRequest("original-cookie"), nonce, cookieMode);

        assertThat(recovered.getCode()).isZero();
        assertThat(recovered.getData().refreshToken()).isEqualTo("successor-cookie");
        verify(sessions, times(1)).markRefreshRotated(eq(101L), any());
        verify(sessions, times(1)).insert(any(UserSessionEntity.class));
        verify(sessions, never()).revokeRefreshChain(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void copiedOldCookieWithoutTheOriginalNonceStillRevokesTheChain(boolean cookieMode) {
        UserSessionEntity old = new UserSessionEntity();
        old.setUserId(42L);
        old.setSessionChainId("chain-42");
        old.setRotatedToId("successor-hash");
        old.setRotationRedeemedAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE));
        when(sessions.findRefreshForUpdate(any())).thenReturn(old);
        when(sessions.findSession(any())).thenReturn(old);

        var result = service.refresh(new UserRefreshRequest("old-cookie"), null, cookieMode);

        assertThat(result.getMessage()).isEqualTo("USER_REFRESH_TOKEN_REUSE_DETECTED");
        verify(sessions).revokeOwnedUserSession(eq(42L), any());
        verify(tokens, never()).refreshSuccessor(any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void wrongNonceForRotatedCookieStillTriggersReuseDetection(boolean cookieMode) {
        UserSessionEntity old = new UserSessionEntity();
        old.setUserId(42L);
        old.setSessionChainId("chain-42");
        old.setRotatedToId("different-successor-hash");
        old.setRotationRedeemedAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE));
        when(sessions.findRefreshForUpdate(any())).thenReturn(old);
        when(sessions.findSession(any())).thenReturn(old);
        when(tokens.refreshSuccessor("old-cookie", "b".repeat(64))).thenReturn("wrong-successor");

        var result = service.refresh(new UserRefreshRequest("old-cookie"), "b".repeat(64), cookieMode);

        assertThat(result.getMessage()).isEqualTo("USER_REFRESH_TOKEN_REUSE_DETECTED");
        verify(sessions).revokeOwnedUserSession(eq(42L), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void expiredActiveCookieRevokesItsChain(boolean cookieMode) {
        UserSessionEntity expired = new UserSessionEntity();
        expired.setUserId(42L);
        expired.setSessionChainId("chain-42");
        expired.setLastActiveAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE).minusDays(2));
        expired.setExpiresAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE).minusSeconds(1));
        when(sessions.findRefreshForUpdate(any())).thenReturn(expired);
        when(sessions.findSession(any())).thenReturn(expired);

        var result = service.refresh(new UserRefreshRequest("expired-cookie"), "a".repeat(64), cookieMode);

        assertThat(result.getMessage()).isEqualTo("USER_REFRESH_TOKEN_EXPIRED");
        verify(sessions).revokeOwnedUserSession(eq(42L), any());
        verify(sessions, never()).insert(any(UserSessionEntity.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void revokedUnrotatedCookieCannotRecoverWithANonce(boolean cookieMode) {
        UserSessionEntity revoked = new UserSessionEntity();
        revoked.setUserId(42L);
        revoked.setSessionChainId("chain-42");
        revoked.setRevokedAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE));
        when(sessions.findRefreshForUpdate(any())).thenReturn(revoked);
        when(sessions.findSession(any())).thenReturn(revoked);

        var result = service.refresh(new UserRefreshRequest("logged-out-cookie"), "a".repeat(64), cookieMode);

        assertThat(result.getMessage()).isEqualTo("USER_REFRESH_TOKEN_REUSE_DETECTED");
        verify(sessions).revokeOwnedUserSession(eq(42L), any());
        verify(sessions, never()).insert(any(UserSessionEntity.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void retryOfSupersededRotationDoesNotRevokeItsLiveDescendant(boolean cookieMode) {
        String firstNonce = "a".repeat(64);
        String secondNonce = "b".repeat(64);
        UserEntity user = activeUser();
        UserSessionEntity first = new UserSessionEntity();
        first.setId(101L);
        first.setUserId(42L);
        first.setSessionChainId("chain-42");
        first.setLastActiveAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE));
        first.setExpiresAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE).plusDays(1));
        when(users.selectById(42L)).thenReturn(user);
        when(tokens.refreshSuccessor("first-cookie", firstNonce)).thenReturn("second-cookie");
        when(tokens.refreshSuccessor("second-cookie", secondNonce)).thenReturn("third-cookie");
        when(tokens.createUserToken(eq(42L), eq(user.getPhone()), eq(List.of()), any(),
                any(Duration.class), eq(UserAuthEnvironment.PRODUCTION))).thenReturn("access");
        when(sessions.markRefreshRotated(any(), any())).thenReturn(1);
        when(sessions.findRefreshForUpdate(any())).thenReturn(first);
        when(sessions.findSession(any())).thenReturn(first);

        var firstRotation = service.refresh(new UserRefreshRequest("first-cookie"), firstNonce, cookieMode);
        assertThat(firstRotation.getCode()).isZero();
        ArgumentCaptor<UserSessionEntity> inserted = ArgumentCaptor.forClass(UserSessionEntity.class);
        verify(sessions).insert(inserted.capture());
        UserSessionEntity second = inserted.getValue();
        second.setId(102L);
        first.setRotatedToId(second.getRefreshTokenId());
        first.setRotationRedeemedAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE));
        first.setRevokedAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE));
        when(sessions.findRefreshForUpdate(any())).thenReturn(second, first, second);
        when(sessions.findSession(any())).thenReturn(second, first, second);

        var secondRotation = service.refresh(new UserRefreshRequest("second-cookie"), secondNonce, cookieMode);
        assertThat(secondRotation.getCode()).isZero();
        second.setRotatedToId("third-hash");
        second.setRotationRedeemedAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE));
        second.setRevokedAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE));

        var staleRetry = service.refresh(new UserRefreshRequest("first-cookie"), firstNonce, cookieMode);
        assertThat(staleRetry.getCode()).isEqualTo(409);
        assertThat(staleRetry.getMessage()).isEqualTo("USER_REFRESH_ROTATION_SUPERSEDED");
        verify(sessions, never()).revokeRefreshChain(any());
        verify(sessions, times(2)).insert(any(UserSessionEntity.class));
        verify(outbox, never()).publish(any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void legacyCookieClientWithoutNonceCanRotateAnActiveCookie(boolean cookieMode) {
        UserSessionEntity current = new UserSessionEntity();
        current.setId(101L);
        current.setUserId(42L);
        current.setSessionChainId("chain-42");
        current.setLastActiveAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE));
        current.setExpiresAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE).plusDays(1));
        when(sessions.findRefreshForUpdate(any())).thenReturn(current);
        when(sessions.findSession(any())).thenReturn(current);
        when(sessions.markRefreshRotated(eq(101L), any())).thenReturn(1);
        when(users.selectById(42L)).thenReturn(activeUser());
        when(tokens.createUserToken(eq(42L), any(), eq(List.of()), any(),
                any(Duration.class), eq(UserAuthEnvironment.PRODUCTION))).thenReturn("access");

        var result = service.refresh(new UserRefreshRequest("legacy-cookie"), null, cookieMode);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().refreshToken()).isNotBlank();
        verify(tokens, never()).refreshSuccessor(any(), any());
        verify(sessions).insert(any(UserSessionEntity.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void malformedRotationKeyCannotRotateAnActiveCredential(boolean cookieMode) {
        UserSessionEntity current = new UserSessionEntity();
        current.setUserId(42L);
        current.setSessionGroup("H5");
        when(sessions.findRefreshForUpdate(any())).thenReturn(current);
        when(sessions.findSession(any())).thenReturn(current);

        var result = service.refresh(new UserRefreshRequest("active-token"), "not-a-rotation-key", cookieMode);

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).isEqualTo("USER_REFRESH_ROTATION_KEY_INVALID");
        verify(sessions, never()).markRefreshRotated(any(), any());
        verify(sessions, never()).insert(any(UserSessionEntity.class));
        verify(sessions, never()).revokeRefreshChain(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"other-user", "other-chain", "revoked", "expired", "idle", "blocked", "password-reset"})
    void nativeRecoveryCannotBypassSuccessorOrAccountAuthority(String invalidState) {
        String nonce = "a".repeat(64);
        UserEntity user = activeUser();
        UserSessionEntity original = new UserSessionEntity();
        original.setId(101L);
        original.setUserId(42L);
        original.setSessionChainId("chain-42");
        original.setSessionGroup("APP");
        original.setInstallationId("installation-42");
        original.setAppSessionVersion(3L);
        original.setExecutionSupported(true);
        original.setLastActiveAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE));
        original.setExpiresAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE).plusDays(1));
        when(users.selectById(42L)).thenReturn(user);
        when(tokens.refreshSuccessor("original-token", nonce)).thenReturn("successor-token");
        when(tokens.createUserToken(eq(42L), any(), eq(List.of()), any(),
                any(Duration.class), eq(UserAuthEnvironment.PRODUCTION))).thenReturn("access");
        when(sessions.findRefreshForUpdate(any())).thenReturn(original);
        when(sessions.findSession(any())).thenReturn(original);
        when(sessions.markRefreshRotated(eq(101L), any())).thenReturn(1);
        assertThat(service.refresh(new UserRefreshRequest("original-token"), nonce, false).getCode()).isZero();
        ArgumentCaptor<UserSessionEntity> inserted = ArgumentCaptor.forClass(UserSessionEntity.class);
        verify(sessions).insert(inserted.capture());
        UserSessionEntity successor = inserted.getValue();
        original.setRotatedToId(successor.getRefreshTokenId());
        original.setRotationRedeemedAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE));
        original.setRevokedAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE));
        when(sessions.findRefreshForUpdate(original.getRotatedToId())).thenReturn(successor);
        when(sessions.findSession(original.getRotatedToId())).thenReturn(successor);
        switch (invalidState) {
            case "other-user" -> successor.setUserId(99L);
            case "other-chain" -> successor.setSessionChainId("another-chain");
            case "revoked" -> successor.setRevokedAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE));
            case "expired" -> successor.setExpiresAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE).minusSeconds(1));
            case "idle" -> successor.setLastActiveAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE).minusDays(31));
            case "blocked" -> when(blocklistVerifier.isBlocked(42L)).thenReturn(true);
            case "password-reset" -> when(users.isPasswordResetRequired(42L)).thenReturn(true);
            default -> throw new AssertionError(invalidState);
        }

        var result = service.refresh(new UserRefreshRequest("original-token"), nonce, false);

        assertThat(result.getCode()).isIn(401, 403, 409);
        if (List.of("expired", "idle").contains(invalidState)) {
            var ended = result.getData();
            assertThat(ended).isNotNull();
            assertThat(ended.sessionEndedReason()).isEqualTo("SESSION_EXPIRED");
            assertThat(new Object[]{ended.accessToken(), ended.refreshToken(), ended.tokenType(), ended.user(),
                    ended.installationId(), ended.appSessionVersion(), ended.sessionChainId(), ended.sessionGroup(),
                    ended.loginAttemptId(), ended.sessionSyncKey(), ended.challengeNo(), ended.deliveryHint(),
                    ended.registrationReceipt(), ended.executionSupported(), ended.loginAttemptStatus()}).containsOnlyNulls();
        } else {
            assertThat(result.getData()).isNull();
        }
        verify(sessions, times(1)).insert(any(UserSessionEntity.class));
        verify(tokens, times(1)).createUserToken(eq(42L), any(), eq(List.of()), any(),
                any(Duration.class), eq(UserAuthEnvironment.PRODUCTION));
        if (List.of("other-user", "other-chain", "revoked").contains(invalidState)) {
            assertThat(result.getMessage()).isEqualTo("USER_REFRESH_ROTATION_SUPERSEDED");
            verify(sessions, never()).revokeRefreshChain(any());
        } else {
            verify(sessions).revokeOwnedUserSession(eq(42L), any());
        }
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
        when(sessions.findSession(any())).thenReturn(current);
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
        when(sessions.findSession(any())).thenReturn(current);
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
        when(sessions.findSession(any())).thenReturn(used);

        var result = service.refresh(new UserRefreshRequest("already-used-refresh-secret"));

        assertThat(result.getCode()).isEqualTo(401);
        assertThat(result.getMessage()).isEqualTo("USER_REFRESH_TOKEN_REUSE_DETECTED");
        verify(sessions).revokeOwnedUserSession(eq(42L), any());
        verify(outbox).publish(eq("USER_SECURITY"), eq("42"),
                eq("auth.refresh_token_reuse_detected"), any());
    }

    @Test
    void logoutRevokesTheRefreshChainWithoutPersistingOrReturningTheRawSecret() {
        UserSessionEntity current = new UserSessionEntity();
        current.setUserId(42L);
        current.setSessionChainId("chain-42");
        when(sessions.findRefreshForUpdate(any())).thenReturn(current);
        when(sessions.findSession(any())).thenReturn(current);

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

        var result = service.login(new UserLoginRequest("+84", "901234567", "secret"), "local", context(AppSessionSurface.APP,"PASSWORD"));

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
                "+84", "901234567", "secret", challengeNo, "123456"), "local", context(AppSessionSurface.APP,"TWO_FACTOR"));

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
                "+84", "901234567", challengeNo, "123456"), "local", context(AppSessionSurface.APP,"OTP"));

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
        when(sessions.findSession(any())).thenReturn(current);
        when(users.selectById(42L)).thenReturn(user);

        var result = service.refresh(new UserRefreshRequest("cross-environment-refresh"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("USER_REFRESH_ENVIRONMENT_FORBIDDEN");
        assertThat(result.getData()).isNull();
        verify(sessions).revokeOwnedUserSession(eq(42L), any());
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
                "+84", "901234567", "secret", "NewSecure@2026"), "local", context(AppSessionSurface.APP,"PASSWORD_RESET"));

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

        var result = service.login(new UserLoginRequest("+84", "901234567", "secret"), "local", context(AppSessionSurface.APP,"PASSWORD"));

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

    @Test void providerLoginKeepsMfaCaptchaGuardOwnershipAndDoesNotIssueEarly() {
        when(users.selectOne(any())).thenReturn(activeUser());
        when(users.isTwoFactorEnabled(42L)).thenReturn(true);
        when(captchaGate.checkAndConsume(any(), any(), any(), anyInt()))
                .thenReturn(new CaptchaOtpGate.Decision(false, 428, "USER_CAPTCHA_REQUIRED"));
        var pending = service.issueProviderSession(42L, "provider-ip", context(AppSessionSurface.APP, "OAUTH"), null, null, null);
        assertThat(pending.getCode()).isEqualTo(428);
        verify(sessions, never()).insert(any(UserSessionEntity.class));
        verify(loginGuards).bindUser(any(), eq(42L));
        when(captchaGate.checkAndConsume(any(), eq("captcha-proof"), any(), anyInt()))
                .thenReturn(new CaptchaOtpGate.Decision(true, 0, "OK"));
        var challenge = service.issueProviderSession(42L, "provider-ip", context(AppSessionSurface.APP, "OAUTH"), null, null, "captcha-proof");
        assertThat(challenge.getMessage()).isEqualTo("USER_TWO_FACTOR_VERIFICATION_REQUIRED");
        verify(sessions, never()).insert(any(UserSessionEntity.class));
        when(users.consumeValidLoginOtp(eq(42L), any(), eq("123456"))).thenReturn(1);
        var signedIn = service.issueProviderSession(42L, "provider-ip", context(AppSessionSurface.APP, "OAUTH"),
                challenge.getData().challengeNo(), "123456", null);
        assertThat(signedIn.getCode()).isZero();
        verify(sessions).insert(any(UserSessionEntity.class));
        verify(supportActivity).interactiveLogin(eq(42L), any());
    }

    @Test void providerLoginNeverBypassesBlocklistOrRequiredPasswordReset() {
        when(users.selectOne(any())).thenReturn(activeUser());
        when(blocklistVerifier.isBlocked(42L)).thenReturn(true);
        assertThat(service.issueProviderSession(42L, "provider-ip", context(AppSessionSurface.H5, "OAUTH"), null, null, null).getCode()).isEqualTo(403);
        when(blocklistVerifier.isBlocked(42L)).thenReturn(false);
        when(users.isPasswordResetRequired(42L)).thenReturn(true);
        assertThat(service.issueProviderSession(42L, "provider-ip", context(AppSessionSurface.H5, "OAUTH"), null, null, null).getCode()).isEqualTo(428);
        verify(sessions, never()).insert(any(UserSessionEntity.class));
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

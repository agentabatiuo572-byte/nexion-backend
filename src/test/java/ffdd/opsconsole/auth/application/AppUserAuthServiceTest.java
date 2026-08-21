package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.util.List;
import java.time.LocalDateTime;
import java.time.LocalDate;
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
    private final AppUserAuthService service;

    AppUserAuthServiceTest() {
        properties.setTtlMinutes(120);
        when(configFacade.activeValue(any())).thenReturn(Optional.empty());
        when(otpDelivery.available()).thenReturn(true);
        when(otpDelivery.verificationCode()).thenReturn("123456");
        when(users.createLoginOtpChallenge(any(), any(), any(), any(Integer.class))).thenReturn(1);
        when(loginGuards.lockOtpSendGuard(any())).thenAnswer(ignored -> freshOtpSendGuard());
        when(loginGuards.recordOtpSend(any(), any(), any(), any(Integer.class), any(), any(Integer.class))).thenReturn(1);
        service = new AppUserAuthService(
                users, sessions, loginGuards, passwords, tokens, properties, blocklistVerifier, configFacade, otpDelivery, outbox, environment);
    }

    @Test
    void validDatabaseUserGetsSessionBackedUserToken() {
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setCountryCode("+81");
        user.setPhone("9012345678");
        user.setNickname("Nexion user");
        user.setStatus("ACTIVE");
        user.setIsDeleted(0);
        user.setSandbox(0);
        user.setPasswordHash(passwords.encode("secret"));
        when(users.selectOne(any())).thenReturn(user);
        when(tokens.createUserToken(eq(42L), eq("9012345678"), eq(List.of()), any(), any(Duration.class), eq(UserAuthEnvironment.PRODUCTION)))
                .thenReturn("signed-token");

        var result = service.login(new UserLoginRequest("81", "9012345678", "secret"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().accessToken()).isEqualTo("signed-token");
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
        user.setCountryCode("+81");
        user.setPhone("9012345678");
        user.setStatus("ACTIVE");
        user.setIsDeleted(0);
        user.setPasswordHash(passwords.encode("secret"));
        when(users.selectOne(any())).thenReturn(user);

        var result = service.login(new UserLoginRequest("+81", "9012345678", "wrong"));

        assertThat(result.getCode()).isEqualTo(401);
        assertThat(result.getMessage()).isEqualTo("USER_CREDENTIAL_INVALID");
    }

    @Test
    void validCredentialsAreRejectedWhenC2BlocklistIsActive() {
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setCountryCode("+81");
        user.setPhone("9012345678");
        user.setStatus("ACTIVE");
        user.setIsDeleted(0);
        user.setPasswordHash(passwords.encode("secret"));
        when(users.selectOne(any())).thenReturn(user);
        when(blocklistVerifier.isBlocked(42L)).thenReturn(true);

        var result = service.login(new UserLoginRequest("+81", "9012345678", "secret"));

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

        var result = service.login(new UserLoginRequest("+81", "9012345678", "secret"));

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
        user.setCountryCode("+81");
        user.setPhone("9012345678");
        user.setNickname("Trusted user");
        user.setStatus("ACTIVE");
        user.setIsDeleted(0);
        user.setPasswordHash(passwords.encode("secret"));
        when(loginGuards.lock(any())).thenReturn(null, guard);
        when(users.selectOne(any())).thenReturn(user);
        when(blocklistVerifier.isAllowlisted(42L)).thenReturn(true);
        var result = service.login(new UserLoginRequest("+81", "9012345678", "secret"));

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

        var result = service.login(new UserLoginRequest("+81", "9012345678", "wrong"));

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

        service.login(new UserLoginRequest("+81", "9012345678", "wrong"));

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

        var result = service.login(new UserLoginRequest("+81", "9012345678", "secret"), "203.0.113.9");

        assertThat(result.getCode()).isEqualTo(429);
        assertThat(result.getMessage()).isEqualTo("USER_LOGIN_RATE_LIMITED");
    }

    @Test
    void twoFactorEnabledUserNeverReceivesTokenWithoutSecondFactor() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.isTwoFactorEnabled(42L)).thenReturn(true);

        var result = service.login(new UserLoginRequest("+81", "9012345678", "secret"));

        assertThat(result.getCode()).isEqualTo(428);
        assertThat(result.getMessage()).isEqualTo("USER_TWO_FACTOR_VERIFICATION_REQUIRED");
        assertThat(result.getData().challengeNo()).startsWith("OTP-");
        verify(users).createLoginOtpChallenge(eq(42L), eq(result.getData().challengeNo()), any(), eq(5));
        verify(otpDelivery).deliver(eq("+81"), eq("9012345678"), eq(result.getData().challengeNo()), any(), eq(5));
        org.mockito.Mockito.verifyNoInteractions(sessions, tokens);
    }

    @Test
    void passwordResetRequiredUserMustCompleteResetBeforeSessionIssuance() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.isPasswordResetRequired(42L)).thenReturn(true);

        var result = service.login(new UserLoginRequest("+81", "9012345678", "secret"));

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
        when(tokens.createUserToken(eq(42L), eq("9012345678"), eq(List.of()), any(), eq(Duration.ofHours(4)), eq(UserAuthEnvironment.PRODUCTION)))
                .thenReturn("configured-token");

        LocalDateTime before = LocalDateTime.now();
        var result = service.login(new UserLoginRequest("+81", "9012345678", "secret"));

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
        when(tokens.createUserToken(eq(42L), eq("9012345678"), eq(List.of()), any(), any(Duration.class), eq(UserAuthEnvironment.PRODUCTION)))
                .thenReturn("reset-token");

        var result = service.completePasswordReset(new UserPasswordResetCompleteRequest(
                "+81", "9012345678", "secret", "NewSecure@2026"));

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
        when(tokens.createUserToken(eq(42L), eq("9012345678"), eq(List.of()), any(), any(Duration.class), eq(UserAuthEnvironment.PRODUCTION)))
                .thenReturn("mfa-token");

        var result = service.completeTwoFactorLogin(new UserTwoFactorLoginRequest(
                "+81", "9012345678", "secret", challengeNo, "123456"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().accessToken()).isEqualTo("mfa-token");
        verify(users).consumeValidLoginOtp(42L, challengeNo, "123456");
        verify(sessions).insert(any(UserSessionEntity.class));
    }

    @Test
    void twoFactorLoginRejectsRegistrationAndPasswordlessChallengePurposes() {
        var loginPurpose = service.completeTwoFactorLogin(new UserTwoFactorLoginRequest(
                "+81", "9012345678", "secret", "LOGIN-0123456789abcdef0123456789abcdef", "123456"));
        var registrationPurpose = service.completeTwoFactorLogin(new UserTwoFactorLoginRequest(
                "+81", "9012345678", "secret", "REG-0123456789abcdef0123456789abcdef", "123456"));

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
        when(tokens.createUserToken(eq(42L), eq("9012345678"), eq(List.of()), any(), any(Duration.class), eq(UserAuthEnvironment.PRODUCTION)))
                .thenReturn("otp-login-token");

        var sent = service.beginOtpLogin(new UserOtpLoginRequest("+81", "9012345678"));
        var verified = service.completeOtpLogin(new UserOtpLoginVerifyRequest(
                "+81", "9012345678", sent.getData().challengeNo(), "123456"));

        assertThat(sent.getCode()).isZero();
        assertThat(sent.getData().challengeNo()).startsWith("LOGIN-");
        assertThat(verified.getCode()).isZero();
        assertThat(verified.getData().accessToken()).isEqualTo("otp-login-token");
        verify(users).consumeValidLoginOtp(eq(42L), eq(sent.getData().challengeNo()), eq("123456"));
    }

    @Test
    void otpLoginCannotConsumeASecondFactorChallenge() {
        var result = service.completeOtpLogin(new UserOtpLoginVerifyRequest(
                "+81", "9012345678", "OTP-42", "123456"));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("USER_OTP_LOGIN_CHALLENGE_INVALID");
    }

    @Test
    void otpLoginStartDoesNotRevealWhetherAnAccountCanUseThePath() {
        var unknown = service.beginOtpLogin(new UserOtpLoginRequest("+81", "8012345678"));

        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.isTwoFactorEnabled(42L)).thenReturn(true);
        var twoFactorProtected = service.beginOtpLogin(new UserOtpLoginRequest("+81", "9012345678"));

        assertThat(unknown.getCode()).isZero();
        assertThat(unknown.getData().challengeNo()).matches("LOGIN-[a-f0-9]{32}");
        assertThat(twoFactorProtected.getCode()).isZero();
        assertThat(twoFactorProtected.getData().challengeNo()).matches("LOGIN-[a-f0-9]{32}");
        verify(otpDelivery, never()).deliver(any(), any(), eq(twoFactorProtected.getData().challengeNo()), any(), any(Integer.class));
    }

    @Test
    void otpSendThrottleSurvivesIpRotationAndStopsSmsBombing() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        UserOtpSendGuardRecord fresh = freshOtpSendGuard();
        UserOtpSendGuardRecord cooldown = freshOtpSendGuard();
        cooldown.setLastSentAt(LocalDateTime.now());
        when(loginGuards.lockOtpSendGuard(any())).thenReturn(fresh, cooldown);

        var first = service.beginOtpLogin(new UserOtpLoginRequest("+81", "9012345678"), "198.51.100.10");
        var rotatedIp = service.beginOtpLogin(new UserOtpLoginRequest("+81", "9012345678"), "203.0.113.20");

        assertThat(first.getCode()).isZero();
        assertThat(rotatedIp.getCode()).isEqualTo(429);
        assertThat(rotatedIp.getMessage()).isEqualTo("USER_OTP_SEND_RATE_LIMITED");
        verify(otpDelivery, times(1)).deliver(eq("+81"), eq("9012345678"), any(), any(), any(Integer.class));
    }

    @Test
    void issuingNewLoginOtpInvalidatesEveryOlderOpenLoginChallengeFirst() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);

        var result = service.beginOtpLogin(new UserOtpLoginRequest("+81", "9012345678"), "198.51.100.30");

        assertThat(result.getCode()).isZero();
        var order = inOrder(users);
        order.verify(users).invalidateOpenLoginOtpChallenges(42L);
        order.verify(users).createLoginOtpChallenge(eq(42L), eq(result.getData().challengeNo()), any(), any(Integer.class));
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
                "+81", "9012345678", challengeNo, "999999"), "198.51.100.40");

        assertThat(fifth.getCode()).isEqualTo(422);
        ArgumentCaptor<LocalDateTime> lockUntil = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(loginGuards, times(2)).recordFailure(any(), any(Integer.class), any(), lockUntil.capture());
        assertThat(lockUntil.getAllValues().get(1)).isAfter(LocalDateTime.now());

        UserLoginGuardRecord locked = new UserLoginGuardRecord();
        locked.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        when(loginGuards.lock(any())).thenReturn(null, locked);
        var blockedSend = service.beginOtpLogin(
                new UserOtpLoginRequest("+81", "9012345678"), "203.0.113.40");
        assertThat(blockedSend.getCode()).isEqualTo(429);
        assertThat(blockedSend.getMessage()).isEqualTo("USER_LOGIN_TEMPORARILY_LOCKED");
    }

    @Test
    void expiredOrReplayedOtpChallengeNeverCreatesASession() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(users.consumeValidLoginOtp(42L, "LOGIN-00000000000000000000000000000000", "123456")).thenReturn(0);

        var result = service.completeOtpLogin(new UserOtpLoginVerifyRequest(
                "+81", "9012345678", "LOGIN-00000000000000000000000000000000", "123456"));

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
                "+81", "9012345678", challengeNo, "999999"));

        assertThat(result.getCode()).isEqualTo(422);
        verify(users).recordInvalidLoginOtpAttempt(42L, challengeNo);
        verify(sessions, never()).insert(any(UserSessionEntity.class));
    }

    @Test
    void refreshRotatesOpaqueTokenWithoutPersistingEitherRawSecret() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(tokens.createUserToken(eq(42L), eq("9012345678"), eq(List.of()), any(), any(Duration.class), eq(UserAuthEnvironment.PRODUCTION)))
                .thenReturn("initial-token", "refreshed-token");

        var login = service.login(new UserLoginRequest("+81", "9012345678", "secret"));
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

        var result = service.login(new UserLoginRequest("+81", "9012345678", "secret"));

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
                "+81", "9012345678", "secret", challengeNo, "123456"));

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
                "+81", "9012345678", challengeNo, "123456"));

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
        environment.setActiveProfiles("dev");
        UserEntity user = activeUser();
        user.setSandbox(0);
        when(users.selectOne(any())).thenReturn(user);
        when(users.isPasswordResetRequired(42L)).thenReturn(true);

        var result = service.completePasswordReset(new UserPasswordResetCompleteRequest(
                "+81", "9012345678", "secret", "NewSecure@2026"));

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

        var result = service.login(new UserLoginRequest("+81", "9012345678", "secret"));

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("USER_AUTH_ENVIRONMENT_FORBIDDEN");
        verify(sessions, never()).insert(any(UserSessionEntity.class));
        verify(tokens, never()).createUserToken(any(), any(), any(), any(), any(), any());
    }

    private static Stream<Arguments> crossEnvironmentAccounts() {
        return Stream.of(
                Arguments.of("prod", 1),
                Arguments.of("dev", 0));
    }

    private UserEntity activeUser() {
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setCountryCode("+81");
        user.setPhone("9012345678");
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
        guard.setDayStartedAt(LocalDate.now());
        guard.setDaySendCount(0);
        return guard;
    }
}

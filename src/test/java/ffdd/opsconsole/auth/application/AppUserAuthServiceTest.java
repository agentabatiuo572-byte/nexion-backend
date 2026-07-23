package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.dto.UserLoginRequest;
import ffdd.opsconsole.auth.dto.UserPasswordResetCompleteRequest;
import ffdd.opsconsole.auth.dto.UserRefreshRequest;
import ffdd.opsconsole.auth.dto.UserTwoFactorLoginRequest;
import ffdd.opsconsole.auth.mapper.UserLoginGuardMapper;
import ffdd.opsconsole.auth.infrastructure.UserLoginGuardRecord;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.security.JwtProperties;
import ffdd.opsconsole.shared.security.JwtTokenProvider;
import ffdd.opsconsole.shared.security.UserAccountBlocklistVerifier;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.util.List;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
    private final AppUserAuthService service;

    AppUserAuthServiceTest() {
        properties.setTtlMinutes(120);
        when(configFacade.activeValue(any())).thenReturn(Optional.empty());
        when(otpDelivery.available()).thenReturn(true);
        when(users.createLoginOtpChallenge(any(), any(), any(), any(Integer.class))).thenReturn(1);
        service = new AppUserAuthService(
                users, sessions, loginGuards, passwords, tokens, properties, blocklistVerifier, configFacade, otpDelivery, outbox);
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
        user.setPasswordHash(passwords.encode("secret"));
        when(users.selectOne(any())).thenReturn(user);
        when(tokens.createToken(eq(42L), eq("USER"), eq("9012345678"), eq(List.of()), any(), any(Duration.class)))
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
    void c2AllowlistLowersLoginFrictionForValidCredentialsWithoutBypassingPassword() {
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
        when(tokens.createToken(eq(42L), eq("USER"), eq("9012345678"), eq(List.of()), any(), any(Duration.class)))
                .thenReturn("trusted-token");

        var result = service.login(new UserLoginRequest("+81", "9012345678", "secret"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().accessToken()).isEqualTo("trusted-token");
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
        when(tokens.createToken(eq(42L), eq("USER"), eq("9012345678"), eq(List.of()), any(), eq(Duration.ofHours(4))))
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
        when(tokens.createToken(eq(42L), eq("USER"), eq("9012345678"), eq(List.of()), any(), any(Duration.class)))
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
        when(users.consumeValidLoginOtp(42L, "OTP-42", "123456")).thenReturn(1);
        when(tokens.createToken(eq(42L), eq("USER"), eq("9012345678"), eq(List.of()), any(), any(Duration.class)))
                .thenReturn("mfa-token");

        var result = service.completeTwoFactorLogin(new UserTwoFactorLoginRequest(
                "+81", "9012345678", "secret", "OTP-42", "123456"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().accessToken()).isEqualTo("mfa-token");
        verify(users).consumeValidLoginOtp(42L, "OTP-42", "123456");
        verify(sessions).insert(any(UserSessionEntity.class));
    }

    @Test
    void refreshRotatesOpaqueTokenWithoutPersistingEitherRawSecret() {
        UserEntity user = activeUser();
        when(users.selectOne(any())).thenReturn(user);
        when(tokens.createToken(eq(42L), eq("USER"), eq("9012345678"), eq(List.of()), any(), any(Duration.class)))
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

    private UserEntity activeUser() {
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setCountryCode("+81");
        user.setPhone("9012345678");
        user.setNickname("Nexion user");
        user.setStatus("ACTIVE");
        user.setIsDeleted(0);
        user.setPasswordHash(passwords.encode("secret"));
        return user;
    }
}

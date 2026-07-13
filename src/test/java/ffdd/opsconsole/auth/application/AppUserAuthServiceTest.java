package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.dto.UserLoginRequest;
import ffdd.opsconsole.auth.mapper.UserLoginGuardMapper;
import ffdd.opsconsole.auth.infrastructure.UserLoginGuardRecord;
import ffdd.opsconsole.shared.security.JwtProperties;
import ffdd.opsconsole.shared.security.JwtTokenProvider;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.util.List;
import java.time.LocalDateTime;
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
    private final AppUserAuthService service;

    AppUserAuthServiceTest() {
        properties.setTtlMinutes(120);
        service = new AppUserAuthService(users, sessions, loginGuards, passwords, tokens, properties);
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
        when(tokens.createToken(eq(42L), eq("USER"), eq("9012345678"), eq(List.of()), any()))
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
}

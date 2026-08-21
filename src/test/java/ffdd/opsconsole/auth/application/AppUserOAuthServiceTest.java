package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.dto.UserOAuthExchangeRequest;
import ffdd.opsconsole.auth.dto.UserOAuthExchangeResponse;
import ffdd.opsconsole.auth.mapper.UserOAuthIdentityMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

class AppUserOAuthServiceTest {
    private static final String LOCAL_ORIGIN = "http://127.0.0.1:5173";
    private final UserOAuthIdentityMapper identityMapper = mock(UserOAuthIdentityMapper.class);
    private final UserOpsMapper userMapper = mock(UserOpsMapper.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final AppUserAuthService authService = mock(AppUserAuthService.class);
    private final EventOutboxService outboxService = mock(EventOutboxService.class);
    private final Environment environment = mock(Environment.class);
    private final OAuthSandboxChallengeService sandboxChallengeService = mock(OAuthSandboxChallengeService.class);
    private AppUserOAuthService service;

    @BeforeEach
    void setUp() {
        service = new AppUserOAuthService(identityMapper, userMapper, passwordEncoder,
                authService, outboxService, environment, sandboxChallengeService);
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});
        when(environment.getProperty("server.forward-headers-strategy")).thenReturn("none");
        when(passwordEncoder.encode(any())).thenReturn("oauth-hash");
    }

    @Test
    void explicitSandboxMockCreatesAnIsolatedRealUserAndSession() {
        doAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(301L);
            return 1;
        }).when(userMapper).insert(any(UserEntity.class));
        when(authService.issueRegisteredSession(any(UserEntity.class), eq("127.0.0.1")))
                .thenReturn(ApiResult.ok(new ffdd.opsconsole.auth.dto.UserLoginResponse(
                        "access", "Bearer", new ffdd.opsconsole.auth.dto.UserLoginResponse.UserSession(
                                301L, "+1", "900123456789012", "Sandbox Alice"), "refresh")));

        ApiResult<UserOAuthExchangeResponse> result = service.exchange(
                sandboxRequest("GOOGLE", "browser-a", "Sandbox Alice"),
                "127.0.0.1", LOCAL_ORIGIN);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().source()).isEqualTo("mock");
        assertThat(result.getData().sandbox()).isTrue();
        assertThat(result.getData().user().userId()).isEqualTo(301L);
        ArgumentCaptorSupport.assertSandboxUser(userMapper);
        verify(userMapper).ensureRegisteredUserWallet(301L, 1);
        verify(outboxService).publish(eq("USER_SECURITY"), eq("301"),
                eq("auth.oauth_sandbox_account_created"), any(Map.class));
    }

    @Test
    void telegramUsesAnExplicitDevelopmentAccountWhenTheRealProviderIsUnavailable() {
        var ids = new AtomicLong(500L);
        doAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(ids.incrementAndGet());
            return 1;
        }).when(userMapper).insert(any(UserEntity.class));
        when(authService.issueRegisteredSession(any(UserEntity.class), eq("127.0.0.1")))
                .thenAnswer(invocation -> {
                    UserEntity user = invocation.getArgument(0);
                    return ApiResult.ok(new ffdd.opsconsole.auth.dto.UserLoginResponse(
                            "access-" + user.getId(), "Bearer",
                            new ffdd.opsconsole.auth.dto.UserLoginResponse.UserSession(
                                    user.getId(), "+1", user.getPhone(), user.getNickname()), "refresh"));
                });

        ApiResult<UserOAuthExchangeResponse> result = service.exchange(
                sandboxRequest("TELEGRAM", "app-telegram-development", "Telegram Mock"),
                "127.0.0.1", LOCAL_ORIGIN);
        assertThat(result.getCode()).isZero();
        assertThat(result.getData().source()).isEqualTo("mock");
        assertThat(result.getData().sandbox()).isTrue();
    }

    @Test
    void passkeyMapsToTheFixedDevelopmentPhoneAccountWithoutCreatingAUser() {
        when(environment.getProperty("nexion.auth.development-passkey-account.country-code"))
                .thenReturn("+86");
        when(environment.getProperty("nexion.auth.development-passkey-account.phone"))
                .thenReturn("18708173775");
        UserEntity fixed = activeSandboxUser(187L, "+86", "18708173775", "Development User");
        when(userMapper.lockActiveDevelopmentUserByPhone("+86", "86", "18708173775"))
                .thenReturn(fixed);
        when(authService.issueRegisteredSession(fixed, "127.0.0.1"))
                .thenReturn(ApiResult.ok(new ffdd.opsconsole.auth.dto.UserLoginResponse(
                        "access", "Bearer", new ffdd.opsconsole.auth.dto.UserLoginResponse.UserSession(
                                187L, "+86", "18708173775", "Development User"), "refresh")));

        ApiResult<UserOAuthExchangeResponse> result = service.exchange(
                sandboxRequest("PASSKEY", "development-passkey-fixed-account", "Passkey User"),
                "127.0.0.1", LOCAL_ORIGIN);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().user().userId()).isEqualTo(187L);
        assertThat(result.getData().user().phone()).isEqualTo("18708173775");
        verify(userMapper, never()).insert(any(UserEntity.class));
        verify(userMapper, never()).ensureRegisteredUserWallet(anyLong(), anyInt());
        verify(identityMapper, never()).insertIdentity(any());
    }

    @Test
    void passkeyRejectsAnEnvironmentOverrideOfTheFixedDevelopmentPhone() {
        when(environment.getProperty("nexion.auth.development-passkey-account.country-code"))
                .thenReturn("+86");
        when(environment.getProperty("nexion.auth.development-passkey-account.phone"))
                .thenReturn("18800000000");

        ApiResult<UserOAuthExchangeResponse> result = service.exchange(
                sandboxRequest("PASSKEY", "development-passkey-fixed-account", "Passkey User"),
                "127.0.0.1", LOCAL_ORIGIN);

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("OAUTH_DEVELOPMENT_ACCOUNT_NOT_FOUND");
        verify(userMapper, never()).lockActiveDevelopmentUserByPhone(any(), any(), any());
        verify(authService, never()).issueRegisteredSession(any(), any());
    }

    @Test
    void passkeyFailsClosedWhenTheConfiguredDevelopmentAccountDoesNotExist() {
        when(environment.getProperty("nexion.auth.development-passkey-account.country-code"))
                .thenReturn("+86");
        when(environment.getProperty("nexion.auth.development-passkey-account.phone"))
                .thenReturn("18708173775");

        ApiResult<UserOAuthExchangeResponse> result = service.exchange(
                sandboxRequest("PASSKEY", "development-passkey-fixed-account", "Passkey User"),
                "127.0.0.1", LOCAL_ORIGIN);

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("OAUTH_DEVELOPMENT_ACCOUNT_NOT_FOUND");
        verify(userMapper, never()).insert(any(UserEntity.class));
        verify(identityMapper, never()).insertIdentity(any());
    }

    @Test
    void passkeyRejectsANonLocalDevelopmentCallerBeforeConsumingTheChallenge() {
        ApiResult<UserOAuthExchangeResponse> result = service.exchange(
                new UserOAuthExchangeRequest("PASSKEY", "SANDBOX_MOCK", null, "Passkey User",
                        "OAUTH-11111111111111111111111111111111"),
                "192.168.1.20", "http://192.168.1.20:5173");

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("OAUTH_DEVELOPMENT_PASSKEY_LOCAL_ONLY");
        verify(sandboxChallengeService, never()).consume(any(), any());
        verify(userMapper, never()).insert(any(UserEntity.class));
    }

    @Test
    void everySandboxProviderRejectsANonLocalExchangeBeforeConsumingTheChallenge() {
        for (String provider : java.util.List.of("GOOGLE", "APPLE", "PASSKEY", "TELEGRAM")) {
            ApiResult<UserOAuthExchangeResponse> result = service.exchange(
                    new UserOAuthExchangeRequest(provider, "SANDBOX_MOCK", null, "Local only",
                            "OAUTH-11111111111111111111111111111111"),
                    "192.168.1.20", "http://192.168.1.20:5173");

            assertThat(result.getCode()).as(provider).isEqualTo(403);
            assertThat(result.getMessage()).as(provider)
                    .isEqualTo("OAUTH_DEVELOPMENT_PASSKEY_LOCAL_ONLY");
        }
        verify(sandboxChallengeService, never()).consume(any(), any());
        verify(userMapper, never()).insert(any(UserEntity.class));
    }

    @Test
    void passkeyFailsClosedWhenForwardedHeaderRewritingIsEnabled() {
        when(environment.getProperty("server.forward-headers-strategy")).thenReturn("native");

        ApiResult<UserOAuthExchangeResponse> result = service.exchange(
                new UserOAuthExchangeRequest("PASSKEY", "SANDBOX_MOCK", null, "Passkey User",
                        "OAUTH-11111111111111111111111111111111"),
                "127.0.0.1", LOCAL_ORIGIN);

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("OAUTH_DEVELOPMENT_PASSKEY_NETWORK_POLICY_INVALID");
        verify(sandboxChallengeService, never()).consume(any(), any());
    }

    @Test
    void productionNeverFallsBackToMockWhenProviderIsMissing() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});

        ApiResult<UserOAuthExchangeResponse> result = service.exchange(
                sandboxRequest("GOOGLE", "browser-a", "Alice"),
                "127.0.0.1", LOCAL_ORIGIN);

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("OAUTH_PROVIDER_NOT_CONFIGURED");
        verify(userMapper, never()).insert(any(UserEntity.class));
        verify(userMapper, never()).ensureRegisteredUserWallet(anyLong(), anyInt());
        verify(authService, never()).issueRegisteredSession(any(), any());
        verify(outboxService, never()).publish(any(), any(), any(), any());
    }

    @Test
    void anExistingProviderSubjectReusesTheSameSandboxAccountWithoutAnotherWallet() {
        var identity = new ffdd.opsconsole.auth.infrastructure.UserOAuthIdentityEntity();
        identity.setId(11L);
        identity.setUserId(301L);
        identity.setProvider("GOOGLE");
        identity.setExternalSubject("app-google-sandbox");
        identity.setSourceEnvironment("SANDBOX");
        when(identityMapper.findForUpdate("GOOGLE", "app-google-sandbox", "SANDBOX")).thenReturn(identity);
        UserEntity existing = new UserEntity();
        existing.setId(301L);
        existing.setSandbox(1);
        existing.setIsDeleted(0);
        existing.setStatus("ACTIVE");
        existing.setCountryCode("+1");
        existing.setPhone("900123456789");
        existing.setNickname("Sandbox Alice");
        when(userMapper.selectById(301L)).thenReturn(existing);
        when(authService.issueRegisteredSession(existing, "127.0.0.1"))
                .thenReturn(ApiResult.ok(new ffdd.opsconsole.auth.dto.UserLoginResponse(
                        "access", "Bearer", new ffdd.opsconsole.auth.dto.UserLoginResponse.UserSession(
                                301L, "+1", "900123456789", "Sandbox Alice"), "refresh")));

        ApiResult<UserOAuthExchangeResponse> result = service.exchange(
                sandboxRequest("GOOGLE", "app-google-sandbox", "Changed Name"),
                "127.0.0.1", LOCAL_ORIGIN);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().user().userId()).isEqualTo(301L);
        verify(userMapper, never()).insert(any(UserEntity.class));
        verify(userMapper, never()).ensureRegisteredUserWallet(anyLong(), anyInt());
        verify(identityMapper, never()).insertIdentity(any());
    }

    @Test
    void duplicateIdentityInsertReloadsTheWinnerAndCleansTheLosersSandboxRows() {
        var winnerIdentity = new ffdd.opsconsole.auth.infrastructure.UserOAuthIdentityEntity();
        winnerIdentity.setUserId(302L);
        winnerIdentity.setProvider("GOOGLE");
        winnerIdentity.setExternalSubject("race-subject");
        winnerIdentity.setSourceEnvironment("SANDBOX");
        when(identityMapper.findForUpdate("GOOGLE", "race-subject", "SANDBOX"))
                .thenReturn(null, winnerIdentity);
        doAnswer(invocation -> {
            UserEntity loser = invocation.getArgument(0);
            loser.setId(303L);
            return 1;
        }).when(userMapper).insert(any(UserEntity.class));
        doAnswer(invocation -> { throw new DuplicateKeyException("uk_oauth_identity"); })
                .when(identityMapper).insertIdentity(any());
        when(userMapper.softDeleteSandboxOAuthWallet(303L)).thenReturn(1);
        when(userMapper.softDeleteSandboxOAuthUser(303L)).thenReturn(1);
        UserEntity winner = new UserEntity();
        winner.setId(302L);
        winner.setSandbox(1);
        winner.setIsDeleted(0);
        winner.setStatus("ACTIVE");
        winner.setCountryCode("+1");
        winner.setPhone("900222222222");
        winner.setNickname("Winner");
        when(userMapper.selectById(302L)).thenReturn(winner);
        when(authService.issueRegisteredSession(winner, "127.0.0.1"))
                .thenReturn(ApiResult.ok(new ffdd.opsconsole.auth.dto.UserLoginResponse(
                        "access", "Bearer", new ffdd.opsconsole.auth.dto.UserLoginResponse.UserSession(
                                302L, "+1", "900222222222", "Winner"), "refresh")));

        ApiResult<UserOAuthExchangeResponse> result = service.exchange(
                sandboxRequest("GOOGLE", "race-subject", "Race"),
                "127.0.0.1", LOCAL_ORIGIN);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().user().userId()).isEqualTo(302L);
        verify(userMapper).softDeleteSandboxOAuthUser(303L);
        verify(userMapper).softDeleteSandboxOAuthWallet(303L);
        verify(authService).issueRegisteredSession(winner, "127.0.0.1");
    }

    @Test
    void concurrentFirstLoginsConvergeOnOneIdentityAndOnlyTheLoserIsTombstoned() throws Exception {
        var initialLookups = new CountDownLatch(2);
        var lookupCount = new AtomicInteger();
        var identityCreated = new AtomicBoolean();
        var userSequence = new AtomicLong(400L);
        var winnerIdentity = new ffdd.opsconsole.auth.infrastructure.UserOAuthIdentityEntity();
        winnerIdentity.setProvider("APPLE");
        winnerIdentity.setExternalSubject("parallel-subject");
        winnerIdentity.setSourceEnvironment("SANDBOX");
        UserEntity winner = new UserEntity();
        winner.setSandbox(1);
        winner.setIsDeleted(0);
        winner.setStatus("ACTIVE");
        winner.setCountryCode("+1");
        winner.setPhone("900333333333");
        winner.setNickname("Parallel Winner");

        when(identityMapper.findForUpdate("APPLE", "parallel-subject", "SANDBOX"))
                .thenAnswer(invocation -> {
                    if (lookupCount.incrementAndGet() <= 2) {
                        initialLookups.countDown();
                        assertThat(initialLookups.await(5, TimeUnit.SECONDS)).isTrue();
                        return null;
                    }
                    return identityCreated.get() ? winnerIdentity : null;
                });
        doAnswer(invocation -> {
            UserEntity candidate = invocation.getArgument(0);
            candidate.setId(userSequence.incrementAndGet());
            if (winnerIdentity.getUserId() == null) {
                winnerIdentity.setUserId(candidate.getId());
                winner.setId(candidate.getId());
            }
            return 1;
        }).when(userMapper).insert(any(UserEntity.class));
        doAnswer(invocation -> {
            if (identityCreated.compareAndSet(false, true)) return 1;
            throw new DuplicateKeyException("uk_oauth_identity");
        }).when(identityMapper).insertIdentity(any());
        when(userMapper.softDeleteSandboxOAuthWallet(anyLong())).thenReturn(1);
        when(userMapper.softDeleteSandboxOAuthUser(anyLong())).thenReturn(1);
        when(userMapper.selectById(anyLong())).thenReturn(winner);
        when(authService.issueRegisteredSession(any(UserEntity.class), eq("127.0.0.1")))
                .thenAnswer(invocation -> {
                    UserEntity user = invocation.getArgument(0);
                    return ApiResult.ok(new ffdd.opsconsole.auth.dto.UserLoginResponse(
                            "access-" + user.getId(), "Bearer",
                            new ffdd.opsconsole.auth.dto.UserLoginResponse.UserSession(
                                    user.getId(), "+1", user.getPhone(), user.getNickname()), "refresh"));
                });

        var firstRequest = sandboxRequest("APPLE", "parallel-subject", "Parallel");
        var secondRequest = sandboxRequest("APPLE", "parallel-subject", "Parallel");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.exchange(
                    firstRequest,
                    "127.0.0.1", LOCAL_ORIGIN));
            var second = executor.submit(() -> service.exchange(
                    secondRequest,
                    "127.0.0.1", LOCAL_ORIGIN));
            assertThat(first.get(5, TimeUnit.SECONDS).getCode()).isZero();
            assertThat(second.get(5, TimeUnit.SECONDS).getCode()).isZero();
        } finally {
            executor.shutdownNow();
        }
        // Two insert attempts are expected, but the mapper's unique key allows
        // only the first one to persist; the second is the conflict path.
        verify(identityMapper, org.mockito.Mockito.times(2)).insertIdentity(any());
        verify(userMapper, org.mockito.Mockito.times(1)).softDeleteSandboxOAuthUser(anyLong());
        verify(userMapper, org.mockito.Mockito.times(1)).softDeleteSandboxOAuthWallet(anyLong());
    }

    @Test
    void mixedProfilesFailClosedBeforeAnyOAuthWrite() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev", "prod"});

        ApiResult<UserOAuthExchangeResponse> result = service.exchange(
                sandboxRequest("APPLE", "browser-a", "Alice"),
                "127.0.0.1", LOCAL_ORIGIN);

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("OAUTH_PROFILE_FORBIDDEN");
        verify(userMapper, never()).insert(any(UserEntity.class));
        verify(outboxService, never()).publish(any(), any(), any(), any());
    }

    @Test
    void invalidProviderAndSubjectAreRejected() {
        ApiResult<UserOAuthExchangeResponse> result = service.exchange(
                new UserOAuthExchangeRequest("FACEBOOK", "SANDBOX_MOCK", null, "Alice",
                        "OAUTH-00000000000000000000000000000000"),
                "127.0.0.1", LOCAL_ORIGIN);

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("OAUTH_REQUEST_INVALID");
        verify(userMapper, never()).insert(any(UserEntity.class));
    }

    @Test
    void clientChosenSubjectCannotOpenAnExistingSandboxIdentity() {
        String challengeNo = "OAUTH-11111111111111111111111111111111";
        when(sandboxChallengeService.consume("GOOGLE", challengeNo))
                .thenReturn(java.util.Optional.of("server-issued-subject"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> service.exchange(
                new UserOAuthExchangeRequest("GOOGLE", "SANDBOX_MOCK", "victim-subject", "Attacker",
                        challengeNo), "127.0.0.1", LOCAL_ORIGIN));
        verify(identityMapper).findForUpdate("GOOGLE", "server-issued-subject", "SANDBOX");
        verify(identityMapper, never()).findForUpdate("GOOGLE", "victim-subject", "SANDBOX");
    }

    @Test
    void missingOrReplayedSandboxChallengeFailsBeforeIdentityLookup() {
        ApiResult<UserOAuthExchangeResponse> result = service.exchange(
                new UserOAuthExchangeRequest("GOOGLE", "SANDBOX_MOCK", "victim-subject", "Attacker",
                        "OAUTH-22222222222222222222222222222222"), "127.0.0.1", LOCAL_ORIGIN);

        assertThat(result.getCode()).isEqualTo(401);
        assertThat(result.getMessage()).isEqualTo("OAUTH_SANDBOX_CHALLENGE_INVALID");
        verify(identityMapper, never()).findForUpdate(any(), any(), any());
        verify(userMapper, never()).insert(any(UserEntity.class));
    }

    private UserOAuthExchangeRequest sandboxRequest(String provider, String subject, String displayName) {
        String challengeNo = "OAUTH-" + String.format("%032x", subject.hashCode() & 0xffffffffL);
        when(sandboxChallengeService.consume(provider, challengeNo))
                .thenReturn(java.util.Optional.of(subject));
        return new UserOAuthExchangeRequest(provider, "SANDBOX_MOCK", null, displayName, challengeNo);
    }

    private static UserEntity activeSandboxUser(
            long id, String countryCode, String phone, String nickname) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setCountryCode(countryCode);
        user.setPhone(phone);
        user.setNickname(nickname);
        user.setSandbox(1);
        user.setStatus("ACTIVE");
        user.setIsDeleted(0);
        return user;
    }

    private static final class ArgumentCaptorSupport {
        static void assertSandboxUser(UserOpsMapper mapper) {
            var captor = org.mockito.ArgumentCaptor.forClass(UserEntity.class);
            verify(mapper).insert(captor.capture());
            UserEntity user = captor.getValue();
            assertThat(user.getSandbox()).isEqualTo(1);
            assertThat(user.getStatus()).isEqualTo("ACTIVE");
            assertThat(user.getPhone()).matches("900\\d{12}");
            assertThat(user.getPasswordHash()).isEqualTo("oauth-hash");
        }
    }
}

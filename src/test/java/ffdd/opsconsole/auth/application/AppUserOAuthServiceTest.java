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
    private final UserOAuthIdentityMapper identityMapper = mock(UserOAuthIdentityMapper.class);
    private final UserOpsMapper userMapper = mock(UserOpsMapper.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final AppUserAuthService authService = mock(AppUserAuthService.class);
    private final EventOutboxService outboxService = mock(EventOutboxService.class);
    private final Environment environment = mock(Environment.class);
    private AppUserOAuthService service;

    @BeforeEach
    void setUp() {
        service = new AppUserOAuthService(identityMapper, userMapper, passwordEncoder,
                authService, outboxService, environment);
        when(environment.getActiveProfiles()).thenReturn(new String[] {"acceptance"});
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
                new UserOAuthExchangeRequest("GOOGLE", "SANDBOX_MOCK", "browser-a", "Sandbox Alice"),
                "127.0.0.1");

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
    void productionNeverFallsBackToMockWhenProviderIsMissing() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"production"});

        ApiResult<UserOAuthExchangeResponse> result = service.exchange(
                new UserOAuthExchangeRequest("GOOGLE", "SANDBOX_MOCK", "browser-a", "Alice"),
                "127.0.0.1");

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
                new UserOAuthExchangeRequest("GOOGLE", "SANDBOX_MOCK", "app-google-sandbox", "Changed Name"),
                "127.0.0.1");

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
                new UserOAuthExchangeRequest("GOOGLE", "SANDBOX_MOCK", "race-subject", "Race"),
                "127.0.0.1");

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

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.exchange(
                    new UserOAuthExchangeRequest("APPLE", "SANDBOX_MOCK", "parallel-subject", "Parallel"),
                    "127.0.0.1"));
            var second = executor.submit(() -> service.exchange(
                    new UserOAuthExchangeRequest("APPLE", "SANDBOX_MOCK", "parallel-subject", "Parallel"),
                    "127.0.0.1"));
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
        when(environment.getActiveProfiles()).thenReturn(new String[] {"acceptance", "production"});

        ApiResult<UserOAuthExchangeResponse> result = service.exchange(
                new UserOAuthExchangeRequest("APPLE", "SANDBOX_MOCK", "browser-a", "Alice"),
                "127.0.0.1");

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("OAUTH_PROFILE_FORBIDDEN");
        verify(userMapper, never()).insert(any(UserEntity.class));
        verify(outboxService, never()).publish(any(), any(), any(), any());
    }

    @Test
    void invalidProviderAndSubjectAreRejected() {
        ApiResult<UserOAuthExchangeResponse> result = service.exchange(
                new UserOAuthExchangeRequest("FACEBOOK", "SANDBOX_MOCK", "bad subject", "Alice"),
                "127.0.0.1");

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("OAUTH_REQUEST_INVALID");
        verify(userMapper, never()).insert(any(UserEntity.class));
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

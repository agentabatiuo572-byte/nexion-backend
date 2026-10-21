package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.auth.captcha.CaptchaOtpGate;
import ffdd.opsconsole.auth.infrastructure.OAuthProviderFlow;
import ffdd.opsconsole.auth.infrastructure.UserOAuthIdentityEntity;
import ffdd.opsconsole.auth.mapper.OAuthProviderFlowMapper;
import ffdd.opsconsole.auth.mapper.UserLoginGuardMapper;
import ffdd.opsconsole.auth.mapper.UserOAuthIdentityMapper;
import ffdd.opsconsole.common.boundary.PhoneExecutionSessionContract;
import ffdd.opsconsole.content.application.SupportActivityService;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.CanonicalEventSchemaMySqlFixture;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.*;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** Real LINK, password/guard, session, identity and flow SQL in Spring transactions.
 * The existing fixture copies DDL only into its disposable database; no provider calls or real user rows.
 * The race asserts a WAITING account-row lock through the TEST user's existing performance_schema read grant. */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
class OAuthProviderLinkMySqlIntegrationTest {
    private static final long USER_ID = 8_310_074L;
    private static final String PASSWORD = "LinkFixture1!";
    private static final String SESSION_ID = "telegram74-original-session";
    private CanonicalEventSchemaMySqlFixture fixture;
    private TransactionTemplate tx;
    private AuthSessionMapper sessions;
    private OAuthProviderFlowMapper flows;
    private UserOAuthIdentityMapper identities;
    private OAuthProviderFlowService service;
    private AppUserAuthService auth;
    private UserSessionRevocationService revocations;
    private UsernamePasswordAuthenticationToken authentication;
    private OAuthProviderFlowService.Proof proof;

    @BeforeEach void setup() throws Exception {
        fixture = new CanonicalEventSchemaMySqlFixture("nx_user", "nx_user_security", "nx_user_session");
        // Upgrade the copied legacy DDL in the owned fixture, using the real migration on one connection.
        try (var connection = fixture.jdbc().getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("scripts/migrations/20261019_app_single_session.sql"));
        }
        tx = new TransactionTemplate(new DataSourceTransactionManager(fixture.jdbc().getDataSource()));
        sessions = fixture.mapper(AuthSessionMapper.class);
        flows = fixture.mapper(OAuthProviderFlowMapper.class); flows.createTable();
        identities = fixture.mapper(UserOAuthIdentityMapper.class); identities.createTable();
        var loginGuards = fixture.mapper(UserLoginGuardMapper.class); loginGuards.createTable();
        var users = fixture.mapper(UserOpsMapper.class);
        var passwords = new BCryptPasswordEncoder();
        var config = mock(PlatformConfigFacade.class);
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        var blocklist = mock(UserAccountBlocklistVerifier.class);
        var environment = new MockEnvironment().withProperty("oauth.enabled", "true");
        environment.setActiveProfiles("dev");
        revocations = fixture.transactional(new UserSessionRevocationService(sessions, mock(PhoneExecutionSessionContract.class)));
        auth = new AppUserAuthService(users, sessions, loginGuards, passwords, mock(JwtTokenProvider.class),
                new JwtProperties(), blocklist, config, mock(CaptchaOtpGate.class), mock(UserOtpDeliveryService.class),
                mock(EventOutboxService.class), environment, mock(SupportActivityService.class),
                mock(AppSingleSessionService.class), revocations);
        var current = fixture.transactional(new CurrentAppSessionService(sessions, config, blocklist, users, environment));
        var guard = fixture.transactional(new AppUserSecurityVerificationGuard(loginGuards, mock(AuditLogService.class)));
        service = fixture.transactional(new OAuthProviderFlowService(mock(OAuthProviderClient.class), flows, identities,
                auth, environment, guard, current, mock(AppUserRegistrationService.class)));
        fixture.jdbc().update("""
                INSERT INTO nx_user(id,country_code,phone,client_ip,password_hash,nickname,referral_code,status,sandbox,
                                    created_at,updated_at,is_deleted)
                VALUES(?,'+84','901234574','127.0.0.1',?,'oauth-link-test','LINK8310074','ACTIVE',0,NOW(),NOW(),0)
                """, USER_ID, passwords.encode(PASSWORD));
        fixture.jdbc().update("""
                INSERT INTO nx_user_session(user_id,refresh_token_id,session_chain_id,session_group,expires_at,
                                            last_active_at,created_at,updated_at,is_deleted)
                VALUES(?,?,'telegram74-original-chain','H5',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 9 HOUR),
                       DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),NOW(),0)
                """, USER_ID, SESSION_ID);
        authentication = new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of());
        authentication.setDetails(Map.of("subjectType", "USER", "sessionId", SESSION_ID));
        proof = verifiedFlow("telegram74-subject");
    }

    @AfterEach void cleanup() throws Exception {
        if (fixture != null) fixture.close();
    }

    @Test void revocationCommittedWhileLinkWaitsForAccountLockRejectsWithoutIdentityOrConsumption() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var started = new CountDownLatch(1);
        var connection = new AtomicLong();
        try {
            Future<ApiResult<Void>> pending = tx.execute(status -> {
                sessions.lockAccount(USER_ID);
                Future<ApiResult<Void>> request = executor.submit(() -> tx.execute(linkStatus -> {
                    connection.set(fixture.jdbc().queryForObject("SELECT CONNECTION_ID()", Long.class));
                    started.countDown();
                    return link(PASSWORD);
                }));
                await(started);
                awaitDatabaseLockWait(connection.get());
                assertThat(request.isDone()).isFalse();
                assertThat(revocations.revokeOwned(USER_ID, SESSION_ID)).isEqualTo(1);
                return request;
            });
            assertThatThrownBy(() -> pending.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(BizException.class).hasRootCauseMessage("SESSION_REPLACED");
            assertNoBinding();
            assertThat(fixture.jdbc().queryForObject("SELECT session_ended_reason FROM nx_user_session WHERE refresh_token_id=?",
                    String.class, SESSION_ID)).isEqualTo("SECURITY_REVOKED");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"H5", "APP"})
    void validSessionKeepsPasswordChecksAndIdempotentRecoveryButRevokedReplayFails(String group) {
        if ("APP".equals(group)) appSession(1, "telegram74-original-chain", "old-installation");
        assertThat(link("wrong-password").getMessage()).isEqualTo("OAUTH_LINK_PASSWORD_INVALID");
        assertNoBinding();
        assertThat(link(PASSWORD).getCode()).isZero();
        assertThat(link(PASSWORD).getCode()).isZero();
        assertThat(identityCount()).isEqualTo(1);
        assertThat(flows.find(proof.flowId()).getStatus()).isEqualTo("CONSUMED");
        assertThat(identities.findProviderIdentity("TELEGRAM", "telegram74-subject").getUserId()).isEqualTo(USER_ID);
        revocations.revokeOwned(USER_ID, SESSION_ID);
        assertThatThrownBy(() -> link(PASSWORD)).hasMessage("SESSION_REPLACED");
        assertThat(identityCount()).isEqualTo(1);
    }

    @Test void occupiedIdentityIsNeverMovedToAnotherAccount() {
        insertIdentity(USER_ID + 1, "telegram74-subject");
        assertThatThrownBy(() -> link(PASSWORD)).hasMessage("OAUTH_IDENTITY_ALREADY_LINKED");
        assertThat(identities.findProviderIdentity("TELEGRAM", "telegram74-subject").getUserId()).isEqualTo(USER_ID + 1);
        assertThat(identityCount()).isEqualTo(1);
        assertThat(flows.find(proof.flowId()).getStatus()).isEqualTo("VERIFIED");
    }

    @Test void existingProviderLinkRejectsAnotherSubjectWithoutConsumingProof() {
        insertIdentity(USER_ID, "already-linked-subject");
        assertThatThrownBy(() -> link(PASSWORD)).hasMessage("OAUTH_PROVIDER_ALREADY_LINKED");
        assertThat(identityCount()).isEqualTo(1);
        assertThat(flows.find(proof.flowId()).getStatus()).isEqualTo("VERIFIED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"logout", "replaced", "expired", "idle", "different-owner"})
    void endedExpiredOrForeignOriginalSessionCannotBind(String scenario) {
        switch (scenario) {
            case "logout" -> tx.executeWithoutResult(status -> {
                sessions.lockAccount(USER_ID);
                sessions.endChain("telegram74-original-chain", "CURRENT_LOGOUT");
                sessions.revokeRefreshChain("telegram74-original-chain");
            });
            case "replaced" -> appSession(2, "telegram74-replacement-chain", "new-installation");
            case "expired" -> fixture.jdbc().update("UPDATE nx_user_session SET expires_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 7 HOUR) WHERE user_id=?", USER_ID);
            case "idle" -> fixture.jdbc().update("UPDATE nx_user_session SET last_active_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 31 DAY) WHERE user_id=?", USER_ID);
            case "different-owner" -> fixture.jdbc().update("UPDATE nx_user_session SET user_id=? WHERE refresh_token_id=?", USER_ID + 1, SESSION_ID);
        }
        assertThatThrownBy(() -> link(PASSWORD)).hasMessage("SESSION_REPLACED");
        assertNoBinding();
    }

    @ParameterizedTest
    @CsvSource({
            "FROZEN,403,OAUTH_IDENTITY_NOT_AVAILABLE",
            "DELETED,403,OAUTH_IDENTITY_NOT_AVAILABLE",
            "PASSWORD_RESET,428,USER_PASSWORD_RESET_REQUIRED"
    })
    void ineligibleAccountCannotBindOrConsumeReceipt(String scenario, int code, String message) {
        switch (scenario) {
            case "FROZEN" -> fixture.jdbc().update("UPDATE nx_user SET status='FROZEN' WHERE id=?", USER_ID);
            case "DELETED" -> fixture.jdbc().update("UPDATE nx_user SET is_deleted=1 WHERE id=?", USER_ID);
            case "PASSWORD_RESET" -> fixture.jdbc().update(
                    "INSERT INTO nx_user_security(user_id,password_reset_required,is_deleted) VALUES(?,1,0)", USER_ID);
        }
        var result = link(PASSWORD);
        assertThat(result.getCode()).isEqualTo(code);
        assertThat(result.getMessage()).isEqualTo(message);
        assertNoBinding();
    }

    @ParameterizedTest
    @ValueSource(strings = {"active", "rotated", "already-revoked"})
    void discardingCapturedH5SessionNeverChangesNewerH5Chain(String scenario) {
        fixture.jdbc().update("""
                INSERT INTO nx_user_session(user_id,refresh_token_id,session_chain_id,session_group,expires_at,
                                            last_active_at,created_at,updated_at,is_deleted)
                SELECT user_id,'telegram74-newer-session','telegram74-newer-chain',session_group,expires_at,
                       last_active_at,created_at,updated_at,is_deleted
                FROM nx_user_session WHERE refresh_token_id=?
                """, SESSION_ID);
        if ("rotated".equals(scenario)) {
            fixture.jdbc().update("""
                    INSERT INTO nx_user_session(user_id,refresh_token_id,session_chain_id,session_group,expires_at,
                                                last_active_at,created_at,updated_at,is_deleted)
                    SELECT user_id,'telegram74-a-successor',session_chain_id,session_group,expires_at,
                           last_active_at,created_at,updated_at,is_deleted
                    FROM nx_user_session WHERE refresh_token_id=?
                    """, SESSION_ID);
            fixture.jdbc().update("UPDATE nx_user_session SET revoked_at=NOW(),rotation_redeemed_at=NOW(),rotated_to_id='telegram74-a-successor' WHERE refresh_token_id=?", SESSION_ID);
        } else if ("already-revoked".equals(scenario)) revocations.revokeOwned(USER_ID, SESSION_ID);
        var newerBefore = fixture.jdbc().queryForMap("SELECT * FROM nx_user_session WHERE refresh_token_id='telegram74-newer-session'");

        var result = fixture.transactional(auth).discardSession(authentication);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("revoked", !"already-revoked".equals(scenario));
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM nx_user_session WHERE session_chain_id='telegram74-original-chain' AND revoked_at IS NULL", Integer.class)).isZero();
        assertThat(fixture.jdbc().queryForMap("SELECT * FROM nx_user_session WHERE refresh_token_id='telegram74-newer-session'")).isEqualTo(newerBefore);
        var newer = tx.execute(status -> {
            sessions.lockAccount(USER_ID);
            return sessions.findAuthorizedSession("telegram74-newer-session", USER_ID, 30);
        });
        assertThat(newer).isNotNull();
    }

    private ApiResult<Void> link(String password) {
        return service.link(proof, USER_ID, password, "127.0.0.1", authentication);
    }

    private void appSession(long currentVersion, String currentChain, String currentInstallation) {
        fixture.jdbc().update("UPDATE nx_user_session SET session_group='APP',installation_id='old-installation',app_session_version=1,execution_supported=1 WHERE user_id=?", USER_ID);
        sessions.initializeAppState(USER_ID);
        assertThat(sessions.advanceAppState(USER_ID, currentVersion, currentChain, currentInstallation, true)).isEqualTo(1);
    }

    private OAuthProviderFlowService.Proof verifiedFlow(String subject) {
        var flow = new OAuthProviderFlow();
        String key = "k".repeat(43), receipt = "r".repeat(43);
        flow.setId("OIDC-" + UUID.randomUUID().toString().replace("-", ""));
        flow.setProofHash(OAuthProviderClient.hash(key)); flow.setState(OAuthProviderClient.random());
        flow.setProvider("TELEGRAM"); flow.setNonce("nonce"); flow.setVerifier("verifier");
        flow.setLinkUserId(USER_ID); flow.setClientHash(OAuthProviderClient.hash("127.0.0.1"));
        flows.insertFlow(flow);
        assertThat(flows.openBrowser(flow.getId(), OAuthProviderClient.hash("browser"))).isEqualTo(1);
        assertThat(flows.claimCallback(flow.getId())).isEqualTo(1);
        assertThat(flows.finishCallback(flow.getId(), "VERIFIED", subject, "Fixture identity", OAuthProviderClient.hash(receipt))).isEqualTo(1);
        return new OAuthProviderFlowService.Proof(flow.getId(), key, receipt);
    }

    private void insertIdentity(long owner, String subject) {
        var identity = new UserOAuthIdentityEntity();
        identity.setProvider("TELEGRAM"); identity.setExternalSubject(subject);
        identity.setUserId(owner); identity.setSourceEnvironment("PRODUCTION");
        assertThat(identities.insertIdentity(identity)).isEqualTo(1);
    }

    private int identityCount() {
        return fixture.jdbc().queryForObject("SELECT COUNT(*) FROM nx_user_oauth_identity", Integer.class);
    }

    private void assertNoBinding() {
        assertThat(identityCount()).isZero();
        assertThat(flows.find(proof.flowId()).getStatus()).isEqualTo("VERIFIED");
    }

    private void awaitDatabaseLockWait(long connectionId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            if (fixture.jdbc().queryForObject("SELECT COUNT(*) FROM performance_schema.data_locks l "
                    + "JOIN performance_schema.threads t ON t.THREAD_ID=l.THREAD_ID "
                    + "WHERE t.PROCESSLIST_ID=? AND l.OBJECT_SCHEMA=DATABASE() "
                    + "AND l.OBJECT_NAME='nx_user' AND l.LOCK_STATUS='WAITING'", Integer.class, connectionId) > 0) return;
            Thread.yield();
        } while (System.nanoTime() < deadline);
        throw new AssertionError("LINK never waited on the held account lock");
    }

    private void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
    }
}

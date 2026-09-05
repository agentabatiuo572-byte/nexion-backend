package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.auth.dto.UserPasswordResetOtpCompleteRequest;
import ffdd.opsconsole.auth.mapper.AppUserSecurityMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.CanonicalEventSchemaMySqlFixture;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.UserAccountBlocklistVerifier;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Real-MySQL regression for the A4 boundary around OTP password-reset completion.
 *
 * <p>The fixture clones only live DDL into an owned random database. OTP delivery, risk lookup,
 * and audit persistence are outside this regression; the password, OTP, session, outbox and A4
 * mapper paths are real MyBatis operations in one Spring transaction.
 */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
class PasswordResetEventSchemaMySqlIntegrationTest {
    private static final long USER_ID = 8_310_041L;
    private static final String EVENT = "auth.password_reset_completed";
    private static final String COUNTRY_CODE = "+84";
    private static final String PHONE = "901234567";
    private static final String OLD_PASSWORD = "OldPassword1!";
    private static final String NEW_PASSWORD = "NewPassword2!";
    private static final String OTP = "123456";

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private CanonicalEventSchemaMySqlFixture fixture;
    private UserOpsMapper users;
    private AppUserSecurityMapper security;
    private AuthSessionMapper sessions;
    private AppUserPasswordResetService service;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new CanonicalEventSchemaMySqlFixture(
                "nx_user", "nx_user_security", "nx_user_session", "nx_user_otp_challenge");
        users = fixture.mapper(UserOpsMapper.class);
        security = fixture.mapper(AppUserSecurityMapper.class);
        sessions = fixture.mapper(AuthSessionMapper.class);
        UserAccountBlocklistVerifier blocklist = mock(UserAccountBlocklistVerifier.class);
        when(blocklist.isBlocked(USER_ID)).thenReturn(false);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        service = fixture.transactional(new AppUserPasswordResetService(
                users, security, sessions, fixture.mapper(ffdd.opsconsole.auth.mapper.UserLoginGuardMapper.class),
                passwords, blocklist, mock(UserOtpDeliveryService.class), mock(AuditLogService.class),
                fixture.outbox(), environment, mock(ffdd.opsconsole.platform.facade.PlatformConfigFacade.class),
                mock(ffdd.opsconsole.auth.captcha.CaptchaOtpGate.class)));
        seedUserAndResetChallenge();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fixture != null) fixture.close();
    }

    @Test
    void missingSchemaRollsBackPasswordSessionAndOtpConsumption() {
        String originalHash = passwordHash();

        assertThatThrownBy(this::complete).hasMessage("A4_SCHEMA_NOT_REGISTERED");

        assertThat(passwordHash()).isEqualTo(originalHash);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT revoked_at IS NULL FROM nx_user_session WHERE user_id=?", Boolean.class, USER_ID)).isTrue();
        assertThat(fixture.jdbc().queryForObject(
                "SELECT consumed_at IS NULL FROM nx_user_otp_challenge WHERE user_id=?", Boolean.class, USER_ID)).isTrue();
        assertThat(fixture.jdbc().queryForObject(
                "SELECT attempts FROM nx_user_otp_challenge WHERE user_id=?", Integer.class, USER_ID)).isZero();
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM nx_user_security WHERE user_id=?",
                Integer.class, USER_ID)).isZero();
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM nx_event_outbox", Integer.class)).isZero();
    }

    @Test
    void migrationPublishesOnlyTheRegisteredNonSensitiveCompletionContract() throws Exception {
        fixture.migrate();

        assertThat(complete().getCode()).isZero();
        assertThat(passwords.matches(NEW_PASSWORD, passwordHash())).isTrue();
        assertThat(fixture.jdbc().queryForObject(
                "SELECT revoked_at IS NOT NULL FROM nx_user_session WHERE user_id=?", Boolean.class, USER_ID)).isTrue();

        JsonNode payload = json.readTree(fixture.jdbc().queryForObject(
                "SELECT payload FROM nx_event_outbox WHERE event_name=?", String.class, EVENT));
        assertThat(payload.path("event_name").asText()).isEqualTo(EVENT);
        assertThat(payload.path("schema_revision").asInt()).isEqualTo(316);
        assertThat(payload.path("user_id").asLong()).isEqualTo(USER_ID);
        assertThat(payload.path("revoked_session_count").asInt()).isEqualTo(1);
        Set<String> keys = new HashSet<>();
        payload.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder(
                "event_id", "event_name", "ts", "user_id", "anon_id", "session_id", "phase",
                "account_age_months", "cohort", "ref", "source", "platform", "app_version", "locale",
                "is_server_authoritative", "schema_revision", "userId", "revokedSessionCount",
                "revoked_session_count");
        for (String field : Set.of("session_id", "anon_id", "ref", "source")) {
            assertThat(payload.get(field).isNull()).as(field).isTrue();
        }
        assertThat(payload.path("platform").asText()).isEqualTo("server");
        assertThat(payload.path("app_version").asText()).isEqualTo("backend");
        assertThat(payload.path("userId").asLong()).isEqualTo(USER_ID);
        assertThat(payload.path("revokedSessionCount").asInt()).isEqualTo(1);
        assertThat(payload.toString()).doesNotContain(OLD_PASSWORD, NEW_PASSWORD, passwordHash(),
                challengeNo(), PHONE, "refresh-password-reset-test", "chain-password-reset-test", "203.0.113.1");
    }

    @Test
    void unregisteredPayloadFieldIsRejectedWithoutAnOutboxWrite() throws Exception {
        fixture.migrate();
        EventOutboxService outbox = fixture.outbox();

        assertThatThrownBy(() -> outbox.publish("USER_SECURITY", String.valueOf(USER_ID), EVENT,
                Map.of("userId", USER_ID, "revokedSessionCount", 1, "newPassword", NEW_PASSWORD)))
                .hasMessage("A4_SCHEMA_PROPERTY_NOT_REGISTERED");

        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM nx_event_outbox", Integer.class)).isZero();
    }

    @Test
    void disabledLifecycleRollsBackAndConsumedOtpCannotResetTwice() throws Exception {
        fixture.migrate();
        fixture.jdbc().update("UPDATE nx_admin_event_lifecycle SET lifecycle_state='disabled' WHERE event_name=?", EVENT);
        String originalHash = passwordHash();

        assertThatThrownBy(this::complete).hasMessage("A4_EVENT_LIFECYCLE_BLOCKED_DISABLED");
        assertThat(passwordHash()).isEqualTo(originalHash);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT consumed_at IS NULL FROM nx_user_otp_challenge WHERE user_id=?", Boolean.class, USER_ID)).isTrue();
        assertThat(fixture.jdbc().queryForObject(
                "SELECT attempts FROM nx_user_otp_challenge WHERE user_id=?", Integer.class, USER_ID)).isZero();
        assertThat(fixture.jdbc().queryForObject(
                "SELECT revoked_at IS NULL FROM nx_user_session WHERE user_id=?", Boolean.class, USER_ID)).isTrue();
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM nx_user_security WHERE user_id=?",
                Integer.class, USER_ID)).isZero();
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM nx_event_outbox", Integer.class)).isZero();

        fixture.jdbc().update("UPDATE nx_admin_event_lifecycle SET lifecycle_state='full' WHERE event_name=?", EVENT);
        assertThat(complete().getCode()).isZero();
        assertThat(complete().getCode()).isEqualTo(422);
        assertThat(fixture.jdbc().queryForObject(
                "SELECT attempts FROM nx_user_otp_challenge WHERE user_id=?", Integer.class, USER_ID)).isEqualTo(1);
        assertThat(passwords.matches(NEW_PASSWORD, passwordHash())).isTrue();
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE event_name=?",
                Integer.class, EVENT)).isEqualTo(1);
    }

    private void seedUserAndResetChallenge() {
        fixture.jdbc().update("""
                INSERT INTO nx_user(id,country_code,phone,client_ip,password_hash,nickname,referral_code,status,sandbox,
                                    created_at,updated_at,is_deleted)
                VALUES(?,?,?,?,?,?,?,?,0,NOW(),NOW(),0)
                """, USER_ID, COUNTRY_CODE, PHONE, "127.0.0.1", passwords.encode(OLD_PASSWORD),
                "password-reset-event-test", "RESET8310041", "ACTIVE");
        fixture.jdbc().update("""
                INSERT INTO nx_user_session(user_id,refresh_token_id,session_chain_id,expires_at,created_at,updated_at,is_deleted)
                VALUES(?,?,?,DATE_ADD(NOW(), INTERVAL 1 DAY),NOW(),NOW(),0)
                """, USER_ID, "refresh-password-reset-test", "chain-password-reset-test");
        assertThat(users.createLoginOtpChallenge(USER_ID, challengeNo(), OTP, 5)).isEqualTo(1);
    }

    private ffdd.opsconsole.shared.api.ApiResult<Map<String, Object>> complete() {
        return service.complete(new UserPasswordResetOtpCompleteRequest(
                COUNTRY_CODE, PHONE, challengeNo(), OTP, NEW_PASSWORD), "203.0.113.1");
    }

    private String passwordHash() {
        return fixture.jdbc().queryForObject("SELECT password_hash FROM nx_user WHERE id=?", String.class, USER_ID);
    }

    private String challengeNo() {
        return "RESET-83100418831004188310041883100418";
    }
}

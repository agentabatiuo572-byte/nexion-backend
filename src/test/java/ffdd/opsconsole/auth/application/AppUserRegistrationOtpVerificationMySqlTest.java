package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.captcha.CaptchaOtpGate;
import ffdd.opsconsole.auth.dto.UserOtpLoginVerifyRequest;
import ffdd.opsconsole.auth.mapper.AppUserRegistrationMapper;
import ffdd.opsconsole.auth.mapper.UserLoginGuardMapper;
import ffdd.opsconsole.growth.application.OpsReferralRewardService;
import ffdd.opsconsole.growth.facade.DayOneInstanceFacade;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.outbox.CanonicalEventSchemaMySqlFixture;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Real mapper and committed service transactions in the existing disposable-schema fixture. */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
class AppUserRegistrationOtpVerificationMySqlTest {
    private static final String CHALLENGE = "REG-" + "a".repeat(32);
    private static final String PHONE = "901234567";
    private CanonicalEventSchemaMySqlFixture fixture;
    private AppUserRegistrationMapper mapper;
    private AppUserRegistrationService service;
    private final MockEnvironment environment = new MockEnvironment();
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final UserOpsMapper users = mock(UserOpsMapper.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final UserOtpDeliveryService delivery = mock(UserOtpDeliveryService.class);
    private final AppUserAuthService auth = mock(AppUserAuthService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);

    @BeforeEach
    void setUp() throws Exception {
        fixture = new CanonicalEventSchemaMySqlFixture("nx_user_registration_otp");
        mapper = fixture.mapper(AppUserRegistrationMapper.class);
        environment.setActiveProfiles("dev");
        when(config.activeValue(any())).thenReturn(Optional.empty());
        service = fixture.transactional(new AppUserRegistrationService(
                mapper, users, passwords, delivery, auth, outbox,
                mock(AppUserRegistrationTransactionExecutor.class), environment,
                mock(OpsReferralRewardService.class), config, mock(UserLoginGuardMapper.class),
                mock(CaptchaOtpGate.class), mock(DayOneInstanceFacade.class)));
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            verifyNoInteractions(users, passwords, delivery, auth, outbox);
        } finally {
            if (fixture != null) fixture.close();
        }
    }

    @ParameterizedTest
    @CsvSource({"dev,+84,901234567,PRODUCTION", "dev,+86,13912345678,PRODUCTION",
            "prod,+84,901234567,PRODUCTION", "test,+86,13912345678,SANDBOX"})
    void successfulVerificationCanRepeatAndFinalRegistrationCanStillConsume(
            String profile, String country, String phone, String audience) {
        environment.setActiveProfiles(profile);
        seed(country, phone, audience);
        var request = new UserOtpLoginVerifyRequest(country, phone, CHALLENGE, "123456");

        assertThat(service.verifyOtp(request).getData()).containsEntry("status", "REGISTRATION_OTP_VERIFIED");
        assertThat(service.verifyOtp(request).getCode()).isZero();
        assertOpenWithAttempts(0);
        assertThat(mapper.consumeValidChallengeInEnvironment(CHALLENGE, country, phone, audience, "123456", 5))
                .isEqualTo(1);
        assertThat(service.verifyOtp(request).getMessage()).isEqualTo("USER_REGISTRATION_OTP_INVALID");
        assertThat(mapper.consumeValidChallengeInEnvironment(CHALLENGE, country, phone, audience, "123456", 5))
                .isZero();
    }

    @Test
    void wrongAttemptsCommitAndTheCurrentPolicyBlocksBothVerifyAndFinalConsume() {
        seed("+84", PHONE, "PRODUCTION");
        when(config.activeValue("auth.risk.otp_max_verify_attempts")).thenReturn(Optional.of("2"));

        assertInvalid(request("654321"));
        assertOpenWithAttempts(1);
        assertInvalid(request("654321"));
        assertOpenWithAttempts(2);
        assertInvalid(request("123456"));
        assertOpenWithAttempts(2);
        assertThat(mapper.consumeValidChallengeInEnvironment(CHALLENGE, "+84", PHONE, "PRODUCTION", "123456", 2))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "exhausted", "consumed", "deleted", "missing"})
    void unavailableChallengesNeverPassVerification(String state) {
        seed("+84", PHONE, "PRODUCTION");
        switch (state) {
            case "expired" -> fixture.jdbc().update("UPDATE nx_user_registration_otp SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND)");
            case "exhausted" -> fixture.jdbc().update("UPDATE nx_user_registration_otp SET attempts=5");
            case "consumed" -> fixture.jdbc().update("UPDATE nx_user_registration_otp SET consumed_at=NOW()");
            case "deleted" -> fixture.jdbc().update("UPDATE nx_user_registration_otp SET is_deleted=1");
            case "missing" -> fixture.jdbc().update("DELETE FROM nx_user_registration_otp");
            default -> throw new AssertionError(state);
        }
        assertInvalid(request("123456"));
        assertThat(fixture.jdbc().queryForObject("SELECT COALESCE(SUM(attempts),0) FROM nx_user_registration_otp", Integer.class))
                .isEqualTo("exhausted".equals(state) ? 5 : 0);
    }

    @Test
    void phoneCountryEnvironmentAndSceneMismatchDoNotSpendAnotherChallengesAttempts() {
        seed("+84", PHONE, "PRODUCTION");
        assertInvalid(new UserOtpLoginVerifyRequest("+84", "901234568", CHALLENGE, "123456"));
        assertInvalid(new UserOtpLoginVerifyRequest("+86", "13912345678", CHALLENGE, "123456"));
        assertInvalid(new UserOtpLoginVerifyRequest("+84", PHONE, "REG-" + "b".repeat(32), "123456"));
        assertInvalid(new UserOtpLoginVerifyRequest("+84", PHONE, "RESET-" + "a".repeat(32), "123456"));
        environment.setActiveProfiles("test");
        assertInvalid(request("123456"));
        assertOpenWithAttempts(0);
        environment.setActiveProfiles("dev");
        assertThat(service.verifyOtp(request("123456")).getCode()).isZero();
    }

    @Test
    void resendingInvalidatesThePreviouslyVerifiedChallenge() {
        seed("+84", PHONE, "PRODUCTION");
        assertThat(service.verifyOtp(request("123456")).getCode()).isZero();
        mapper.invalidateActiveInEnvironment("+84", PHONE, "PRODUCTION");
        assertInvalid(request("123456"));
        assertThat(mapper.consumeValidChallengeInEnvironment(CHALLENGE, "+84", PHONE, "PRODUCTION", "123456", 5))
                .isZero();
    }

    private void seed(String country, String phone, String audience) {
        assertThat(mapper.insertChallengeInEnvironment(CHALLENGE, country, phone, "127.0.0.1", audience, "123456", 5))
                .isEqualTo(1);
    }

    private UserOtpLoginVerifyRequest request(String code) {
        return new UserOtpLoginVerifyRequest("+84", PHONE, CHALLENGE, code);
    }

    private void assertInvalid(UserOtpLoginVerifyRequest request) {
        var result = service.verifyOtp(request);
        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("USER_REGISTRATION_OTP_INVALID");
    }

    private void assertOpenWithAttempts(int attempts) {
        assertThat(fixture.jdbc().queryForObject("SELECT attempts FROM nx_user_registration_otp WHERE challenge_no=?",
                Integer.class, CHALLENGE)).isEqualTo(attempts);
        assertThat(fixture.jdbc().queryForObject("SELECT consumed_at IS NULL FROM nx_user_registration_otp WHERE challenge_no=?",
                Boolean.class, CHALLENGE)).isTrue();
    }
}

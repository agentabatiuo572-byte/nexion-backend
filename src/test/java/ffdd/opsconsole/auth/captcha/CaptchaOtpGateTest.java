package ffdd.opsconsole.auth.captcha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.util.List;
import java.util.Optional;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CaptchaOtpGateTest {
    private final MockEnvironment environment = new MockEnvironment();
    private final PlatformConfigFacade configs = mock(PlatformConfigFacade.class);

    @Test
    void registrationRequiresCaptchaOnEveryFirstSendAndProductionWithoutVerifierFailsClosed() {
        environment.setActiveProfiles("prod");
        when(configs.activeValue(any())).thenReturn(Optional.empty());
        CaptchaOtpGate gate = gate();

        assertThat(gate.checkAndConsume(CaptchaScene.REGISTER, null, "127.0.0.1", 0).code())
                .isEqualTo("USER_CAPTCHA_REQUIRED");
        assertThat(gate.checkAndConsume(CaptchaScene.REGISTER, "vendor-ticket", "127.0.0.1", 0).code())
                .isEqualTo("USER_CAPTCHA_VERIFIER_UNAVAILABLE");
    }

    @Test
    void loginAndResetUseTheConfiguredSuccessfulSendThreshold() {
        environment.setActiveProfiles("prod");
        when(configs.activeValue("auth.risk.captcha_always_scenes")).thenReturn(Optional.of("register"));
        when(configs.activeValue("auth.risk.captcha_after_sends")).thenReturn(Optional.of("2"));
        CaptchaOtpGate gate = gate();

        assertThat(gate.checkAndConsume(CaptchaScene.LOGIN, null, "127.0.0.1", 1).allowed()).isTrue();
        assertThat(gate.checkAndConsume(CaptchaScene.LOGIN, null, "127.0.0.1", 2).code())
                .isEqualTo("USER_CAPTCHA_REQUIRED");
        assertThat(gate.checkAndConsume(CaptchaScene.RESET, null, "127.0.0.1", 2).code())
                .isEqualTo("USER_CAPTCHA_REQUIRED");
    }

    @Test
    void isolatedVerifierRejectsWrongSceneExpiredShapeAndReplay() {
        environment.setActiveProfiles("test");
        IsolatedTestCaptchaTicketVerifier verifier = new IsolatedTestCaptchaTicketVerifier();
        String ticket = "test-captcha:register:9999999999:0123456789abcdef";

        assertThat(verifier.verifyAndConsume(CaptchaScene.LOGIN, ticket, "127.0.0.1").passed()).isFalse();
        assertThat(verifier.verifyAndConsume(CaptchaScene.REGISTER,
                "test-captcha:register:0000000001:0123456789abcdef", "127.0.0.1").code())
                .isEqualTo("USER_CAPTCHA_TICKET_EXPIRED");
        assertThat(verifier.verifyAndConsume(CaptchaScene.REGISTER, ticket, "127.0.0.1").passed()).isTrue();
        assertThat(verifier.verifyAndConsume(CaptchaScene.REGISTER, ticket, "127.0.0.1").code())
                .isEqualTo("USER_CAPTCHA_TICKET_REPLAYED");
    }

    @Test
    void activeCaptchaOffWindowBypassesTheRealOtpGateUntilItsAbsoluteDeadline() {
        environment.setActiveProfiles("prod");
        when(configs.activeValue("auth.risk.captcha_off_window"))
                .thenReturn(Optional.of("2026-09-04T12:30:00Z"));

        assertThat(gate().checkAndConsume(CaptchaScene.REGISTER, null, "127.0.0.1", 0).allowed()).isTrue();
    }

    @Test
    void expiredOrMalformedCaptchaOffWindowFailsClosedToTheNormalCaptchaPolicy() {
        environment.setActiveProfiles("prod");
        when(configs.activeValue("auth.risk.captcha_off_window"))
                .thenReturn(Optional.of("2026-09-04T11:59:59Z"));
        assertThat(gate().checkAndConsume(CaptchaScene.REGISTER, null, "127.0.0.1", 0).code())
                .isEqualTo("USER_CAPTCHA_REQUIRED");

        when(configs.activeValue("auth.risk.captcha_off_window")).thenReturn(Optional.of("not-an-instant"));
        assertThat(gate().checkAndConsume(CaptchaScene.REGISTER, null, "127.0.0.1", 0).code())
                .isEqualTo("USER_CAPTCHA_REQUIRED");
    }

    private CaptchaOtpGate gate() {
        return new CaptchaOtpGate(environment, configs, List.of(new HoldCaptchaTicketVerifier()),
                Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC));
    }
}

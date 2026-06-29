package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.domain.UserSecurityStatusView;
import ffdd.opsconsole.user.facade.UserSecurityRiskDecision;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UserSecurityRiskFacadeAdapterTest {

    private final UserOpsRepository userRepository = mock(UserOpsRepository.class);
    private final PlatformConfigFacade configFacade = mock(PlatformConfigFacade.class);
    private final Map<String, String> config = new LinkedHashMap<>();
    private final UserSecurityRiskFacadeAdapter adapter =
            new UserSecurityRiskFacadeAdapter(userRepository, configFacade);

    @Test
    void loginDecisionReadsLatestC5C6LockThresholds() {
        when(configFacade.activeValue(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(config.get(invocation.getArgument(0, String.class))));
        when(userRepository.securityStatus(7L)).thenReturn(Optional.of(new UserSecurityStatusView(
                7L, true, 5, false, false, 5, 30)));
        config.put("auth.risk.login_lock_threshold", "6");
        config.put("auth.risk.login_long_lock_threshold", "10");

        UserSecurityRiskDecision before = adapter.evaluateLogin(7L);
        config.put("auth.risk.login_lock_threshold", "5");
        UserSecurityRiskDecision after = adapter.evaluateLogin(7L);

        assertThat(before.blocked()).isFalse();
        assertThat(after.blocked()).isTrue();
        assertThat(after.action()).isEqualTo("SHORT_LOCK");
        assertThat(after.primaryThreshold()).isEqualTo(5);
    }

    @Test
    void loginDecisionLongLocksWhenPasswordResetIsRequired() {
        when(configFacade.activeValue(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(config.get(invocation.getArgument(0, String.class))));
        when(userRepository.securityStatus(9L)).thenReturn(Optional.of(new UserSecurityStatusView(
                9L, false, 1, false, true, 5, 30)));

        UserSecurityRiskDecision decision = adapter.evaluateLogin(9L);

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.action()).isEqualTo("LONG_LOCK");
        assertThat(decision.passwordResetRequired()).isTrue();
    }

    @Test
    void registrationDecisionAppliesOtpCooldownAndCaptchaThresholds() {
        when(configFacade.activeValue(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(config.get(invocation.getArgument(0, String.class))));
        config.put("auth.risk.otp_cooldown_seconds", "60");
        config.put("auth.risk.otp_max_24h", "3");

        UserSecurityRiskDecision cooldown = adapter.evaluateRegistrationOtp("15500000000", 1, 20, false);
        UserSecurityRiskDecision captcha = adapter.evaluateRegistrationOtp("15500000000", 3, 120, false);
        UserSecurityRiskDecision passed = adapter.evaluateRegistrationOtp("15500000000", 3, 120, true);

        assertThat(cooldown.blocked()).isTrue();
        assertThat(cooldown.action()).isEqualTo("OTP_COOLDOWN");
        assertThat(captcha.blocked()).isTrue();
        assertThat(captcha.action()).isEqualTo("CAPTCHA_REQUIRED");
        assertThat(captcha.captchaRequired()).isTrue();
        assertThat(passed.blocked()).isFalse();
    }
}

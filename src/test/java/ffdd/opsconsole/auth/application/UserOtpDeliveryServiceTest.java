package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class UserOtpDeliveryServiceTest {

    private final ItnioSmsClient itnioSmsClient = mock(ItnioSmsClient.class);

    @Test
    void noProfileRuntimeNeverUsesTheChinaFixedCodeBecauseItIsProductionAudience() {
        UserOtpDeliveryService service = service(true, new MockEnvironment());

        assertThat(service.available("+86")).isFalse();
        assertThatThrownBy(() -> service.verificationCode("+86"))
                .hasMessage("USER_OTP_DELIVERY_UNAVAILABLE");
    }

    @Test
    void developmentAndTestProfilesUseTheFixedCodeOnlyForChina() {
        for (String profile : new String[] { "dev", "test" }) {
            UserOtpDeliveryService service = service(
                    true, new MockEnvironment().withProperty("spring.profiles.active", profile));

            assertThat(service.available("+86")).as(profile).isTrue();
            assertThat(service.verificationCode("+86")).as(profile).isEqualTo("123456");
            service.deliver("+86", "13800000000", "OTP-LOCAL", "123456", 5);
            verify(itnioSmsClient, never())
                    .send("+86", "13800000000", "OTP-LOCAL", "123456", 5);
        }
    }

    @Test
    void vietnamAlwaysRequiresTheRealProviderEvenWhenTheLocalFixedFlagIsEnabled() {
        UserOtpDeliveryService service = service(
                true, new MockEnvironment().withProperty("spring.profiles.active", "dev"));

        assertThat(service.available("+84")).isFalse();
        assertThatThrownBy(() -> service.verificationCode("+84"))
                .hasMessage("USER_OTP_DELIVERY_UNAVAILABLE");
    }

    @Test
    void vietnamUsesItnioWhenTheProviderIsAvailable() {
        when(itnioSmsClient.enabled()).thenReturn(true);
        when(itnioSmsClient.available()).thenReturn(true);
        UserOtpDeliveryService service = service(
                true, new MockEnvironment().withProperty("spring.profiles.active", "dev"));

        assertThat(service.available("84")).isTrue();
        String code = service.verificationCode("+84");
        assertThat(code).matches("[0-9]{6}");
        service.deliver("+84", "0901234567", "LOGIN-vietnam", code, 5);

        verify(itnioSmsClient).send("+84", "0901234567", "LOGIN-vietnam", code, 5);
    }

    @Test
    void productionMixedAndUnknownProfilesNeverEnableTheChinaFixedCode() {
        for (String profile : new String[] { "prod", "production,acceptance", "staging" }) {
            UserOtpDeliveryService service = service(
                    true, new MockEnvironment().withProperty("spring.profiles.active", profile));

            assertThat(service.available("+86")).as(profile).isFalse();
            assertThatThrownBy(() -> service.deliver(
                    "+86", "13800000000", "REG-forbidden", "123456", 5))
                    .hasMessage("USER_OTP_DELIVERY_UNAVAILABLE");
        }
    }

    @Test
    void fixedCodeRequiresTheExplicitFeatureFlag() {
        UserOtpDeliveryService service = service(false, new MockEnvironment());

        assertThat(service.available("+86")).isFalse();
        assertThatThrownBy(() -> service.verificationCode("+86"))
                .hasMessage("USER_OTP_DELIVERY_UNAVAILABLE");
    }

    @Test
    void enabledItnioProviderDoesNotOverrideTheChinaDevelopmentFallback() {
        when(itnioSmsClient.enabled()).thenReturn(true);
        when(itnioSmsClient.available()).thenReturn(true);
        UserOtpDeliveryService service = service(
                true, new MockEnvironment().withProperty("spring.profiles.active", "dev"));

        assertThat(service.available("+86")).isTrue();
        String code = service.verificationCode("+86");
        service.deliver("+86", "13800000000", "LOGIN-hidden", code, 5);

        assertThat(code).isEqualTo("123456");
        verify(itnioSmsClient, never())
                .send("+86", "13800000000", "LOGIN-hidden", code, 5);
    }

    @Test
    void enabledItnioProviderNeverTurnsChinaIntoAProductionSmsRoute() {
        when(itnioSmsClient.enabled()).thenReturn(true);
        when(itnioSmsClient.available()).thenReturn(true);
        UserOtpDeliveryService service = service(
                true, new MockEnvironment().withProperty("spring.profiles.active", "prod"));

        assertThat(service.available("+86")).isFalse();
        assertThatThrownBy(() -> service.verificationCode("+86"))
                .hasMessage("USER_OTP_DELIVERY_UNAVAILABLE");
        assertThatThrownBy(() -> service.deliver(
                "+86", "13800000000", "LOGIN-production", "654321", 5))
                .hasMessage("USER_OTP_DELIVERY_UNAVAILABLE");
        verify(itnioSmsClient, never())
                .send("+86", "13800000000", "LOGIN-production", "654321", 5);
    }

    @Test
    void enabledButIncompleteItnioConfigurationFailsClosed() {
        when(itnioSmsClient.enabled()).thenReturn(true);
        when(itnioSmsClient.available()).thenReturn(false);
        UserOtpDeliveryService service = service(
                true, new MockEnvironment().withProperty("spring.profiles.active", "dev"));

        assertThat(service.available("+84")).isFalse();
        assertThatThrownBy(() -> service.verificationCode("+84"))
                .hasMessage("USER_OTP_DELIVERY_UNAVAILABLE");
    }

    private UserOtpDeliveryService service(
            boolean localFixedCodeEnabled, MockEnvironment environment) {
        return new UserOtpDeliveryService(localFixedCodeEnabled, environment, itnioSmsClient);
    }
}

package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

class UserOtpDeliveryServiceTest {

    private final RestClient.Builder restClientBuilder = mock(RestClient.Builder.class);

    @Test
    void noProfileRuntimeNeverUsesTheFixedCodeBecauseItIsProductionAudience() {
        UserOtpDeliveryService service = service("", true, new MockEnvironment());

        assertThat(service.available()).isFalse();
        assertThatThrownBy(service::verificationCode)
                .hasMessage("USER_OTP_DELIVERY_UNAVAILABLE");
        verifyNoInteractions(restClientBuilder);
    }

    @Test
    void acceptanceAndTestProfilesMayUseTheExplicitLocalFixedCode() {
        for (String profile : new String[] { "acceptance", "test", "local-sandbox" }) {
            UserOtpDeliveryService service = service(
                    "", true, new MockEnvironment().withProperty("spring.profiles.active", profile));

            assertThat(service.available()).as(profile).isTrue();
            assertThat(service.verificationCode()).as(profile).isEqualTo("123456");
        }
    }

    @Test
    void productionMixedAndUnknownProfilesNeverEnableTheFixedCode() {
        for (String profile : new String[] { "production", "production,acceptance", "staging" }) {
            UserOtpDeliveryService service = service(
                    "", true, new MockEnvironment().withProperty("spring.profiles.active", profile));

            assertThat(service.available()).as(profile).isFalse();
            assertThatThrownBy(() -> service.deliver(
                    "+81", "81987654321", "REG-forbidden", "123456", 5))
                    .hasMessage("USER_OTP_DELIVERY_UNAVAILABLE");
        }
    }

    @Test
    void fixedCodeRequiresTheExplicitFeatureFlag() {
        UserOtpDeliveryService service = service("", false, new MockEnvironment());

        assertThat(service.available()).isFalse();
        assertThatThrownBy(service::verificationCode)
                .hasMessage("USER_OTP_DELIVERY_UNAVAILABLE");
    }

    @Test
    void configuredDeliveryUrlTakesAuthorityOverTheLocalFixedCode() {
        UserOtpDeliveryService service = service(
                "https://sms-provider.invalid/otp", true,
                new MockEnvironment().withProperty("spring.profiles.active", "local-sandbox"));

        assertThat(service.available()).isTrue();
        assertThat(service.verificationCode()).matches("\\d{6}");
    }

    private UserOtpDeliveryService service(
            String deliveryUrl, boolean localFixedCodeEnabled, MockEnvironment environment) {
        return new UserOtpDeliveryService(
                deliveryUrl, localFixedCodeEnabled, restClientBuilder, environment);
    }
}

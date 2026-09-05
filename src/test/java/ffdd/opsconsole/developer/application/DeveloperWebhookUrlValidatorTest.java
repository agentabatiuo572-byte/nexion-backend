package ffdd.opsconsole.developer.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DeveloperWebhookUrlValidatorTest {
    @Test
    void localSandboxExplicitOptInAllowsOnlyLoopbackAtValidationAndDeliveryTime() {
        MockEnvironment environment = environment("dev", true);
        URI uri = DeveloperWebhookUrlValidator.validate("http://127.0.0.1:18080/receiver", environment);

        assertThatCode(() -> DeveloperWebhookUrlValidator.rejectResolvedPrivateAddresses(uri, environment))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> DeveloperWebhookUrlValidator.validate("http://192.168.8.10:18080/receiver", environment))
                .hasMessage("DEVELOPER_WEBHOOK_HTTPS_REQUIRED");
    }

    @Test
    void productionNeverUsesTheLocalLoopbackOptIn() {
        MockEnvironment environment = environment("prod", true);

        assertThatThrownBy(() -> DeveloperWebhookUrlValidator.validate("http://127.0.0.1:18080/receiver", environment))
                .hasMessage("DEVELOPER_WEBHOOK_HTTPS_REQUIRED");
    }

    @Test
    void deliveryGuardRemainsFailClosedWithoutExplicitLocalOptIn() {
        MockEnvironment environment = environment("dev", false);
        URI uri = URI.create("http://127.0.0.1:18080/receiver");

        assertThatThrownBy(() -> DeveloperWebhookUrlValidator.rejectResolvedPrivateAddresses(uri, environment))
                .hasMessage("DEVELOPER_WEBHOOK_PRIVATE_ADDRESS_FORBIDDEN");
    }

    @Test
    void testProfileRejectsLoopbackEvenWhenExplicitlyEnabled() {
        for (String profile : new String[]{"test"}) {
            MockEnvironment environment = environment(profile, true);
            assertThatThrownBy(() -> DeveloperWebhookUrlValidator.validate("http://127.0.0.1:18080/receiver", environment))
                    .hasMessage("DEVELOPER_WEBHOOK_HTTPS_REQUIRED");
        }
    }

    @Test
    void mixedProfilesDoNotExpandTheLocalSandboxLoopbackException() {
        MockEnvironment environment = environment("dev", true);
        environment.setActiveProfiles("dev", "test");

        assertThatThrownBy(() -> DeveloperWebhookUrlValidator.validate("http://127.0.0.1:18080/receiver", environment))
                .hasMessage("DEVELOPER_WEBHOOK_HTTPS_REQUIRED");
    }

    @Test
    void resolvedIpv6UniqueLocalAddressesAreRejected() throws Exception {
        assertThat(DeveloperWebhookUrlValidator.isForbiddenAddress(InetAddress.getByName("fd00::1"))).isTrue();
        assertThat(DeveloperWebhookUrlValidator.isForbiddenAddress(InetAddress.getByName("fc00::1234"))).isTrue();
        assertThat(DeveloperWebhookUrlValidator.isForbiddenAddress(
                InetAddress.getByName("2001:4860:4860::8888"))).isFalse();
    }

    @Test
    void resolvedCarrierGradeNatAndBenchmarkAddressesAreRejected() throws Exception {
        assertThat(DeveloperWebhookUrlValidator.isForbiddenAddress(InetAddress.getByName("100.64.0.1"))).isTrue();
        assertThat(DeveloperWebhookUrlValidator.isForbiddenAddress(InetAddress.getByName("100.127.255.254"))).isTrue();
        assertThat(DeveloperWebhookUrlValidator.isForbiddenAddress(InetAddress.getByName("198.18.0.1"))).isTrue();
        assertThat(DeveloperWebhookUrlValidator.isForbiddenAddress(InetAddress.getByName("198.19.255.254"))).isTrue();
        assertThat(DeveloperWebhookUrlValidator.isForbiddenAddress(InetAddress.getByName("100.128.0.1"))).isFalse();
        assertThat(DeveloperWebhookUrlValidator.isForbiddenAddress(InetAddress.getByName("198.20.0.1"))).isFalse();
    }

    private MockEnvironment environment(String profile, boolean allowLoopback) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        environment.setProperty("nexion.developer.webhooks.allow-loopback", Boolean.toString(allowLoopback));
        return environment;
    }
}

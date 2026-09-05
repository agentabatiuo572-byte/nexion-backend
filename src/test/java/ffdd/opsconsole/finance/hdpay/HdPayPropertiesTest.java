package ffdd.opsconsole.finance.hdpay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class HdPayPropertiesTest {
    @Test
    void defaultReadTimeoutAllowsTheProvidersObservedMultiSecondCreateResponse() {
        assertThat(new HdPayProperties().getReadTimeoutMs()).isEqualTo(10_000);
    }

    @Test
    void providerModeFailsClosedUntilEveryServerSideRequirementIsPresent() {
        HdPayProperties properties = providerProperties();
        properties.setMd5Key("");
        assertThat(properties.ready()).isFalse();

        properties.setMd5Key("0123456789abcdef0123456789abcdef");
        properties.setCallbackBaseUrl("http://47.236.175.174");
        assertThat(properties.ready()).isFalse();

        properties.setCallbackBaseUrl("https://payments.example.com");
        properties.setCallbackHosts(List.of("payments.example.com"));
        assertThat(properties.ready()).isTrue();
        assertThat(properties.callbackUrl()).isEqualTo(
                "https://payments.example.com/openapi/v1/payments/hdpay/pay-in/callback");
    }

    @Test
    void paymentPageMustUseHttpsAndAnExplicitTrustedHost() {
        HdPayProperties properties = providerProperties();
        assertThat(properties.isTrustedPaymentPage("https://api.hdpayadmin.com/placeAnOrder?orderId=1"))
                .isTrue();
        assertThat(properties.isTrustedPaymentPage("http://api.hdpayadmin.com/placeAnOrder?orderId=1"))
                .isFalse();
        assertThat(properties.isTrustedPaymentPage("https://evil.example/placeAnOrder?orderId=1"))
                .isFalse();

        properties.setPaymentPageHosts(List.of("checkout.hdpay.example"));
        assertThat(properties.isTrustedPaymentPage("https://checkout.hdpay.example/pay/1"))
                .isTrue();
    }

    @Test
    void callbackMustUseAStandardPortAndNonPrivateHttpsTarget() {
        HdPayProperties properties = providerProperties();

        properties.setCallbackBaseUrl("https://127.0.0.1");
        assertThat(properties.ready()).isFalse();
        properties.setCallbackBaseUrl("https://192.168.1.20");
        assertThat(properties.ready()).isFalse();
        properties.setCallbackBaseUrl("https://payments.example.com:8443");
        assertThat(properties.ready()).isFalse();
        properties.setCallbackBaseUrl("https://47.236.175.174");
        properties.setCallbackHosts(List.of("47.236.175.174"));
        assertThat(properties.ready()).isTrue();
    }

    @Test
    void callbackRequiresAnExactApprovedHostAndTemporaryTunnelNeedsExplicitTestSeam() {
        HdPayProperties properties = providerProperties();
        properties.setCallbackHosts(List.of("callback.nexgrid.example"));
        assertThat(properties.ready()).isFalse();

        properties.setCallbackHosts(List.of("payments.example.com"));
        assertThat(properties.ready()).isTrue();

        properties.setCallbackBaseUrl("https://demo.free.pinggy.net");
        properties.setCallbackHosts(List.of("demo.free.pinggy.net"));
        assertThat(properties.ready()).isFalse();
        properties.setAllowTemporaryCallbackHostsForTests(true);
        assertThat(properties.ready()).isFalse();

        properties.captureEnvironment(activeProfiles("dev"));
        assertThat(properties.ready()).isTrue();

        properties.captureEnvironment(activeProfiles("prod", "dev"));
        assertThat(properties.ready()).isFalse();
    }

    private HdPayProperties providerProperties() {
        HdPayProperties properties = new HdPayProperties();
        properties.setMode(HdPayProperties.Mode.PROVIDER);
        properties.setBaseUrl("https://api.hdpayadmin.com/api/order");
        properties.setCallbackBaseUrl("https://payments.example.com");
        properties.setCallbackHosts(List.of("payments.example.com"));
        properties.setMerchantId("1234567890123456789");
        properties.setMd5Key("0123456789abcdef0123456789abcdef");
        properties.setPayType("BANKQR");
        properties.setCountryCode("VN");
        return properties;
    }

    private MockEnvironment activeProfiles(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }
}

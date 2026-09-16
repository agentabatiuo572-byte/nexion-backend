package ffdd.opsconsole;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PublicTestDeploymentSafetyTest {
    private final RuntimeProfileEnvironmentPostProcessor processor = new RuntimeProfileEnvironmentPostProcessor();

    private MockEnvironment safe() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.profiles.active", "dev")
                .withProperty("nexion.deployment.public-test", "true");
        PublicTestDeploymentSafety.policy().forEach(env::setProperty);
        return env;
    }

    @Test
    void acceptsOnlyTheCompletePublicTestPolicy() {
        assertThatCode(() -> processor.validate(safe())).doesNotThrowAnyException();
    }

    @Test
    void rejectsEveryMissingOrChangedSafetyPinWithoutPrintingItsValue() {
        for (String key : PublicTestDeploymentSafety.policy().keySet()) {
            MockEnvironment missing = new MockEnvironment();
            missing.setProperty("spring.profiles.active", "dev");
            missing.setProperty("nexion.deployment.public-test", "true");
            PublicTestDeploymentSafety.policy().forEach((k, v) -> { if (!key.equals(k)) missing.setProperty(k, v); });
            assertThatThrownBy(() -> processor.validate(missing)).hasMessageContaining("PUBLIC_TEST_POLICY_REJECTED");
            assertThatThrownBy(() -> processor.validate(safe().withProperty(key, "unsafe-secret-value")))
                    .hasMessageContaining("PUBLIC_TEST_POLICY_REJECTED").hasMessageNotContaining("unsafe-secret-value");
        }
    }

    @Test
    void neverMakesFixedCodesAvailableInProduction() {
        assertThatThrownBy(() -> processor.validate(safe().withProperty("spring.profiles.active", "prod")))
                .hasMessageContaining("PUBLIC_TEST_REQUIRES_DEV");
    }

    @Test
    void hdPayRequiresExplicitPayInApprovalAndAnExactProviderMode() {
        String mode = "nexion.finance.hdpay.mode";
        String approved = "nexion.deployment.hdpay-pay-in-approved";
        assertThatThrownBy(() -> processor.validate(safe().withProperty(mode, "PROVIDER")))
                .hasMessageContaining("PUBLIC_TEST_POLICY_REJECTED: " + mode);
        for (String value : new String[]{"false", "TRUE", "1", "arbitrary"}) {
            assertThatThrownBy(() -> processor.validate(safe().withProperty(mode, "PROVIDER")
                    .withProperty(approved, value))).hasMessageContaining("PUBLIC_TEST_POLICY_REJECTED");
        }
        assertThatCode(() -> processor.validate(safe().withProperty(mode, "PROVIDER")
                .withProperty(approved, "true"))).doesNotThrowAnyException();
        assertThatCode(() -> processor.validate(safe().withProperty(approved, "true")))
                .doesNotThrowAnyException(); // disabling remains safe during rollback.
        for (String value : new String[]{"provider", "MOCK", "DEV", "arbitrary"}) {
            assertThatThrownBy(() -> processor.validate(safe().withProperty(mode, value)
                    .withProperty(approved, "true"))).hasMessageContaining("PUBLIC_TEST_POLICY_REJECTED");
        }
    }

    @Test
    void hdPayApprovalNeverRelaxesAnotherPublicTestSafetyPin() {
        for (String key : PublicTestDeploymentSafety.policy().keySet()) {
            if ("nexion.finance.hdpay.mode".equals(key)) continue;
            MockEnvironment env = safe().withProperty("nexion.finance.hdpay.mode", "PROVIDER")
                    .withProperty("nexion.deployment.hdpay-pay-in-approved", "true")
                    .withProperty(key, "unsafe-secret-value");
            assertThatThrownBy(() -> processor.validate(env))
                    .hasMessageContaining("PUBLIC_TEST_POLICY_REJECTED: " + key)
                    .hasMessageNotContaining("unsafe-secret-value");
        }
        assertThatThrownBy(() -> processor.validate(safe()
                .withProperty("nexion.finance.hdpay.mode", "PROVIDER")
                .withProperty("nexion.deployment.hdpay-pay-in-approved", "true")
                .withProperty("spring.profiles.active", "prod")))
                .hasMessageContaining("PUBLIC_TEST_REQUIRES_DEV");
    }

    @Test
    void payInApprovalCannotEnableTheNewBankPayoutRail() {
        var env = safe().withProperty("nexion.finance.hdpay.mode", "PROVIDER")
                .withProperty("nexion.deployment.hdpay-pay-in-approved", "true")
                .withProperty("nexion.finance.hdpay-payout.enabled", "true");
        assertThatCode(() -> processor.validate(env)).doesNotThrowAnyException();
        var transport = org.mockito.Mockito.mock(ffdd.opsconsole.finance.hdpay.HdPayProperties.class);
        org.mockito.Mockito.when(transport.ready()).thenReturn(true);
        var payout = new ffdd.opsconsole.finance.hdpay.HdPayPayoutProperties();
        org.springframework.test.util.ReflectionTestUtils.setField(payout, "environment", env);
        org.assertj.core.api.Assertions.assertThat(payout.ready(transport)).isFalse();
    }

    @Test
    void doesNotChangeLocalDevOrProductionWhenPublicTestIsNotSelected() {
        for (String profile : new String[]{"dev", "prod"}) {
            assertThatCode(() -> processor.validate(new MockEnvironment().withProperty("spring.profiles.active", profile)))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void publicTestRequiresAnExplicitDatabaseEnvironmentBundle() {
        assertThatThrownBy(() -> new DatabaseEnvironmentPostProcessor().applyAuthoritativePropertySource(safe(), Map.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new DatabaseEnvironmentPostProcessor().applyAuthoritativePropertySource(safe(), Map.of(
                "NEXION_DB_URL", "jdbc:mysql://127.0.0.1/isolated_unit_test",
                "NEXION_DB_USERNAME", "unit_test", "NEXION_DB_PASSWORD", "unit-test-not-a-secret")))
                .doesNotThrowAnyException();
    }
}

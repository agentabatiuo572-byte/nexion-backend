package ffdd.opsconsole;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.springframework.core.env.ConfigurableEnvironment;

/** Public TEST is intentionally narrower than a developer's local dev environment. */
final class PublicTestDeploymentSafety {
    private static final String HDPAY_MODE = "nexion.finance.hdpay.mode";
    private static final String HDPAY_PAY_IN_APPROVED = "nexion.deployment.hdpay-pay-in-approved";

    private PublicTestDeploymentSafety() { }

    static Map<String, String> policy() {
        Properties properties = new Properties();
        try (var stream = PublicTestDeploymentSafety.class.getResourceAsStream("/public-test-policy.properties")) {
            if (stream == null) throw new IllegalStateException("PUBLIC_TEST_POLICY_MISSING");
            properties.load(stream);
        } catch (IOException ex) {
            throw new IllegalStateException("PUBLIC_TEST_POLICY_UNREADABLE", ex);
        }
        Map<String, String> result = new TreeMap<>();
        properties.forEach((key, value) -> result.put(key.toString(), value.toString()));
        if (result.size() < 20) throw new IllegalStateException("PUBLIC_TEST_POLICY_INCOMPLETE");
        return Map.copyOf(result);
    }

    static void validate(ConfigurableEnvironment environment) {
        if (!environment.getProperty("nexion.deployment.public-test", Boolean.class, false)) return;
        if (!RuntimeProfile.DEV.equals(RuntimeProfile.requireSingle(environment))) {
            throw new IllegalStateException("PUBLIC_TEST_REQUIRES_DEV");
        }
        // New, opt-in payout rail is never covered by the existing pay-in approval.
        // Keep the external policy artifact/hash unchanged for compatible, reversible TEST upgrades.
        if (!"false".equals(environment.getProperty("nexion.finance.hdpay-payout.enabled", "false")))
            throw new IllegalStateException("PUBLIC_TEST_POLICY_REJECTED: nexion.finance.hdpay-payout.enabled");
        policy().forEach((key, expected) -> {
            String actual = environment.getProperty(key);
            // Root-owned deployment policy may explicitly authorize real HDPay
            // pay-in on the isolated test host. All other safety pins remain exact.
            // Configuration readiness, callback signatures and D1's channel switch
            // are still enforced by the existing HDPay/VietQR services.
            boolean approvedHdPay = HDPAY_MODE.equals(key)
                    && "DISABLED".equals(expected)
                    && "PROVIDER".equals(actual)
                    && "true".equals(environment.getProperty(HDPAY_PAY_IN_APPROVED));
            if (!expected.equals(actual) && !approvedHdPay) {
                // Never log user-controlled values or environment contents.
                throw new IllegalStateException("PUBLIC_TEST_POLICY_REJECTED: " + key);
            }
        });
    }
}

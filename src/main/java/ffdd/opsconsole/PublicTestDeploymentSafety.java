package ffdd.opsconsole;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.springframework.core.env.ConfigurableEnvironment;

/** Public TEST is intentionally narrower than a developer's local dev environment. */
final class PublicTestDeploymentSafety {
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
        policy().forEach((key, expected) -> {
            if (!expected.equals(environment.getProperty(key))) {
                // Never log user-controlled values or environment contents.
                throw new IllegalStateException("PUBLIC_TEST_POLICY_REJECTED: " + key);
            }
        });
    }
}

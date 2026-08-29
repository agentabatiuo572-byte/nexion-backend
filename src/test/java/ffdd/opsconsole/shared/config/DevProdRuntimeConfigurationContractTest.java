package ffdd.opsconsole.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DevProdRuntimeConfigurationContractTest {
    private static final Path RESOURCES = Path.of("src", "main", "resources");

    @Test
    void runtimeShipsOnlyDevAndProdProfileFiles() {
        assertThat(RESOURCES.resolve("application-dev.yml")).exists();
        assertThat(RESOURCES.resolve("application-prod.yml")).exists();
        assertThat(RESOURCES.resolve("application-acceptance.yml")).doesNotExist();
        assertThat(RESOURCES.resolve("application-local-sandbox.yml")).doesNotExist();
    }

    @Test
    void devProfileUsesCanonicalDevelopmentDataAndNoSandboxRuntime() throws IOException {
        String dev = Files.readString(RESOURCES.resolve("application-dev.yml"));

        assertThat(dev).contains("local-fixed-code-enabled: true");
        assertThat(dev).contains("allow-loopback-without-country: true");
        assertThat(dev).contains("funds-sandbox:").contains("mode: DISABLED");
        assertThat(dev).contains("payment-method-provider:").contains("mode: DISABLED");
        assertThat(dev).contains("source-environment: PRODUCTION");
        assertThat(dev).doesNotContain(
                "LOCAL_SANDBOX",
                "mode: SANDBOX",
                "source-environment: SANDBOX",
                "NEXION_ACCEPTANCE_RUN_ID",
                "acceptance-run-id:",
                "mode: ENABLED");
    }

    @Test
    void prodProfileFailsClosedWithoutLocalSimulation() throws IOException {
        String prod = Files.readString(RESOURCES.resolve("application-prod.yml"));

        assertThat(prod).contains("url: ${NEXION_DB_URL}");
        assertThat(prod).contains("host: ${NEXION_REDIS_HOST}");
        assertThat(prod).contains("endpoint: ${NEXION_MINIO_ENDPOINT}");
        assertThat(prod).contains("trusted-proxy-addresses: ${NEXION_GEO_TRUSTED_PROXIES}");
        assertThat(prod).contains("local-fixed-code-enabled: false");
        assertThat(prod).contains("funds-sandbox:").contains("mode: DISABLED");
        assertThat(prod).contains("allow-loopback-without-country: false");
        assertThat(prod).doesNotContain("LOCAL_SANDBOX");
        assertThat(prod).doesNotContain("127.0.0.1", "localhost");
    }

    @Test
    void javaProfileAuthorityUsesDevAndProdNames() throws IOException {
        String userEnvironment = Files.readString(Path.of("src", "main", "java", "ffdd", "opsconsole",
                "shared", "security", "UserAuthEnvironment.java"));
        String fundsGuard = Files.readString(Path.of("src", "main", "java", "ffdd", "opsconsole",
                "finance", "application", "FundsSandboxProfileGuard.java"));

        assertThat(userEnvironment).contains("\"dev\"").contains("\"prod\"");
        assertThat(userEnvironment).doesNotContain("nexion.runtime.environment");
        assertThat(userEnvironment).doesNotContain("\"acceptance\"", "\"local-sandbox\"", "\"production\"", "\"default\"");
        assertThat(fundsGuard).contains("\"dev\"").contains("\"prod\"");
        assertThat(fundsGuard).doesNotContain("\"acceptance\"", "\"local-sandbox\"", "\"production\"", "\"default\"");
    }

    @Test
    void runtimeProfilesHaveNoSecondEnvironmentAuthority() throws IOException {
        String base = Files.readString(RESOURCES.resolve("application.yml"));
        String dev = Files.readString(RESOURCES.resolve("application-dev.yml"));
        String prod = Files.readString(RESOURCES.resolve("application-prod.yml"));
        String factories = Files.readString(RESOURCES.resolve(Path.of("META-INF", "spring.factories")));

        assertThat(base).doesNotContain("SPRING_PROFILES_ACTIVE:dev");
        assertThat(dev).doesNotContain("runtime:\n    environment:");
        assertThat(prod).doesNotContain("runtime:\n    environment:");
        assertThat(factories).contains("RuntimeProfileEnvironmentPostProcessor");
    }
}

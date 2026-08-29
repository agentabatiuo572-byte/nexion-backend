package ffdd.opsconsole.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class LocalSandboxConfigurationTest {
    @Test
    void developmentUsesTheCanonicalBusinessRailWithoutAnAcceptanceRun() throws IOException {
        String yaml = resource("application-dev.yml");

        assertThat(yaml)
                .contains("source-environment: PRODUCTION")
                .contains("funds-sandbox:", "mode: DISABLED")
                .contains("acceptance-sandbox:", "mode: DISABLED")
                .doesNotContain("NEXION_ACCEPTANCE_RUN_ID", "source-environment: SANDBOX");
    }

    @Test
    void developmentDisablesMockFundsAndUsesCanonicalExecutors() throws IOException {
        String yaml = resource("application-dev.yml");
        String defaults = resource("application.yml");

        assertThat(yaml).contains("funds-sandbox:", "mode: DISABLED")
                .contains("payment-method-provider:", "mode: DISABLED")
                .contains("compute-task:", "executor:", "mode: PRODUCTION");
        assertThat(defaults).contains("mode: ${NEXION_FUNDS_SANDBOX_MODE:DISABLED}");
    }

    @Test
    void developmentHasNoDeployableAcceptanceRunMapping() throws IOException {
        String yaml = resource("application-dev.yml");

        assertThat(yaml).doesNotContain("acceptance-run-id:", "NEXION_ACCEPTANCE_RUN_ID", "LOCAL_SANDBOX");
    }

    private static String resource(String name) throws IOException {
        ClassPathResource resource = new ClassPathResource(name);
        assertThat(resource.exists()).isTrue();
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}

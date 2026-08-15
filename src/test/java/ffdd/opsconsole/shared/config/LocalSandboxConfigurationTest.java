package ffdd.opsconsole.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class LocalSandboxConfigurationTest {
    @Test
    void localSandboxMapsOneAcceptanceRunAcrossSandboxDomainsAndEnablesSupport() throws IOException {
        String yaml = resource("application-local-sandbox.yml");

        assertThat(yaml)
                .contains("source-environment: SANDBOX")
                .contains("analytics:", "acceptance-run-id: ${NEXION_ACCEPTANCE_RUN_ID:}")
                .contains("support:", "acceptance-run-id: ${NEXION_ACCEPTANCE_RUN_ID:}")
                .contains("acceptance-sandbox:", "mode: ENABLED")
                .contains("learning:", "acceptance-run-id: ${NEXION_ACCEPTANCE_RUN_ID:}")
                .contains("commerce:", "acceptance-run-id: ${NEXION_ACCEPTANCE_RUN_ID:}");
    }

    @Test
    void localSandboxDoesNotImplicitlyEnableFundsSandbox() throws IOException {
        String yaml = resource("application-local-sandbox.yml");
        String defaults = resource("application.yml");

        assertThat(yaml).doesNotContain("funds-sandbox:", "mode: LOCAL_SANDBOX");
        assertThat(defaults).contains("mode: ${NEXION_FUNDS_SANDBOX_MODE:DISABLED}");
    }

    @Test
    void localSandboxRunIdMappingsHaveAnEmptyFailClosedDefault() throws IOException {
        String yaml = resource("application-local-sandbox.yml");

        String mapping = "acceptance-run-id: ${NEXION_ACCEPTANCE_RUN_ID:}";
        int occurrences = 0;
        for (int offset = 0; (offset = yaml.indexOf(mapping, offset)) >= 0; offset += mapping.length()) {
            occurrences++;
        }
        assertThat(occurrences).isEqualTo(4);
    }

    private static String resource(String name) throws IOException {
        ClassPathResource resource = new ClassPathResource(name);
        assertThat(resource.exists()).isTrue();
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}

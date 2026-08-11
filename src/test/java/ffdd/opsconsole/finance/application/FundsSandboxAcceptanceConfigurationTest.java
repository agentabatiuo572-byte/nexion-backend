package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class FundsSandboxAcceptanceConfigurationTest {
    @Test
    void acceptanceProfileExplicitlyEnablesOnlyTheServerOwnedFundsSandbox() throws IOException {
        ClassPathResource profile = new ClassPathResource("application-acceptance.yml");

        assertThat(profile.exists()).isTrue();
        String yaml = new String(profile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(yaml).contains("funds-sandbox:", "mode: LOCAL_SANDBOX");
        assertThat(yaml).doesNotContain("mode: PROVIDER");
    }
}

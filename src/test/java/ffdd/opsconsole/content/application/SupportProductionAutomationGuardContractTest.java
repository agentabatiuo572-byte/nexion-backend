package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SupportProductionAutomationGuardContractTest {
    @Test
    void bootstrapGatesBeforeTouchingOfficialSupportFacts() throws Exception {
        String bootstrap = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/application/ConversationTimeoutPolicyBootstrap.java"));
        assertThat(bootstrap).contains("if (!productionPathGuard.productionSupportAutomationAllowed()) return;")
                .contains("mapper.ensurePolicyTable");
    }
}

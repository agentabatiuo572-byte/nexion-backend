package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SupportProductionAutomationGuardContractTest {
    @Test
    void bootstrapAndSchedulersGateBeforeTouchingOfficialSupportFacts() throws Exception {
        String bootstrap = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/application/ConversationTimeoutPolicyBootstrap.java"));
        String idle = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/application/ConversationIdleTimeoutScheduler.java"));
        String transfer = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/application/OpsConversationTransferScheduler.java"));
        assertThat(bootstrap).contains("if (!productionPathGuard.productionSupportAutomationAllowed()) return;")
                .contains("mapper.ensurePolicyTable");
        assertThat(idle).contains("if (!productionPathGuard.productionSupportAutomationAllowed()) return new SweepResult(0, 0);")
                .contains("mapper.selectPolicy");
        assertThat(transfer).contains("if (!productionPathGuard.productionSupportAutomationAllowed()) return;")
                .contains("conversationService.runTimeoutFallback");
    }
}

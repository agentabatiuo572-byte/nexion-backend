package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OpsSupportProductionWriteGuardContractTest {
    @Test
    void everyTicketMutationAndConversationCommandGatewayRejectsIsolatedProfiles() throws Exception {
        String tickets = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/web/OpsSupportTicketController.java"));
        String conversations = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/web/OpsConversationController.java"));
        String agents = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/web/OpsSupportAgentController.java"));
        String knowledge = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/web/OpsSupportKnowledgeController.java"));
        String timeout = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/web/OpsConversationTimeoutPolicyController.java"));
        String receipt = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/web/AppConversationReceiptController.java"));
        String templates = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/web/OpsSessionTemplateController.java"));
        assertThat(occurrences(tickets, "productionPathGuard.requireOpsWriteAllowed()"))
                .isEqualTo(10);
        assertThat(conversations).contains("productionPathGuard.requireOpsWriteAllowed();")
                .contains("private <T> ApiResult<T> executeCommand");
        assertThat(occurrences(agents, "productionPathGuard.requireOpsWriteAllowed()"))
                .isEqualTo(5);
        assertThat(knowledge).contains("productionPathGuard.requireOpsWriteAllowed();")
                .contains("private <T> ApiResult<T> executeCommand");
        assertThat(timeout).contains("productionPathGuard.requireOpsWriteAllowed();");
        assertThat(receipt).contains("productionPathGuard.requireAllowed(userId);")
                .contains("supportService.markConversationRead");
        assertThat(occurrences(templates, "productionPathGuard.requireOpsWriteAllowed();"))
                .isEqualTo(3);
        assertThat(templates).contains("private <T> ApiResult<T> executeCommand")
                .contains("private <T> ApiResult<T> executeSupportOperationCommand")
                .contains("private <T> ApiResult<T> executeContentCommand");
    }

    private int occurrences(String value, String needle) {
        int count = 0, at = 0;
        while ((at = value.indexOf(needle, at)) >= 0) { count++; at += needle.length(); }
        return count;
    }
}

package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SupportAcceptanceSandboxStateContractTest {
    @Test
    void onlyOpenOrResolvedSandboxConversationsMayBeMutated() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/application/SupportAcceptanceSandboxService.java"));
        assertThat(source).contains("requireOperableConversation(c)")
                .contains("!\"OPEN\".equals(status)&&!\"RESOLVED\".equals(status)")
                .contains("CONVERSATION_INVALID_STATE");
    }

    @Test
    void readHeaderCasIsTheOnlyExactlyOneReceiptGate() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/application/SupportAcceptanceSandboxService.java"));
        assertThat(source).contains("cas(mapper.readHeaderCas", "mapper.readCas(")
                .doesNotContain("cas(mapper.readCas");
    }

    @Test
    void transferIsOpenOnly() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/application/SupportAcceptanceSandboxService.java"));
        assertThat(source).contains("requireTransferableConversation(c)")
                .contains("!\"OPEN\".equals(upper(conversation,\"status\"))");
    }
}

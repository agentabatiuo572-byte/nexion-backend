package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SupportAcceptanceSandboxStateContractTest {
    @Test
    void onlyOpenOrResolvedSandboxTicketsAndConversationsMayBeMutated() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/application/SupportAcceptanceSandboxService.java"));
        assertThat(source).contains("requireReplyableTicket(t)", "requireOperableConversation(c)")
                .contains("!\"OPEN\".equals(status)&&!\"RESOLVED\".equals(status)")
                .contains("SUPPORT_TICKET_INVALID_STATE", "CONVERSATION_INVALID_STATE");
    }

    @Test
    void readHeaderCasIsTheOnlyExactlyOneReceiptGate() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/application/SupportAcceptanceSandboxService.java"));
        assertThat(source).contains("cas(mapper.readHeaderCas", "mapper.readCas(")
                .doesNotContain("cas(mapper.readCas");
    }

    @Test
    void transferIsOpenOnlyWhileAppConversionPermitsEveryNonClosedState() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/application/SupportAcceptanceSandboxService.java"));
        assertThat(source).contains("requireTransferableConversation(c)", "requireNotClosedConversation(c)")
                .contains("!\"OPEN\".equals(status)", "\"CLOSED\".equals(upper(conversation,\"status\"))");
    }
}

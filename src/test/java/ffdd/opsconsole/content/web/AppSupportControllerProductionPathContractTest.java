package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AppSupportControllerProductionPathContractTest {
    @Test
    void everySharedAppSupportEntryPassesTheProductionPathGuardBeforeTheService() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/web/AppSupportController.java"));
        assertThat(occurrences(source, "guarded(userId)")).isEqualTo(12);
        for (String serviceCall : new String[] {
                "service.tickets", "service.ticket", "service.createTicket", "service.replyTicket",
                "service.closeTicket", "service.conversations", "service.conversation",
                "service.markConversationRead", "service.startConversation", "service.replyConversation",
                "service.convertConversationToTicket", "service.faqs"}) {
            assertThat(source.indexOf("guarded(userId)", source.indexOf(serviceCall) - 80))
                    .as(serviceCall + " must be guarded before entering AppSupportService")
                    .isGreaterThanOrEqualTo(0);
        }
    }

    private int occurrences(String value, String needle) {
        int count = 0, index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) { count++; index += needle.length(); }
        return count;
    }
}

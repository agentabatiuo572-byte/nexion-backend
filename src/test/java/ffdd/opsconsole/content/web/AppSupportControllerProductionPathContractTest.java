package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AppSupportControllerProductionPathContractTest {
    @Test
    void everySharedAppSupportEntryPassesTheProductionPathGuardBeforeTheService() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/web/AppSupportController.java"));
        String[] guardedServiceCalls = new String[] {
                "service.tickets", "service.ticketCursor", "service.ticket", "service.createTicket", "service.replyTicket",
                "service.closeTicket", "service.markTicketRead", "service.conversations", "service.conversationCategories",
                "service.conversationCursor", "service.conversation",
                "service.markConversationRead", "service.startConversation", "service.replyConversation",
                "service.convertConversationToTicket", "service.faqs", "service.faqPage", "service.slaTargets",
                "service.commandResult"};
        assertThat(occurrences(source, "guarded(userId)")).isEqualTo(guardedServiceCalls.length);
        for (String serviceCall : guardedServiceCalls) {
            assertThat(source.indexOf("guarded(userId)", source.indexOf(serviceCall) - 80))
                    .as(serviceCall + " must be guarded before entering AppSupportService")
                    .isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void productionReadWhitelistIncludesTheSupportConfigurationAndRecoveryPaths() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ffdd/opsconsole/content/web/AppSupportController.java"));

        assertThat(source).contains("@GetMapping(\"/conversation-categories\")", "service.conversationCategories(userId)",
                "@GetMapping(\"/sla-targets\")", "service.slaTargets(userId)",
                "@GetMapping(\"/commands/{key}\")", "service.commandResult(userId, key)");
        assertThat(source).doesNotContain("@PostMapping(\"/commands")
                .doesNotContain("@PutMapping(\"/commands")
                .doesNotContain("@DeleteMapping(\"/commands");
    }

    private int occurrences(String value, String needle) {
        int count = 0, index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) { count++; index += needle.length(); }
        return count;
    }
}

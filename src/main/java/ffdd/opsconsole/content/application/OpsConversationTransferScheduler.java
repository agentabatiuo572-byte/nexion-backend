package ffdd.opsconsole.content.application;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.context.ApplicationEventPublisher;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class OpsConversationTransferScheduler {
    private final OpsConversationService conversationService;
    private final ApplicationEventPublisher eventPublisher;
    private final ProductionSupportPathGuard productionPathGuard;

    @Scheduled(
            initialDelayString = "${nexion.ops.content.transfer-fallback-initial-delay-ms:60000}",
            fixedDelayString = "${nexion.ops.content.transfer-fallback-delay-ms:60000}")
    public void runTimeoutFallback() {
        if (!productionPathGuard.productionSupportAutomationAllowed()) return;
        // A proxied transactional service returns only after its successful commit.
        for (String conversationNo : conversationService.runTimeoutFallbackConversationNos()) {
            eventPublisher.publishEvent(ConversationMessageEvent.builder()
                    .conversationNo(conversationNo).eventType(ConversationMessageEvent.EventType.STATUS)
                    .senderType("SYSTEM").senderName("System")
                    .body("TIMEOUT_FALLBACK").ts(LocalDateTime.now()).build());
        }
    }
}

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

    @Scheduled(
            initialDelayString = "${nexion.ops.content.transfer-fallback-initial-delay-ms:60000}",
            fixedDelayString = "${nexion.ops.content.transfer-fallback-delay-ms:60000}")
    public void runTimeoutFallback() {
        int changed = conversationService.runTimeoutFallback();
        if (changed > 0) {
            eventPublisher.publishEvent(ConversationMessageEvent.builder()
                    .conversationNo("*").eventType(ConversationMessageEvent.EventType.STATUS)
                    .senderType("SYSTEM").senderName("System")
                    .body("TIMEOUT_FALLBACK_BATCH_CHANGED:" + changed).ts(LocalDateTime.now()).build());
        }
    }
}

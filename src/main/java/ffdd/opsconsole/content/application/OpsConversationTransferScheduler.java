package ffdd.opsconsole.content.application;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OpsConversationTransferScheduler {
    private final OpsConversationService conversationService;

    @Scheduled(
            initialDelayString = "${nexion.ops.content.transfer-fallback-initial-delay-ms:60000}",
            fixedDelayString = "${nexion.ops.content.transfer-fallback-delay-ms:60000}")
    public void runTimeoutFallback() {
        conversationService.runTimeoutFallback();
    }
}

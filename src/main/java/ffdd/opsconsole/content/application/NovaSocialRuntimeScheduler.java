package ffdd.opsconsole.content.application;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NovaSocialRuntimeScheduler {
    private final NovaSocialRuntimeService runtimeService;

    @Scheduled(
            initialDelayString = "${nexion.ops.content.nova-social-sync-initial-delay-ms:60000}",
            fixedDelayString = "${nexion.ops.content.nova-social-sync-delay-ms:600000}")
    public void syncRealEvents() {
        runtimeService.runScheduledSync();
    }

    @Scheduled(
            initialDelayString = "${nexion.ops.content.nova-social-dispatch-initial-delay-ms:70000}",
            fixedDelayString = "${nexion.ops.content.nova-social-dispatch-delay-ms:30000}")
    public void dispatchSocialNotifications() {
        runtimeService.runScheduledDispatch();
    }
}

package ffdd.opsconsole.content.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class NovaSocialRuntimeScheduler {
    private final NovaSocialRuntimeService runtimeService;
    private final NovaBusinessRuntimeService businessRuntimeService;

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

    @Scheduled(
            initialDelayString = "${nexion.ops.content.nova-business-dispatch-initial-delay-ms:75000}",
            fixedDelayString = "${nexion.ops.content.nova-business-dispatch-delay-ms:30000}")
    public void dispatchBusinessNotifications() {
        businessRuntimeService.channelKeys().forEach(channel -> {
            try {
                businessRuntimeService.runScheduledChannel(channel);
            } catch (RuntimeException exception) {
                log.error("Nova business channel dispatch failed: channel={}", channel, exception);
            }
        });
    }
}

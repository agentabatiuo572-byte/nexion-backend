package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.NovaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class NovaSocialRuntimeScheduler implements ApplicationRunner {
    private final NovaSocialRuntimeService runtimeService;
    private final NovaBusinessRuntimeService businessRuntimeService;
    private final OpsNovaService opsNovaService;
    private final NovaRepository novaRepository;

    /**
     * Nova tables, columns and baseline rows are prepared once here, the same way the other bootstrap
     * initializers of this package work. Reads must not create schema or write defaults.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) {
        novaRepository.ensureTables();
    }

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
            initialDelayString = "${nexion.ops.content.nova-social-expiry-initial-delay-ms:80000}",
            fixedDelayString = "${nexion.ops.content.nova-social-expiry-delay-ms:60000}")
    public void expireDueSocialEvents() {
        try {
            opsNovaService.expireOverdueSocialEvents();
        } catch (RuntimeException exception) {
            log.error("Nova social event expiry failed", exception);
        }
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

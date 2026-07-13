package ffdd.opsconsole.janus.application;

import ffdd.opsconsole.janus.domain.JanusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class JanusOverrideExpiryScheduler {
    private final JanusRepository repository;

    @Scheduled(fixedDelayString = "${nexion.janus.override-expiry-interval-ms:60000}")
    @Transactional
    public void expireOverrides() {
        repository.expireDeviceOverrides(System.currentTimeMillis());
    }
}

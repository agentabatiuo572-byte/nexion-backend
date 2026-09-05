package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.auth.mapper.UserLoginGuardMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserLoginGuardCleanupScheduler {
    private final UserLoginGuardMapper mapper;

    @Scheduled(fixedDelayString = "${nexion.auth.login-guard-cleanup-ms:600000}")
    public void cleanup() {
        LocalDateTime before = LocalDateTime.now().minusHours(1);
        for (int batch = 0; batch < 10 && mapper.deleteExpired(before) == 1000; batch++) {
            // Bounded batches avoid one large delete transaction under hostile traffic.
        }
        LocalDateTime eventBefore = LocalDateTime.now().minusHours(25);
        for (int batch = 0; batch < 10 && mapper.deleteExpiredOtpSendEvents(eventBefore) == 1000; batch++) {
            // Keep trailing-window evidence long enough for rate limiting, then bound retention.
        }
    }
}

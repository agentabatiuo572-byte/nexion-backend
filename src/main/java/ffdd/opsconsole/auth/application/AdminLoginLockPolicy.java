package ffdd.opsconsole.auth.application;

import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class AdminLoginLockPolicy {
    public Optional<Duration> lockDuration(long shortFailures, long dailyFailures) {
        if (dailyFailures >= 15) {
            return Optional.of(Duration.ofHours(24));
        }
        if (shortFailures >= 5) {
            return Optional.of(Duration.ofMinutes(15));
        }
        return Optional.empty();
    }
}

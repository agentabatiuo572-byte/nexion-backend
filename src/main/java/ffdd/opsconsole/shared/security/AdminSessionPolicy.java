package ffdd.opsconsole.shared.security;

import java.time.Duration;
import java.time.Instant;

public record AdminSessionPolicy(long idleMinutes, long absoluteMinutes) {
    public AdminSessionPolicy {
        idleMinutes = Math.max(1, idleMinutes);
        absoluteMinutes = Math.max(idleMinutes, absoluteMinutes);
    }

    public boolean isActive(Instant issuedAt, Instant lastSeenAt, Instant now) {
        if (issuedAt == null || lastSeenAt == null || now == null) {
            return false;
        }
        return !now.isAfter(issuedAt.plus(Duration.ofMinutes(absoluteMinutes)))
                && !now.isAfter(lastSeenAt.plus(Duration.ofMinutes(idleMinutes)));
    }
}

package ffdd.opsconsole.shared.security;

import java.time.Duration;
import java.time.Instant;

public record AdminSessionPolicy(long idleMinutes, long absoluteMinutes) {
    public static final long IDLE_MINUTES = 60;
    public static final String BASELINE_VALUE = "60min / unlimited";
    public static final String BASELINE_DESCRIPTION = "后台无有效操作60分钟自动登出；有效操作续期，无绝对登录时限。";
    public AdminSessionPolicy {
        idleMinutes = Math.max(1, idleMinutes);
        absoluteMinutes = 0; // ADMIN has only the idle boundary; no absolute login lifetime.
    }

    public boolean isActive(Instant issuedAt, Instant lastSeenAt, Instant now) {
        if (issuedAt == null || lastSeenAt == null || now == null) {
            return false;
        }
        return !now.isBefore(lastSeenAt)
                && now.isBefore(lastSeenAt.plus(Duration.ofMinutes(idleMinutes)));
    }
}

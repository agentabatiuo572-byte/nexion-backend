package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AdminSessionPolicyTest {
    private final AdminSessionPolicy policy = new AdminSessionPolicy(60, 0);
    private final Instant now = Instant.parse("2026-07-17T10:00:00Z");

    @Test void expiresAtExactlySixtyMinutesIdle() {
        assertThat(policy.isActive(now.minusSeconds(3601), now.minusSeconds(3600), now)).isFalse();
    }
    @Test void acceptsRecentActivityBeyondEightHours() {
        assertThat(policy.isActive(now.minusSeconds(86400), now.minusSeconds(5), now)).isTrue();
    }
    @Test void acceptsJustBeforeIdleBoundary() {
        assertThat(policy.isActive(now.minusSeconds(86400), now.minusSeconds(3599), now)).isTrue();
    }
    @Test void rejectsMissingOrFutureActivity() {
        assertThat(policy.isActive(now, null, now)).isFalse();
        assertThat(policy.isActive(now, now.plusSeconds(1), now)).isFalse();
    }
}

package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AdminSessionPolicyTest {
    private final AdminSessionPolicy policy = new AdminSessionPolicy(30, 480);
    private final Instant now = Instant.parse("2026-07-17T10:00:00Z");

    @Test
    void expiresAfterThirtyMinutesIdle() {
        assertThat(policy.isActive(now.minusSeconds(60), now.minusSeconds(30 * 60 + 1), now)).isFalse();
    }

    @Test
    void expiresAfterEightHoursEvenWhenRecentlyActive() {
        assertThat(policy.isActive(now.minusSeconds(8 * 60 * 60 + 1), now.minusSeconds(5), now)).isFalse();
    }

    @Test
    void acceptsSessionInsideBothBoundaries() {
        assertThat(policy.isActive(now.minusSeconds(60), now.minusSeconds(5), now)).isTrue();
    }
}

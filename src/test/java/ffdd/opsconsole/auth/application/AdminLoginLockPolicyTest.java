package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AdminLoginLockPolicyTest {
    private final AdminLoginLockPolicy policy = new AdminLoginLockPolicy();

    @Test
    void locksForFifteenMinutesAfterFiveRecentFailures() {
        assertThat(policy.lockDuration(4, 4)).isEmpty();
        assertThat(policy.lockDuration(5, 5)).contains(Duration.ofMinutes(15));
    }

    @Test
    void escalatesToTwentyFourHoursAfterFifteenDailyFailures() {
        assertThat(policy.lockDuration(5, 15)).contains(Duration.ofHours(24));
    }
}

package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class WithdrawalDayWindowTest {
    @Test
    void vietnamMidnightMovesBothCountingWindowAndDisplayedResetRegardlessOfJvmZone() {
        var before = WithdrawalDayWindow.at(Clock.fixed(
                Instant.parse("2026-08-31T16:59:59Z"), ZoneId.of("Asia/Tokyo")));
        var after = WithdrawalDayWindow.at(Clock.fixed(
                Instant.parse("2026-08-31T17:00:00Z"), ZoneId.of("America/Los_Angeles")));
        assertThat(before.fromInclusive()).isEqualTo(LocalDateTime.parse("2026-08-31T01:00:00"));
        assertThat(before.toExclusive()).isEqualTo(LocalDateTime.parse("2026-09-01T01:00:00"));
        assertThat(before.resetAt()).isEqualTo(Instant.parse("2026-08-31T17:00:00Z").toEpochMilli());
        assertThat(after.fromInclusive()).isEqualTo(before.toExclusive());
        assertThat(after.toExclusive()).isEqualTo(LocalDateTime.parse("2026-09-02T01:00:00"));
        assertThat(after.resetAt()).isEqualTo(Instant.parse("2026-09-01T17:00:00Z").toEpochMilli());
    }
}

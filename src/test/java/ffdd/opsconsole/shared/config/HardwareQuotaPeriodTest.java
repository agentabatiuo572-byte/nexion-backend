package ffdd.opsconsole.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class HardwareQuotaPeriodTest {
    @Test
    void legacyMonthAndActiveOctoberKeepTheirOriginalStart() {
        var september = HardwareQuotaPeriod.atUtc(LocalDateTime.parse("2026-09-30T17:30:00"));
        assertThat(september.fromUtc()).isEqualTo("2026-09-01T00:00:00");
        assertThat(september.untilUtc()).isEqualTo("2026-10-01T00:00:00");
        var beforeCutover = HardwareQuotaPeriod.atUtc(LocalDateTime.parse("2026-10-18T16:59:59"));
        var afterCutover = HardwareQuotaPeriod.atUtc(LocalDateTime.parse("2026-10-18T17:00:00"));
        assertThat(afterCutover).isEqualTo(beforeCutover);
        assertThat(afterCutover.fromUtc()).isEqualTo("2026-10-01T00:00:00");
        assertThat(afterCutover.untilUtc()).isEqualTo("2026-10-31T17:00:00");
        assertThat(afterCutover.fromOrderStorage()).isEqualTo("2026-10-01T08:00:00");
        assertThat(afterCutover.untilOrderStorage()).isEqualTo("2026-11-01T01:00:00");
    }

    @Test
    void vietnamMidnightChangesMonthExactlyOnceWithNoGapOrOverlap() {
        var october = HardwareQuotaPeriod.atUtc(LocalDateTime.parse("2026-10-31T16:59:59.999999"));
        var november = HardwareQuotaPeriod.atUtc(LocalDateTime.parse("2026-10-31T17:00:00"));
        assertThat(october.untilUtc()).isEqualTo(november.fromUtc());
        assertThat(november.fromUtc()).isEqualTo("2026-10-31T17:00:00");
        assertThat(november.untilUtc()).isEqualTo("2026-11-30T17:00:00");
        assertThat(november.fromOrderStorage()).isEqualTo("2026-11-01T01:00:00");
        assertThat(HardwareQuotaPeriod.atUtc(LocalDateTime.parse("2026-11-01T00:00:00"))).isEqualTo(november);
        var december = HardwareQuotaPeriod.atUtc(LocalDateTime.parse("2026-11-30T17:00:00"));
        assertThat(november.untilUtc()).isEqualTo(december.fromUtc());
        var january = HardwareQuotaPeriod.atUtc(LocalDateTime.parse("2026-12-31T17:00:00"));
        assertThat(december.untilUtc()).isEqualTo(january.fromUtc());
        assertThat(january.untilUtc()).isEqualTo("2027-01-31T17:00:00");
    }
}

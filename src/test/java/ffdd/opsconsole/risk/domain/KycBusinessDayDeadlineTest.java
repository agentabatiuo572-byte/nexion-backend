package ffdd.opsconsole.risk.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class KycBusinessDayDeadlineTest {
    @Test
    void fridayPlusOneWorkingDayIsMonday() {
        assertThat(KycBusinessDayDeadline.addWorkingDays(LocalDateTime.of(2026, 7, 17, 14, 30), 1))
                .isEqualTo(LocalDateTime.of(2026, 7, 20, 14, 30));
    }

    @Test
    void saturdayPlusOneWorkingDayIsMonday() {
        assertThat(KycBusinessDayDeadline.addWorkingDays(LocalDateTime.of(2026, 7, 18, 9, 15), 1))
                .isEqualTo(LocalDateTime.of(2026, 7, 20, 9, 15));
    }

    @Test
    void sevenWorkingDaysSkipWeekendAndPreserveTime() {
        assertThat(KycBusinessDayDeadline.addWorkingDays(LocalDateTime.of(2026, 7, 17, 8, 45), 7))
                .isEqualTo(LocalDateTime.of(2026, 7, 28, 8, 45));
    }
}

package ffdd.opsconsole.risk.domain;

import java.time.DayOfWeek;
import java.time.LocalDateTime;

public final class KycBusinessDayDeadline {
    private KycBusinessDayDeadline() {
    }

    public static LocalDateTime addWorkingDays(LocalDateTime start, int workingDays) {
        if (start == null || workingDays < 1) {
            throw new IllegalArgumentException("K5_WORKING_DAYS_INVALID");
        }
        LocalDateTime cursor = start;
        int added = 0;
        while (added < workingDays) {
            cursor = cursor.plusDays(1);
            DayOfWeek day = cursor.getDayOfWeek();
            if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return cursor;
    }
}

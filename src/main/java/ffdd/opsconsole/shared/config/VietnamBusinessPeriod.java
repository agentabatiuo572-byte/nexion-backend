package ffdd.opsconsole.shared.config;

import java.time.*;
import java.time.temporal.WeekFields;
import java.util.Locale;

/** Versioned growth/team calendar. Storage and event timestamps remain Asia/Shanghai.
 * Boundaries before the rollout retain their original meaning for settlement replay;
 * the open day/week/month bridge to 2026-10-19 00:00 Vietnam without gaps or overlap.
 */
public final class VietnamBusinessPeriod {
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final ZoneId STORAGE_ZONE = DateTimeFormatConfig.BUSINESS_ZONE;
    public static final LocalDate FIRST_VIETNAM_BOUNDARY = LocalDate.of(2026, 10, 19);

    private VietnamBusinessPeriod() { }

    public static LocalDate dateAt(Instant instant) {
        return calendarTime(LocalDateTime.ofInstant(instant, STORAGE_ZONE)).toLocalDate();
    }

    public static LocalDateTime calendarTime(LocalDateTime storageTime) {
        return storageTime.isBefore(FIRST_VIETNAM_BOUNDARY.atStartOfDay()) ? storageTime
                : storageTime.atZone(STORAGE_ZONE).withZoneSameInstant(ZONE).toLocalDateTime();
    }

    public static LocalDateTime storageBoundary(LocalDate date) {
        return date.isBefore(FIRST_VIETNAM_BOUNDARY) ? date.atStartOfDay()
                : date.atStartOfDay(ZONE).withZoneSameInstant(STORAGE_ZONE).toLocalDateTime();
    }

    public static String weekKey(LocalDateTime storageTime) {
        LocalDate date = calendarTime(storageTime).toLocalDate();
        return String.format(Locale.ROOT, "WEEK:%04d-W%02d", date.get(WeekFields.ISO.weekBasedYear()),
                date.get(WeekFields.ISO.weekOfWeekBasedYear()));
    }
}

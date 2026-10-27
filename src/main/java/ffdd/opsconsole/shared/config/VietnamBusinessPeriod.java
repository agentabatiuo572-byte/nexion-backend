package ffdd.opsconsole.shared.config;

import java.time.*;

/** Versioned growth/team calendar. Storage and event timestamps remain Asia/Shanghai.
 * Boundaries before the rollout retain their original meaning for settlement replay;
 * the open day/week/month bridge to 2026-10-19 00:00 Vietnam without gaps or overlap.
 * R-1: delegates to the GROWTH_TEAM track of {@link BusinessCalendar}, so a business time zone set in A3
 * applies here from its effective date as well.
 */
public final class VietnamBusinessPeriod {
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final ZoneId STORAGE_ZONE = DateTimeFormatConfig.BUSINESS_ZONE;
    public static final LocalDate FIRST_VIETNAM_BOUNDARY = BusinessCalendar.FIRST_VIETNAM_DATE;

    private VietnamBusinessPeriod() { }

    public static LocalDate dateAt(Instant instant) {
        return BusinessCalendar.growthTeam().dateAt(instant);
    }

    public static LocalDateTime calendarTime(LocalDateTime storageTime) {
        return BusinessCalendar.growthTeam().calendarTime(storageTime);
    }

    public static LocalDateTime storageBoundary(LocalDate date) {
        return BusinessCalendar.growthTeam().storageStart(date);
    }

    public static String weekKey(LocalDateTime storageTime) {
        return BusinessCalendar.growthTeam().weekKey(storageTime);
    }
}

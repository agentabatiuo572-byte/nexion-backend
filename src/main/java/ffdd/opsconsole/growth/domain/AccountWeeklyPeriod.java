package ffdd.opsconsole.growth.domain;

import java.time.*;
import java.time.temporal.TemporalAdjusters;

/** Immutable calendar coordinates. Database DATETIME values still represent +08 instants. */
public record AccountWeeklyPeriod(String zoneId, LocalDate localWeekStart, Instant from, Instant until) {
    public static final ZoneId STORAGE_ZONE = ZoneId.of("Asia/Shanghai");
    public static final String CALENDAR_VERSION = "PHONE_REGION_V1";

    public AccountWeeklyPeriod {
        ZoneId.of(zoneId);
        if (localWeekStart.getDayOfWeek() != DayOfWeek.MONDAY || !until.isAfter(from)) {
            throw new IllegalArgumentException("WEEKLY_PERIOD_INVALID");
        }
    }

    public static AccountWeeklyPeriod regular(Instant now, String zoneId) {
        ZoneId zone = ZoneId.of(zoneId);
        LocalDate monday = now.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new AccountWeeklyPeriod(zoneId, monday, monday.atStartOfDay(zone).toInstant(),
                monday.plusWeeks(1).atStartOfDay(zone).toInstant());
    }

    /** A zone change finishes the old window and gives exactly one successor week its new end. */
    public static AccountWeeklyPeriod successor(LocalDate previousMonday, Instant previousUntil, String zoneId) {
        LocalDate monday = previousMonday.plusWeeks(1);
        return new AccountWeeklyPeriod(zoneId, monday, previousUntil,
                monday.plusWeeks(1).atStartOfDay(ZoneId.of(zoneId)).toInstant());
    }

    public boolean contains(Instant instant) { return !instant.isBefore(from) && instant.isBefore(until); }
    public LocalDateTime storageFrom() { return LocalDateTime.ofInstant(from, STORAGE_ZONE); }
    public LocalDateTime storageUntil() { return LocalDateTime.ofInstant(until, STORAGE_ZONE); }
    public static Instant instant(LocalDateTime storage) { return storage.atZone(STORAGE_ZONE).toInstant(); }
}

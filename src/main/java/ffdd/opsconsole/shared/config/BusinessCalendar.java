package ffdd.opsconsole.shared.config;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * R-1: versioned business calendar. Storage timestamps stay Asia/Shanghai local time; only day/week/month
 * cutting follows the business time zone in effect. Each track keeps its own history (LEGACY = Beijing time,
 * GROWTH_TEAM = Beijing until 2026-10-19 then Vietnam); a GLOBAL version set by the super admin applies to
 * every track from its effective date, so settled periods keep their original meaning.
 *
 * <p>A version effective on business date D switches at {@code effectiveStorageAt}, the earliest "D 00:00"
 * among the zones involved. {@link Track#dateAt} reads the date in the zone of the version at that instant and
 * {@link Track#storageStart} the start of a date in the zone of the version for that date, so moving the clock
 * back lengthens the last old day and moving it forward shortens it: no gap, no overlap.
 */
public final class BusinessCalendar {
    /** Same as DateTimeFormatConfig.BUSINESS_ZONE: storage stays Beijing time (fixed +8). */
    public static final ZoneId STORAGE_ZONE = ZoneId.of("Asia/Shanghai");
    public static final String LEGACY = "LEGACY";
    public static final String GROWTH_TEAM = "GROWTH_TEAM";
    public static final String GLOBAL = "GLOBAL";
    static final LocalDate EPOCH = LocalDate.of(1970, 1, 1);
    static final LocalDate FIRST_VIETNAM_DATE = LocalDate.of(2026, 10, 19);

    public record Version(String track, ZoneId zone, LocalDate effectiveDate, LocalDateTime effectiveStorageAt) { }

    private static volatile List<Version> versions = seed();

    private BusinessCalendar() { }

    /** Matches the seed rows of nx_business_calendar; used until the database copy is loaded (and in unit tests). */
    public static List<Version> seed() {
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        return List.of(
                new Version(LEGACY, shanghai, EPOCH, EPOCH.atStartOfDay()),
                new Version(GROWTH_TEAM, shanghai, EPOCH, EPOCH.atStartOfDay()),
                new Version(GROWTH_TEAM, ZoneId.of("Asia/Ho_Chi_Minh"), FIRST_VIETNAM_DATE, FIRST_VIETNAM_DATE.atStartOfDay()));
    }

    public static List<Version> versions() { return versions; }

    public static void replaceVersions(List<Version> next) {
        if (next == null || next.isEmpty()) return;
        versions = List.copyOf(next);
    }

    public static Track legacy() { return new Track(LEGACY); }

    public static Track growthTeam() { return new Track(GROWTH_TEAM); }

    /** The instant a version effective on {@code date} in {@code zone} takes over from {@code previousZones}. */
    public static LocalDateTime switchStorageAt(LocalDate date, ZoneId zone, List<ZoneId> previousZones) {
        Instant earliest = date.atStartOfDay(zone).toInstant();
        for (ZoneId previous : previousZones) {
            Instant candidate = date.atStartOfDay(previous).toInstant();
            if (candidate.isBefore(earliest)) earliest = candidate;
        }
        return LocalDateTime.ofInstant(earliest, STORAGE_ZONE);
    }

    public static final class Track {
        private final String name;

        private Track(String name) { this.name = name; }

        public String name() { return name; }

        private List<Version> own() {
            return versions.stream().filter(v -> v.track().equals(name) || v.track().equals(GLOBAL)).toList();
        }

        public Version versionAtStorage(LocalDateTime storageTime) {
            return own().stream().filter(v -> !v.effectiveStorageAt().isAfter(storageTime))
                    .max(Comparator.comparing(Version::effectiveStorageAt)).orElse(seed().get(0));
        }

        public Version versionOnDate(LocalDate date) {
            return own().stream().filter(v -> !v.effectiveDate().isAfter(date))
                    .max(Comparator.comparing(Version::effectiveDate)).orElse(seed().get(0));
        }

        public ZoneId zoneAt(Instant instant) { return versionAtStorage(storageTime(instant)).zone(); }

        public ZoneId currentZone(Clock clock) { return zoneAt(clock.instant()); }

        /** Business wall-clock time for a stored (Shanghai local) timestamp. */
        public LocalDateTime calendarTime(LocalDateTime storageTime) {
            return storageTime.atZone(STORAGE_ZONE).withZoneSameInstant(versionAtStorage(storageTime).zone()).toLocalDateTime();
        }

        public LocalDate dateAtStorage(LocalDateTime storageTime) { return calendarTime(storageTime).toLocalDate(); }

        public LocalDate dateAt(Instant instant) { return dateAtStorage(storageTime(instant)); }

        public LocalDate today(Clock clock) { return dateAt(clock.instant()); }

        /** "UTC+08:00"-style label of the zone in effect now, for responses that state their cut. */
        public String offsetLabel() {
            String id = zoneAt(Instant.now()).getRules().getOffset(Instant.now()).getId();
            return "UTC" + ("Z".equals(id) ? "+00:00" : id);
        }

        public YearMonth month(Instant instant) { return YearMonth.from(dateAt(instant)); }

        /** Start of a business date as an instant. */
        public Instant startOf(LocalDate date) { return date.atStartOfDay(versionOnDate(date).zone()).toInstant(); }

        /** Start of a business date as a stored (Shanghai local) timestamp, for SQL range bounds. */
        public LocalDateTime storageStart(LocalDate date) { return storageTime(startOf(date)); }

        public LocalDateTime storageStart(YearMonth month) { return storageStart(month.atDay(1)); }

        public String weekKey(LocalDateTime storageTime) {
            LocalDate date = dateAtStorage(storageTime);
            return String.format(Locale.ROOT, "WEEK:%04d-W%02d", date.get(WeekFields.ISO.weekBasedYear()),
                    date.get(WeekFields.ISO.weekOfWeekBasedYear()));
        }

        private static LocalDateTime storageTime(Instant instant) { return LocalDateTime.ofInstant(instant, STORAGE_ZONE); }
    }
}

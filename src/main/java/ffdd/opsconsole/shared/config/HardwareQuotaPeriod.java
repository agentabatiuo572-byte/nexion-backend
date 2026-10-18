package ffdd.opsconsole.shared.config;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.YearMonth;

/** F4b usage stays UTC; paid-order DATETIME stays +08. Existing month starts are immutable. */
public record HardwareQuotaPeriod(LocalDateTime fromUtc, LocalDateTime untilUtc) {
    private static final String SQL_MONTH = "DATE_FORMAT(CASE WHEN UTC_TIMESTAMP() < '2026-10-18 17:00:00'"
            + " THEN UTC_TIMESTAMP() ELSE DATE_ADD(UTC_TIMESTAMP(), INTERVAL 7 HOUR) END, '%Y-%m-01')";
    private static final String SQL_NEXT_MONTH = "DATE_ADD(" + SQL_MONTH + ", INTERVAL 1 MONTH)";
    public static final String SQL_MONTH_START_UTC = "(CASE WHEN " + SQL_MONTH
            + " < '2026-10-19' THEN " + SQL_MONTH + " ELSE DATE_SUB(" + SQL_MONTH + ", INTERVAL 7 HOUR) END)";
    public static final String SQL_NEXT_MONTH_START_UTC = "(CASE WHEN " + SQL_NEXT_MONTH
            + " < '2026-10-19' THEN " + SQL_NEXT_MONTH + " ELSE DATE_SUB(" + SQL_NEXT_MONTH + ", INTERVAL 7 HOUR) END)";
    public static final String SQL_MONTH_START_ORDER_STORAGE = "DATE_ADD(" + SQL_MONTH_START_UTC + ", INTERVAL 8 HOUR)";
    public static final String SQL_NEXT_MONTH_START_ORDER_STORAGE = "DATE_ADD(" + SQL_NEXT_MONTH_START_UTC + ", INTERVAL 8 HOUR)";

    public static HardwareQuotaPeriod atUtc(LocalDateTime occurredAtUtc) {
        var instant = occurredAtUtc.toInstant(ZoneOffset.UTC);
        var first = VietnamBusinessPeriod.FIRST_VIETNAM_BOUNDARY.atStartOfDay(VietnamBusinessPeriod.ZONE).toInstant();
        var date = instant.isBefore(first) ? occurredAtUtc.toLocalDate() : instant.atZone(VietnamBusinessPeriod.ZONE).toLocalDate();
        var month = YearMonth.from(date);
        return new HardwareQuotaPeriod(boundary(month.atDay(1)), boundary(month.plusMonths(1).atDay(1)));
    }

    private static LocalDateTime boundary(LocalDate date) {
        return date.isBefore(VietnamBusinessPeriod.FIRST_VIETNAM_BOUNDARY) ? date.atStartOfDay()
                : LocalDateTime.ofInstant(date.atStartOfDay(VietnamBusinessPeriod.ZONE).toInstant(), ZoneOffset.UTC);
    }

    public LocalDateTime fromOrderStorage() {
        return LocalDateTime.ofInstant(fromUtc.toInstant(ZoneOffset.UTC), VietnamBusinessPeriod.STORAGE_ZONE);
    }

    public LocalDateTime untilOrderStorage() {
        return LocalDateTime.ofInstant(untilUtc.toInstant(ZoneOffset.UTC), VietnamBusinessPeriod.STORAGE_ZONE);
    }
}

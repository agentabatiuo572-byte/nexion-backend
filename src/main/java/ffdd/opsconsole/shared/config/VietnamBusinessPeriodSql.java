package ffdd.opsconsole.shared.config;

/** SQL equivalent of VietnamBusinessPeriod for existing database-clock queries. */
public final class VietnamBusinessPeriodSql {
    private VietnamBusinessPeriodSql() { }
    public static final String STORAGE_NOW = "CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')";
    public static final String NOW = "(CASE WHEN " + STORAGE_NOW + " < '2026-10-19 00:00:00' THEN " + STORAGE_NOW + " ELSE DATE_SUB(" + STORAGE_NOW + ", INTERVAL 1 HOUR) END)";
    public static final String TODAY = "DATE(" + NOW + ")";
    public static final String MONTH_DATE = "DATE_FORMAT(" + NOW + ", '%Y-%m-01')";
    public static final String NEXT_MONTH_DATE = "DATE_ADD(" + MONTH_DATE + ", INTERVAL 1 MONTH)";
    public static final String WEEK_DATE = "DATE_SUB(" + TODAY + ", INTERVAL WEEKDAY(" + TODAY + ") DAY)";
    public static final String MONTH_START = "(CASE WHEN " + MONTH_DATE + " < '2026-10-19' THEN " + MONTH_DATE + " ELSE DATE_ADD(" + MONTH_DATE + ", INTERVAL 1 HOUR) END)";
    public static final String NEXT_MONTH_START = "(CASE WHEN " + NEXT_MONTH_DATE + " < '2026-10-19' THEN " + NEXT_MONTH_DATE + " ELSE DATE_ADD(" + NEXT_MONTH_DATE + ", INTERVAL 1 HOUR) END)";
    public static final String NEXT_WEEK_DATE = "DATE_ADD(" + WEEK_DATE + ", INTERVAL 7 DAY)";
    public static final String NEXT_WEEK_START = "(CASE WHEN " + NEXT_WEEK_DATE + " < '2026-10-19' THEN " + NEXT_WEEK_DATE + " ELSE DATE_ADD(" + NEXT_WEEK_DATE + ", INTERVAL 1 HOUR) END)";
    public static final String TOMORROW = "DATE_ADD(" + TODAY + ", INTERVAL 1 DAY)";
    public static final String DAY_END = "(CASE WHEN " + TOMORROW + " < '2026-10-19' THEN " + TOMORROW + " ELSE DATE_ADD(" + TOMORROW + ", INTERVAL 1 HOUR) END)";
    public static final String WEEK_START = "(CASE WHEN " + WEEK_DATE + " < '2026-10-19' THEN " + WEEK_DATE + " ELSE DATE_ADD(" + WEEK_DATE + ", INTERVAL 1 HOUR) END)";
}

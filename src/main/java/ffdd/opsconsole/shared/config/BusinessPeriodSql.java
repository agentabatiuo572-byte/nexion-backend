package ffdd.opsconsole.shared.config;

/**
 * R-1: SQL counterpart of {@link BusinessCalendar} for database-clock queries. Every expression is a
 * compile-time constant (usable in mapper annotations): the business offset is read from nx_business_calendar
 * by a scalar subquery, so a newly configured time zone applies without changing mapper signatures.
 * Storage remains UTC+8 (480 minutes). No expression contains "<", so they also fit XML &lt;script&gt; mappers. Expressions ending in _START / _END are stored timestamps; _DATE are
 * business dates.
 */
public final class BusinessPeriodSql {
    public static final String STORAGE_NOW = "CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')";
    static final String DELTA_HEAD = "COALESCE((SELECT bc.offset_minutes-480 FROM nx_business_calendar bc"
            + " WHERE bc.is_deleted=0 AND bc.track IN ('";
    static final String AT_MID = "','GLOBAL') AND NOT bc.effective_storage_at>";
    static final String AT_TAIL = " ORDER BY bc.effective_storage_at DESC,bc.id DESC LIMIT 1),0)";
    static final String ON_MID = "','GLOBAL') AND NOT bc.effective_date>";
    static final String ON_TAIL = " ORDER BY bc.effective_date DESC,bc.id DESC LIMIT 1),0)";

    private BusinessPeriodSql() { }

    /** Runtime-built per-row conversion for queries assembled in Java (not usable in annotations). */
    public static String businessTime(String track, String storageExpr) {
        return "DATE_ADD(" + storageExpr + ",INTERVAL " + DELTA_HEAD + track + AT_MID + storageExpr + AT_TAIL + " MINUTE)";
    }

    /** Beijing time until a GLOBAL version takes effect. */
    public static final class Legacy {
        static final String T = BusinessCalendar.LEGACY;
        public static final String NOW = "DATE_ADD(" + STORAGE_NOW + ",INTERVAL " + DELTA_HEAD + T + AT_MID + STORAGE_NOW + AT_TAIL + " MINUTE)";
        public static final String TODAY = "DATE(" + NOW + ")";
        public static final String TOMORROW = "DATE_ADD(" + TODAY + ",INTERVAL 1 DAY)";
        public static final String WEEK_DATE = "DATE_SUB(" + TODAY + ",INTERVAL WEEKDAY(" + TODAY + ") DAY)";
        public static final String NEXT_WEEK_DATE = "DATE_ADD(" + WEEK_DATE + ",INTERVAL 7 DAY)";
        public static final String MONTH_DATE = "DATE(DATE_FORMAT(" + NOW + ",'%Y-%m-01'))";
        public static final String NEXT_MONTH_DATE = "DATE_ADD(" + MONTH_DATE + ",INTERVAL 1 MONTH)";
        public static final String DAY_START = "DATE_SUB(TIMESTAMP(" + TODAY + "),INTERVAL " + DELTA_HEAD + T + ON_MID + TODAY + ON_TAIL + " MINUTE)";
        public static final String DAY_END = "DATE_SUB(TIMESTAMP(" + TOMORROW + "),INTERVAL " + DELTA_HEAD + T + ON_MID + TOMORROW + ON_TAIL + " MINUTE)";
        public static final String WEEK_START = "DATE_SUB(TIMESTAMP(" + WEEK_DATE + "),INTERVAL " + DELTA_HEAD + T + ON_MID + WEEK_DATE + ON_TAIL + " MINUTE)";
        public static final String NEXT_WEEK_START = "DATE_SUB(TIMESTAMP(" + NEXT_WEEK_DATE + "),INTERVAL " + DELTA_HEAD + T + ON_MID + NEXT_WEEK_DATE + ON_TAIL + " MINUTE)";
        public static final String MONTH_START = "DATE_SUB(TIMESTAMP(" + MONTH_DATE + "),INTERVAL " + DELTA_HEAD + T + ON_MID + MONTH_DATE + ON_TAIL + " MINUTE)";
        public static final String NEXT_MONTH_START = "DATE_SUB(TIMESTAMP(" + NEXT_MONTH_DATE + "),INTERVAL " + DELTA_HEAD + T + ON_MID + NEXT_MONTH_DATE + ON_TAIL + " MINUTE)";
        /** Prefix + stored column + {@link #BUSINESS_TIME_MID} + same column + {@link #BUSINESS_TIME_TAIL}: per-row business time. */
        public static final String BUSINESS_TIME_HEAD = "DATE_ADD(";
        public static final String BUSINESS_TIME_MID = ",INTERVAL " + DELTA_HEAD + T + AT_MID;
        public static final String BUSINESS_TIME_TAIL = AT_TAIL + " MINUTE)";

        private Legacy() { }
    }

    /** Growth and team: Beijing time until 2026-10-19, Vietnam time after, then any GLOBAL version. */
    public static final class GrowthTeam {
        static final String T = BusinessCalendar.GROWTH_TEAM;
        public static final String NOW = "DATE_ADD(" + STORAGE_NOW + ",INTERVAL " + DELTA_HEAD + T + AT_MID + STORAGE_NOW + AT_TAIL + " MINUTE)";
        public static final String TODAY = "DATE(" + NOW + ")";
        public static final String TOMORROW = "DATE_ADD(" + TODAY + ",INTERVAL 1 DAY)";
        public static final String WEEK_DATE = "DATE_SUB(" + TODAY + ",INTERVAL WEEKDAY(" + TODAY + ") DAY)";
        public static final String NEXT_WEEK_DATE = "DATE_ADD(" + WEEK_DATE + ",INTERVAL 7 DAY)";
        public static final String MONTH_DATE = "DATE(DATE_FORMAT(" + NOW + ",'%Y-%m-01'))";
        public static final String NEXT_MONTH_DATE = "DATE_ADD(" + MONTH_DATE + ",INTERVAL 1 MONTH)";
        public static final String DAY_START = "DATE_SUB(TIMESTAMP(" + TODAY + "),INTERVAL " + DELTA_HEAD + T + ON_MID + TODAY + ON_TAIL + " MINUTE)";
        public static final String DAY_END = "DATE_SUB(TIMESTAMP(" + TOMORROW + "),INTERVAL " + DELTA_HEAD + T + ON_MID + TOMORROW + ON_TAIL + " MINUTE)";
        public static final String WEEK_START = "DATE_SUB(TIMESTAMP(" + WEEK_DATE + "),INTERVAL " + DELTA_HEAD + T + ON_MID + WEEK_DATE + ON_TAIL + " MINUTE)";
        public static final String NEXT_WEEK_START = "DATE_SUB(TIMESTAMP(" + NEXT_WEEK_DATE + "),INTERVAL " + DELTA_HEAD + T + ON_MID + NEXT_WEEK_DATE + ON_TAIL + " MINUTE)";
        public static final String MONTH_START = "DATE_SUB(TIMESTAMP(" + MONTH_DATE + "),INTERVAL " + DELTA_HEAD + T + ON_MID + MONTH_DATE + ON_TAIL + " MINUTE)";
        public static final String NEXT_MONTH_START = "DATE_SUB(TIMESTAMP(" + NEXT_MONTH_DATE + "),INTERVAL " + DELTA_HEAD + T + ON_MID + NEXT_MONTH_DATE + ON_TAIL + " MINUTE)";

        private GrowthTeam() { }
    }
}

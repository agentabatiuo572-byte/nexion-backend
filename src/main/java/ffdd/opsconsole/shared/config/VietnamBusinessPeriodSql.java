package ffdd.opsconsole.shared.config;

/** SQL equivalent of VietnamBusinessPeriod for existing database-clock queries (R-1: the GROWTH_TEAM track). */
public final class VietnamBusinessPeriodSql {
    private VietnamBusinessPeriodSql() { }
    public static final String STORAGE_NOW = BusinessPeriodSql.STORAGE_NOW;
    public static final String NOW = BusinessPeriodSql.GrowthTeam.NOW;
    public static final String TODAY = BusinessPeriodSql.GrowthTeam.TODAY;
    public static final String MONTH_DATE = BusinessPeriodSql.GrowthTeam.MONTH_DATE;
    public static final String NEXT_MONTH_DATE = BusinessPeriodSql.GrowthTeam.NEXT_MONTH_DATE;
    public static final String WEEK_DATE = BusinessPeriodSql.GrowthTeam.WEEK_DATE;
    public static final String MONTH_START = BusinessPeriodSql.GrowthTeam.MONTH_START;
    public static final String NEXT_MONTH_START = BusinessPeriodSql.GrowthTeam.NEXT_MONTH_START;
    public static final String NEXT_WEEK_DATE = BusinessPeriodSql.GrowthTeam.NEXT_WEEK_DATE;
    public static final String NEXT_WEEK_START = BusinessPeriodSql.GrowthTeam.NEXT_WEEK_START;
    public static final String TOMORROW = BusinessPeriodSql.GrowthTeam.TOMORROW;
    public static final String DAY_END = BusinessPeriodSql.GrowthTeam.DAY_END;
    public static final String WEEK_START = BusinessPeriodSql.GrowthTeam.WEEK_START;
}

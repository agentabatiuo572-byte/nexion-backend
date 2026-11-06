package ffdd.opsconsole.shared.canonical.mapper;

/** Shared payable holdings and six-decimal amounts for G4 and B1. */
public final class GenesisEmissionSql {
    public static final String ELIGIBLE_HOLDINGS = """
              FROM nx_genesis_holding h
              JOIN nx_user u ON u.id=h.user_id AND COALESCE(u.sandbox,0)=0 AND u.is_deleted=0
              LEFT JOIN nx_genesis_series s ON s.series_code=h.series_code AND s.is_deleted=0
             WHERE h.is_deleted=0 AND UPPER(h.status) IN ('ACTIVE','LISTED')
            """;

    // Use exact decimal arithmetic and round each payable amount before summing.
    private static final String AMOUNT_PREFIX = "GREATEST(0, ROUND(s.price_usdt * ";
    private static final String AMOUNT_SUFFIX = " * 0.01, 6))";
    public static final String CURRENT_AMOUNT = AMOUNT_PREFIX + "s.daily_dividend_rate_pct" + AMOUNT_SUFFIX;
    public static final String PROPOSED_AMOUNT = AMOUNT_PREFIX + "#{rate}" + AMOUNT_SUFFIX;
    public static final String CURRENT_TOTAL = "SELECT COALESCE(SUM(" + CURRENT_AMOUNT + "),0)" + ELIGIBLE_HOLDINGS;

    private GenesisEmissionSql() {}
}

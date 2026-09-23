package ffdd.opsconsole.shared.canonical;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The single server-side publish gate shared by every user-facing storefront
 * read and by the E1 write path that decides whether an administrative row may
 * be listed at all.
 *
 * <p>{@code nx_product.store_visible} is an operator flag, not a publish
 * decision: E1 derives it directly from the SKU status, so any administrative
 * row that was switched on reaches the App unless this gate rejects it. The
 * gate therefore owns the two conditions an operator cannot express through the
 * existing status field:
 *
 * <ul>
 *   <li><b>test identity</b> - the product number or name carries a test/mock
 *       marker. The vocabulary mirrors the repository's existing ops cleanup
 *       predicate ({@code scripts/cleanup-ops-mock-data.sql}, {@code @device_cleanup_regex})
 *       so a row that cleanup classifies as mock data can never be published.</li>
 *   <li><b>no effective earnings</b> - neither {@code estimated_daily_usdt} nor
 *       {@code daily_nex} is positive, i.e. the product has no configured yield
 *       to sell. A priced row with zero yield is a placeholder, not an offer.</li>
 * </ul>
 *
 * <p>Rows already excluded by the pre-existing store predicates
 * ({@code is_deleted}, {@code store_visible}, {@code status}, {@code price_usdt})
 * are not re-classified here; the gate reports a reason only for the rows it is
 * responsible for, so an operator-facing explanation stays unambiguous.
 *
 * <p>The read path evaluates the rule in SQL and the write path evaluates it in
 * {@link #evaluate}; both are derived from the constants below so the two can
 * never disagree about the vocabulary.
 *
 * <p>Alias contract: every SQL predicate references {@code nx_product} as {@code p}.
 */
public final class StorefrontProductPublishGate {

    private StorefrontProductPublishGate() {
    }

    /** Identity expression a publish decision is made on, for {@code nx_product p}. */
    public static final String IDENTITY_SQL = "CONCAT(p.product_no,' ',COALESCE(p.name,''))";

    /**
     * Test/mock identity markers. Kept deliberately narrow: a marker must be a
     * whole token, so {@code latest-box} and {@code testament-box} stay
     * publishable while {@code stellarbox-test} and {@code demo-box} do not.
     */
    public static final String TEST_IDENTIFIER_REGEX =
            "(^|[^[:alnum:]])(test|mock|demo|e2e|smoke|fixture)([-_]|[^[:alnum:]]|$)";

    private static final Pattern TEST_IDENTIFIER = Pattern.compile(TEST_IDENTIFIER_REGEX, Pattern.CASE_INSENSITIVE);

    /** True when the product identity carries a test/mock marker. */
    public static final String TEST_IDENTIFIER_SQL =
            "(LOWER(p.product_no) = 'hd1-0902' OR REGEXP_LIKE(" + IDENTITY_SQL
                    + ", '" + TEST_IDENTIFIER_REGEX + "', 'i'))";

    /**
     * True when the product carries no configured yield in either currency.
     * Written as a negation so the predicate contains no {@code <} and can be
     * embedded in a MyBatis {@code <script>} block without XML escaping.
     */
    public static final String NO_EFFECTIVE_EARNINGS_SQL =
            "NOT (COALESCE(p.estimated_daily_usdt,0)>0 OR COALESCE(p.daily_nex,0)>0)";

    /** True when the gate withholds the row from users. */
    public static final String BLOCKED_SQL =
            "(" + TEST_IDENTIFIER_SQL + " OR (" + NO_EFFECTIVE_EARNINGS_SQL + "))";

    /** True when the gate publishes the row to users. */
    public static final String PUBLISHABLE_SQL = "NOT " + BLOCKED_SQL;

    public static final String TEST_IDENTIFIER_REASON = "PRODUCT_TEST_IDENTIFIER";
    public static final String NO_EFFECTIVE_EARNINGS_REASON = "PRODUCT_NO_EFFECTIVE_EARNINGS";

    /**
     * Reason for a row the gate withholds, or {@code null} when the row is
     * publishable. A test marker wins because it demands an operator action
     * rather than a configuration fix.
     */
    public static final String BLOCK_REASON_SQL = "CASE WHEN " + PUBLISHABLE_SQL + " THEN NULL"
            + " WHEN " + TEST_IDENTIFIER_SQL + " THEN '" + TEST_IDENTIFIER_REASON + "'"
            + " ELSE '" + NO_EFFECTIVE_EARNINGS_REASON + "' END";

    /**
     * Java form of the gate, used by the E1 write path where the row is not
     * persisted yet. {@code null} yield means "not configured" and is treated
     * as zero, matching the SQL {@code COALESCE}.
     */
    public static Decision evaluate(String productNo, String name, BigDecimal dailyUsdt, BigDecimal dailyNex) {
        String identity = (productNo == null ? "" : productNo) + " " + (name == null ? "" : name);
        if ("hd1-0902".equalsIgnoreCase(productNo)
                || TEST_IDENTIFIER.matcher(identity.toLowerCase(Locale.ROOT)).find()) {
            return new Decision(false, TEST_IDENTIFIER_REASON);
        }
        boolean earns = positive(dailyUsdt) || positive(dailyNex);
        return earns ? new Decision(true, null) : new Decision(false, NO_EFFECTIVE_EARNINGS_REASON);
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    /** Outcome of the gate for one product row. {@code reason} is null when publishable. */
    public record Decision(boolean publishable, String reason) {
    }
}

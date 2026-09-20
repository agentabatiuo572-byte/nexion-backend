package ffdd.opsconsole.shared.canonical;

import java.util.regex.Pattern;

/**
 * The single server-side gate that keeps the retired {@code Nexion} brand out of
 * every user-facing published copy.
 *
 * <p>Why a shared gate rather than a per-domain check: the brand rename is a
 * cross-cutting fact. I2 Nova push templates, Help Center articles, V-Rank prize
 * names and storefront copy each grew their own accidental tolerance for the old
 * token, so an operator could publish content that still told users the product
 * was called something else. Each domain already had a completeness gate for its
 * own shape (locale coverage, placeholder parity); none of them looked at brand.
 *
 * <p>Detection is deliberately a whole-token match. {@code Nexion} must not fire
 * on unrelated substrings, and the current brand {@code NexGrid} must never match.
 * The token list is the only place a retired brand is declared; callers derive
 * both the SQL predicate and the Java verdict from it so a domain can never
 * disagree with the gate about the vocabulary.
 */
public final class RetiredBrandGate {

    private RetiredBrandGate() {
    }

    /**
     * Retired brand tokens. Whole-token, case-insensitive. Kept as a regex rather
     * than a bare list so {@code Nexion}-prefixed product names are caught while
     * words that merely contain the letters are not.
     */
    public static final String RETIRED_BRAND_REGEX = "(^|[^[:alnum:]])nexion([^[:alnum:]]|$)";

    private static final Pattern RETIRED_BRAND = Pattern.compile(RETIRED_BRAND_REGEX, Pattern.CASE_INSENSITIVE);

    /** The brand every user-facing string must use. */
    public static final String CURRENT_BRAND = "NexGrid";

    /** SQL predicate over one text column/expression, for MyBatis interpolation. */
    public static String matchesSql(String expression) {
        return "REGEXP_LIKE(COALESCE(" + expression + ",''), '" + RETIRED_BRAND_REGEX + "', 'i')";
    }

    /** True when the value still carries a retired brand token. */
    public static boolean carriesRetiredBrand(String value) {
        return value != null && RETIRED_BRAND.matcher(value).find();
    }

    /** True when any supplied value still carries a retired brand token. */
    public static boolean anyCarriesRetiredBrand(String... values) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (carriesRetiredBrand(value)) {
                return true;
            }
        }
        return false;
    }

    /** Operator-facing reason code for a rejected publish. */
    public static final String REASON = "RETIRED_BRAND_IN_PUBLISHED_COPY";

    /** Human-readable detail naming the retired token, for audit detail maps. */
    public static String detail() {
        return "copy still carries the retired brand 'Nexion'; use '" + CURRENT_BRAND + "'";
    }
}

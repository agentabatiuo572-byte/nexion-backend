package ffdd.opsconsole.shared.i18n;

import java.util.regex.Pattern;

public final class AuthoringWhitespace {
    // ECMAScript trim: ASCII whitespace, Space_Separator, line separators and BOM.
    private static final String WHITESPACE = "[\\s\\p{Zs}\\u2028\\u2029\\uFEFF]";
    private static final Pattern BLANK = Pattern.compile(WHITESPACE + "*");
    private static final Pattern EDGE = Pattern.compile("\\A" + WHITESPACE + "+|" + WHITESPACE + "+\\z");

    private AuthoringWhitespace() {}

    public static boolean isBlank(String value) {
        return value == null || BLANK.matcher(value).matches();
    }

    public static String trim(String value) {
        return EDGE.matcher(value).replaceAll("");
    }
}

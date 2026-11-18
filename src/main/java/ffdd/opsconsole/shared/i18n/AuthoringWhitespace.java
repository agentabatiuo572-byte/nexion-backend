package ffdd.opsconsole.shared.i18n;

import java.util.regex.Pattern;

public final class AuthoringWhitespace {
    // ECMAScript trim: ASCII whitespace, Space_Separator, line separators and BOM.
    private static final Pattern BLANK = Pattern.compile("[\\s\\p{Zs}\\u2028\\u2029\\uFEFF]*");

    private AuthoringWhitespace() {}

    public static boolean isBlank(String value) {
        return value == null || BLANK.matcher(value).matches();
    }
}

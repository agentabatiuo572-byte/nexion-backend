package ffdd.opsconsole.shared.i18n;

import ffdd.opsconsole.shared.exception.AppTextChineseNotAllowedException;

public final class AppDisplayText {
    private AppDisplayText() {}

    public static void requireNoHan(String field, String value) {
        if (value != null && value.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
            throw new AppTextChineseNotAllowedException(field);
        }
    }
}

package ffdd.opsconsole.shared.i18n;

import java.util.List;
import java.util.Locale;

public final class InterfaceLanguages {
    public static final List<String> BASE = List.of("zh", "en", "vi");
    public static final List<String> CODES = List.of("zh", "en", "vi", "id", "ms", "fil", "ru", "kk", "uz");

    private InterfaceLanguages() {}

    /** Stored profiles and template locales can contain regional tags such as en-US. */
    public static String baseCode(String raw) {
        if (raw == null) return "en";
        String tag = raw.trim().toLowerCase(Locale.ROOT);
        if (!tag.matches("[a-z]{2,3}(?:-[a-z0-9]{2,8})*")) return "en";
        int separator = tag.indexOf('-');
        String base = separator < 0 ? tag : tag.substring(0, separator);
        return CODES.contains(base) ? base : "en";
    }

    public static boolean isAdditional(String code) {
        return code != null && CODES.contains(code) && !BASE.contains(code);
    }
}

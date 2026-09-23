package ffdd.opsconsole.content.domain;

import java.util.Locale;
import ffdd.opsconsole.shared.canonical.RetiredBrandGate;
import org.springframework.util.StringUtils;

/** Content check shared by I6 publication and integrity scans. */
public final class I18nCopyQuality {
    private I18nCopyQuality() {}

    public static boolean isPlaceholderText(String value) {
        if (!StringUtils.hasText(value)) return false;
        String bare = value.replaceAll("\\s+", "");
        // Short abbreviations such as AAA are valid copy; flag sustained filler only.
        if (bare.matches("(.)\\1{4,}")) return true;
        String compact = bare.replaceAll("\\p{Punct}+", "").toLowerCase(Locale.ROOT);
        return !compact.isEmpty() && compact.matches("(test|todo|tbd|placeholder|dummy|样例|测试|占位)+");
    }

    public static String publishError(String zh, String en, String vi) {
        if (!StringUtils.hasText(zh) || !StringUtils.hasText(en) || !StringUtils.hasText(vi)) {
            return "I18N_COPY_REQUIRED";
        }
        if (isPlaceholderText(zh) || isPlaceholderText(en) || isPlaceholderText(vi)) {
            return "I18N_PLACEHOLDER_TEXT_FORBIDDEN";
        }
        return RetiredBrandGate.anyCarriesRetiredBrand(zh, en, vi) ? RetiredBrandGate.REASON : null;
    }
}

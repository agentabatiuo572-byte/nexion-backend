package ffdd.opsconsole.content.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ffdd.opsconsole.shared.canonical.RetiredBrandGate;
import org.springframework.util.StringUtils;

/** Content check shared by I6 publication and integrity scans. */
public final class I18nCopyQuality {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[a-zA-Z0-9_.-]+}");
    private I18nCopyQuality() {}

    public static boolean isComplete(I18nMessagePairView message) {
        return message != null && "published".equals(message.status())
                && publishError(message.zh(), message.en(), message.vi()) == null
                && placeholders(message.zh()).equals(placeholders(message.en()))
                && placeholders(message.zh()).equals(placeholders(message.vi()));
    }

    public static boolean placeholdersMatch(String english, java.util.Map<String, String> translations) {
        var expected = placeholders(english);
        return translations.values().stream().allMatch(value -> expected.equals(placeholders(value)));
    }

    public static List<String> integrityIssueCodes(I18nMessagePairView message) {
        var issues = new ArrayList<String>();
        boolean hasZh = hasCopyText(message.zh());
        boolean hasEn = hasCopyText(message.en());
        boolean hasVi = hasCopyText(message.vi());
        if (!hasZh) issues.add("missing-zh");
        if (!hasEn) issues.add("missing-en");
        if (!hasVi) issues.add("missing-vi");
        if (hasZh && hasEn && hasVi) {
            Set<String> zh = placeholders(message.zh());
            if (!zh.equals(placeholders(message.en())) || !zh.equals(placeholders(message.vi()))) {
                issues.add("placeholder");
            }
        }
        if (isPlaceholderText(message.zh()) || isPlaceholderText(message.en()) || isPlaceholderText(message.vi())) {
            issues.add("placeholder-text");
        }
        if (RetiredBrandGate.anyCarriesRetiredBrand(message.zh(), message.en(), message.vi())) {
            issues.add("retired-brand");
        }
        return issues;
    }

    private static Set<String> placeholders(String value) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        Set<String> tokens = new TreeSet<>();
        while (matcher.find()) tokens.add(matcher.group());
        return tokens;
    }

    public static boolean isPlaceholderText(String value) {
        if (!StringUtils.hasText(value)) return false;
        // ECMAScript whitespace matches the PC editor, including NBSP and BOM, but not NEL.
        String bare = value.replaceAll("[\\s\\p{Z}\\uFEFF]+", "");
        // Short abbreviations such as AAA are valid copy; flag sustained filler only.
        if (bare.matches("(.)\\1{4,}")) return true;
        String compact = bare.replaceAll("\\p{Punct}+", "").toLowerCase(Locale.ROOT);
        return !compact.isEmpty() && compact.matches("(test|todo|tbd|placeholder|dummy|样例|测试|占位)+");
    }

    public static String publishError(String zh, String en, String vi) {
        if (!hasCopyText(zh) || !hasCopyText(en) || !hasCopyText(vi)) {
            return "I18N_COPY_REQUIRED";
        }
        if (isPlaceholderText(zh) || isPlaceholderText(en) || isPlaceholderText(vi)) {
            return "I18N_PLACEHOLDER_TEXT_FORBIDDEN";
        }
        return RetiredBrandGate.anyCarriesRetiredBrand(zh, en, vi) ? RetiredBrandGate.REASON : null;
    }

    private static boolean hasCopyText(String value) {
        return value != null && !value.matches("[\\p{javaWhitespace}\\p{Z}\\uFEFF]*");
    }
}

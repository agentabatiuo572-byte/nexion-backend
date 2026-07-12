package ffdd.opsconsole.content.application;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

/** Canonicalizes legacy calling codes and localized country labels to ISO-3166 alpha-2. */
public final class CountryCodeNormalizer {
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("84", "VN"), Map.entry("VIETNAM", "VN"), Map.entry("VIET NAM", "VN"),
            Map.entry("VIET NAM ", "VN"), Map.entry("越南", "VN"),
            Map.entry("86", "CN"), Map.entry("CHINA", "CN"), Map.entry("中国", "CN"),
            Map.entry("852", "HK"), Map.entry("HONG KONG", "HK"), Map.entry("中国香港", "HK"),
            Map.entry("65", "SG"), Map.entry("SINGAPORE", "SG"), Map.entry("新加坡", "SG"),
            Map.entry("44", "GB"), Map.entry("UK", "GB"), Map.entry("UNITED KINGDOM", "GB"), Map.entry("英国", "GB"));
    private static final Map<String, List<String>> LEGACY_CODES = Map.of(
            "VN", List.of("84", "+84", "VIET NAM", "VIETNAM", "VIỆT NAM", "越南"),
            "CN", List.of("86", "+86", "CHINA", "中国"),
            "HK", List.of("852", "+852", "HONG KONG", "中国香港"),
            "SG", List.of("65", "+65", "SINGAPORE", "新加坡"),
            "GB", List.of("44", "+44", "UK", "UNITED KINGDOM", "英国"));

    private CountryCodeNormalizer() {
    }

    public static String normalize(String value) {
        if (!StringUtils.hasText(value)) return "";
        String raw = value.trim().toUpperCase(Locale.ROOT);
        String withoutPlus = raw.startsWith("+") ? raw.substring(1) : raw;
        if (withoutPlus.matches("^[0-9]{1,4}$")) {
            return ALIASES.getOrDefault(withoutPlus, "");
        }
        if (raw.matches("^[A-Z]{2}$")) return raw;
        String ascii = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("\\s+", " ");
        return ALIASES.getOrDefault(raw, ALIASES.getOrDefault(ascii, ""));
    }

    public static List<String> aliasesFor(List<String> countries) {
        Set<String> values = new LinkedHashSet<>();
        for (String country : countries == null ? List.<String>of() : countries) {
            String normalized = normalize(country);
            if (!StringUtils.hasText(normalized)) continue;
            values.add(normalized);
            values.addAll(LEGACY_CODES.getOrDefault(normalized, List.of()));
        }
        return new ArrayList<>(values);
    }
}

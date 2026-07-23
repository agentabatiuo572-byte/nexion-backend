package ffdd.opsconsole.risk.application;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Typed parser for the K3 address-source rule embedded in condition_text. */
public record K3AddressReputationRuleConfig(Source source, BigDecimal lowThreshold) {
    public enum Source {
        INTERNAL("internal"),
        THIRD_PARTY("third-party"),
        COMBINED("combined");

        private final String value;

        Source(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public static final BigDecimal DEFAULT_LOW_THRESHOLD = new BigDecimal("0.4");
    private static final Pattern CANONICAL = Pattern.compile(
            "^addressReputationSource=(internal|third-party|combined)"
                    + "(?:\\s*;\\s*addressReputationLowThreshold=([^\\s;]+))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Map<String, Source> LEGACY_SOURCES = Map.ofEntries(
            Map.entry("内部", Source.INTERNAL),
            Map.entry("内部黑名单", Source.INTERNAL),
            Map.entry("黑名单 / 低信誉地址", Source.INTERNAL),
            Map.entry("第三方", Source.THIRD_PARTY),
            Map.entry("链上信誉", Source.THIRD_PARTY),
            Map.entry("第三方链上信誉", Source.THIRD_PARTY),
            Map.entry("组合", Source.COMBINED),
            Map.entry("内部黑名单 + 链上信誉", Source.COMBINED),
            Map.entry("内部 + 第三方信誉", Source.COMBINED));

    public static Optional<K3AddressReputationRuleConfig> parse(String value) {
        String normalized = normalizeLegacySpacing(value);
        Source legacy = LEGACY_SOURCES.get(normalized);
        if (legacy != null) {
            return Optional.of(new K3AddressReputationRuleConfig(legacy, DEFAULT_LOW_THRESHOLD));
        }
        Matcher matcher = CANONICAL.matcher(normalized);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        Source source = switch (matcher.group(1).toLowerCase(Locale.ROOT)) {
            case "internal" -> Source.INTERNAL;
            case "third-party" -> Source.THIRD_PARTY;
            case "combined" -> Source.COMBINED;
            default -> null;
        };
        BigDecimal threshold = matcher.group(2) == null
                ? DEFAULT_LOW_THRESHOLD : decimal(matcher.group(2));
        if (source == null || threshold == null
                || threshold.compareTo(BigDecimal.ZERO) < 0
                || threshold.compareTo(BigDecimal.ONE) > 0) {
            return Optional.empty();
        }
        return Optional.of(new K3AddressReputationRuleConfig(source, threshold));
    }

    public boolean usesThirdParty() {
        return source == Source.THIRD_PARTY || source == Source.COMBINED;
    }

    public String canonicalCondition() {
        return "addressReputationSource=" + source.value()
                + "; addressReputationLowThreshold=" + lowThreshold.stripTrailingZeros().toPlainString();
    }

    private static String normalizeLegacySpacing(String value) {
        return (value == null ? "" : value.trim())
                .replace("／", "/")
                .replace("＋", "+")
                .replaceAll("\\s*/\\s*", " / ")
                .replaceAll("\\s*\\+\\s*", " + ")
                .trim();
    }

    private static BigDecimal decimal(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}

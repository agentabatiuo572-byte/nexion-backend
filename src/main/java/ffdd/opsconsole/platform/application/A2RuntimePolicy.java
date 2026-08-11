package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.platform.domain.PlatformConfigItem;
import ffdd.opsconsole.platform.domain.PlatformConfigRepository;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Single fail-closed runtime authority for A2 governance parameters and reason validation. */
@Service
@RequiredArgsConstructor
public class A2RuntimePolicy {
    public static final String REASON_MIN_KEY = "admin.a2.reason_min_chars";
    public static final String RETENTION_KEY = "admin.a2.retention_months";
    public static final String SCHEMA_VERSION_KEY = "admin.a2.schema_version";
    /** Versions whose envelope fields are implemented and understood by this deployment. */
    private static final java.util.Map<String, Integer> SUPPORTED_SCHEMA_VERSIONS = java.util.Map.of(
            "v3", 3,
            "v4", 4);

    private final PlatformConfigRepository repository;

    public int reasonMinChars() {
        return parseReasonMinChars(requiredValue(REASON_MIN_KEY, "A2_REASON_POLICY_UNAVAILABLE"));
    }

    /** True only for a legacy install where the authoritative row is genuinely absent. Invalid rows never bootstrap-bypass. */
    public boolean reasonPolicyMissing() {
        return repository.findActiveByKey(REASON_MIN_KEY)
                .map(PlatformConfigItem::configValue)
                .filter(StringUtils::hasText)
                .isEmpty();
    }

    public static int parseReasonMinChars(String configured) {
        if (!StringUtils.hasText(configured)) throw new BizException(503, "A2_REASON_POLICY_UNAVAILABLE");
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(\\d{1,3})\\s*(?:字符|字)?$")
                .matcher(configured.trim());
        if (!matcher.matches()) throw new BizException(503, "A2_REASON_POLICY_INVALID");
        try {
            int parsed = Integer.parseInt(matcher.group(1));
            if (parsed < 8 || parsed > 200) throw new NumberFormatException("range");
            return parsed;
        } catch (NumberFormatException ex) {
            throw new BizException(503, "A2_REASON_POLICY_INVALID");
        }
    }

    public int retentionMonths() {
        return boundedInteger(RETENTION_KEY, "^(\\d{1,3})\\s*(?:个月|月|months?)?$", 13, 36,
                "A2_RETENTION_POLICY_UNAVAILABLE", "A2_RETENTION_POLICY_INVALID");
    }

    public String schemaVersion() {
        String value = requiredValue(SCHEMA_VERSION_KEY, "A2_SCHEMA_VERSION_UNAVAILABLE");
        try {
            return normalizeSupportedSchemaVersion(value);
        } catch (IllegalArgumentException ex) {
            throw unavailable("A2_SCHEMA_VERSION_INVALID");
        }
    }

    /**
     * A schema label is not free text: audit readers can only interpret versions registered here.
     * The numeric order is used by the command boundary to prohibit rollback and no-op rewrites.
     */
    public static String normalizeSupportedSchemaVersion(String value) {
        String normalized = value == null ? "" : value.trim()
                .replaceFirst("(?i)^统一\\s*schema\\s*·?\\s*", "")
                .trim();
        if (!SUPPORTED_SCHEMA_VERSIONS.containsKey(normalized)) {
            throw new IllegalArgumentException("A2_SCHEMA_VERSION_UNSUPPORTED");
        }
        return normalized;
    }

    public static int schemaVersionOrder(String value) {
        return SUPPORTED_SCHEMA_VERSIONS.get(normalizeSupportedSchemaVersion(value));
    }

    /** Parses a version-shaped request so historical rollback attempts receive a conflict, not a format error. */
    public static int numericSchemaVersionOrder(String value) {
        String normalized = value == null ? "" : value.trim()
                .replaceFirst("(?i)^统一\\s*schema\\s*·?\\s*", "")
                .trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^v([1-9]\\d*)$").matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("A2_SCHEMA_VERSION_UNSUPPORTED");
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("A2_SCHEMA_VERSION_UNSUPPORTED");
        }
    }

    public ReasonPolicyView reasonPolicy() {
        return new ReasonPolicyView(reasonMinChars(), 200, REASON_MIN_KEY);
    }

    public void validateReason(String reason) {
        validateReason(reason, reasonMinChars());
    }

    public static void validateReason(String reason, int minimum) {
        int visible = visibleCodePointCount(reason);
        if (visible < minimum) throw new BizException(422, "REASON_TOO_SHORT_MIN_" + minimum);
        if (visible > 200) throw new BizException(422, "REASON_TOO_LONG_MAX_200");
    }

    /** Counts user-visible code points; whitespace, controls and zero-width formatting cannot satisfy the policy. */
    public static int visibleCodePointCount(String value) {
        if (value == null || value.isEmpty()) return 0;
        int visible = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)
                    || type == Character.FORMAT || type == Character.NON_SPACING_MARK
                    || type == Character.ENCLOSING_MARK || type == Character.COMBINING_SPACING_MARK) {
                continue;
            }
            visible++;
        }
        return visible;
    }

    private int boundedInteger(
            String key, String format, int min, int max, String missingCode, String invalidCode) {
        String value = requiredValue(key, missingCode);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(format, java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(value.trim());
        if (!matcher.matches()) throw unavailable(invalidCode);
        try {
            int parsed = Integer.parseInt(matcher.group(1));
            if (parsed < min || parsed > max) throw unavailable(invalidCode);
            return parsed;
        } catch (NumberFormatException ex) {
            throw unavailable(invalidCode);
        }
    }

    private String requiredValue(String key, String code) {
        String value = repository.findActiveByKey(key)
                .map(PlatformConfigItem::configValue)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .orElseThrow(() -> unavailable(code));
        return value.toLowerCase(Locale.ROOT).startsWith("统一 schema")
                ? value.replaceFirst("(?i)^统一\\s*schema\\s*·?\\s*", "").trim()
                : value;
    }

    private BizException unavailable(String code) {
        return new BizException(503, code);
    }

    public record ReasonPolicyView(int minChars, int maxChars, String sourceKey) {}
}

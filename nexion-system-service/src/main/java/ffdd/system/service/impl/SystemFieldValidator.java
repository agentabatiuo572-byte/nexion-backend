package ffdd.system.service.impl;

import ffdd.common.exception.BizException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

final class SystemFieldValidator {
    static final int ACTIVE = 1;
    private static final int MAX_LIST_LIMIT = 200;
    private static final int MAX_CONTENT_LENGTH = 65535;
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]+$");
    private static final Pattern LOCALE_PATTERN = Pattern.compile("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}$");

    private SystemFieldValidator() {
    }

    static String normalizeCode(String value, String fieldName, int maxLength) {
        String normalized = trimToNull(value);
        if (!StringUtils.hasText(normalized)
                || normalized.length() > maxLength
                || !CODE_PATTERN.matcher(normalized).matches()) {
            throw new BizException("Invalid " + fieldName);
        }
        return normalized;
    }

    static String normalizeLocale(String locale) {
        String normalized = trimToNull(locale);
        if (!StringUtils.hasText(normalized)) {
            throw new BizException("Invalid locale");
        }
        normalized = normalized.replace('_', '-');
        if (normalized.length() > 16 || !LOCALE_PATTERN.matcher(normalized).matches()) {
            throw new BizException("Invalid locale");
        }
        String[] parts = normalized.split("-");
        StringBuilder canonical = new StringBuilder(parts[0].toLowerCase(Locale.ROOT));
        for (int i = 1; i < parts.length; i++) {
            canonical.append('-');
            String part = parts[i];
            canonical.append(part.length() == 2 ? part.toUpperCase(Locale.ROOT) : part.toLowerCase(Locale.ROOT));
        }
        return canonical.toString();
    }

    static String requireText(String value, String fieldName, int maxLength) {
        String normalized = trimToNull(value);
        if (!StringUtils.hasText(normalized)) {
            throw new BizException(fieldName + " is required");
        }
        if (normalized.length() > maxLength) {
            throw new BizException(fieldName + " length must be <= " + maxLength);
        }
        return normalized;
    }

    static String normalizeContent(String content) {
        if (content == null) {
            return null;
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new BizException("content length must be <= " + MAX_CONTENT_LENGTH);
        }
        return content;
    }

    static Integer normalizeStatus(Integer status) {
        if (status == null) {
            return ACTIVE;
        }
        if (status != 0 && status != 1) {
            throw new BizException("Invalid status");
        }
        return status;
    }

    static Integer normalizeSortOrder(Integer sortOrder) {
        if (sortOrder == null) {
            return 0;
        }
        if (sortOrder < 0 || sortOrder > 1_000_000) {
            throw new BizException("Invalid sortOrder");
        }
        return sortOrder;
    }

    static int normalizeLimit(int limit) {
        if (limit < 1) {
            return 20;
        }
        return Math.min(limit, MAX_LIST_LIMIT);
    }

    static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}

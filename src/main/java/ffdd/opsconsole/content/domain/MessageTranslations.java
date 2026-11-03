package ffdd.opsconsole.content.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.util.StringUtils;

/** The optional six-language part of a versioned content record. */
public final class MessageTranslations {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final Map<String, String> LOCALES = Map.of(
            "id", "id-ID", "ms", "ms-MY", "fil", "fil-PH",
            "ru", "ru-RU", "kk", "kk-KZ", "uz", "uz-UZ");

    private MessageTranslations() {}

    public static <T> Map<String, T> ordered(Map<String, T> values) {
        return values == null ? null : java.util.Collections.unmodifiableMap(new TreeMap<>(values));
    }

    public static Map<String, String> clean(Map<String, String> translations) {
        Map<String, String> result = new TreeMap<>();
        if (translations != null) translations.forEach((code, value) -> {
            if (StringUtils.hasText(value)) result.put(code, value.trim());
        });
        return result;
    }

    public static Map<String, String> read(String json) {
        if (!StringUtils.hasText(json)) return Map.of();
        try {
            return JSON.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("INVALID_CONTENT_TRANSLATIONS", e);
        }
    }

    public static String json(Map<String, String> translations) {
        try {
            return JSON.writeValueAsString(clean(translations));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("INVALID_CONTENT_TRANSLATIONS", e);
        }
    }
}

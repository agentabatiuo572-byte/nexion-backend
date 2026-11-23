package ffdd.opsconsole.shared.i18n;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.util.StringUtils;

/**
 * Optional localized product subtitles. {@code nx_product.tagline} remains the
 * English base value; this map only carries the other interface languages.
 */
public final class ProductTaglines {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ProductTaglines() {}

    public static Map<String, String> clean(Map<String, String> values) {
        if (values == null) return null;
        Map<String, String> result = new TreeMap<>();
        values.forEach((key, value) -> {
            if (StringUtils.hasText(key) && !AuthoringWhitespace.isBlank(value)) {
                result.put(AuthoringWhitespace.trim(key), AuthoringWhitespace.trim(value));
            }
        });
        return Collections.unmodifiableMap(result);
    }

    /** Writes a validated object node; non-text values are rejected by the shared boundary check. */
    public static String jsonFromNode(JsonNode values) {
        if (values == null) return null;
        if (!values.isObject()) throw new IllegalArgumentException("INVALID_PRODUCT_TAGLINE_TRANSLATIONS");
        Map<String, String> cleaned = new TreeMap<>();
        values.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) throw new IllegalArgumentException("INVALID_PRODUCT_TAGLINE_TRANSLATIONS");
            String value = AuthoringWhitespace.trim(entry.getValue().textValue());
            if (!AuthoringWhitespace.isBlank(value)) cleaned.put(entry.getKey(), value);
        });
        return json(cleaned);
    }

    public static String json(Map<String, String> values) {
        Map<String, String> cleaned = clean(values);
        if (cleaned == null) return null;
        try {
            return JSON.writeValueAsString(cleaned);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("INVALID_PRODUCT_TAGLINE_TRANSLATIONS", ex);
        }
    }

    /** Malformed optional metadata must not take the whole catalogue down. */
    public static Map<String, String> read(String json) {
        if (!StringUtils.hasText(json)) return Map.of();
        try {
            Map<String, String> parsed = clean(JSON.readValue(json, new TypeReference<Map<String, String>>() {}));
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException | RuntimeException ex) {
            return Map.of();
        }
    }

    public static String resolve(String base, String json, String locale) {
        String code = InterfaceLanguages.baseCode(locale);
        if ("en".equals(code)) return base;
        String translated = read(json).get(code);
        return StringUtils.hasText(translated) ? translated : base;
    }
}

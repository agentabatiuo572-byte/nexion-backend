package ffdd.opsconsole.shared.audit;


import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Array;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuditLogSanitizer {
    private static final String REDACTED = "[REDACTED]";
    private static final int MAX_DETAIL_JSON_LENGTH = 4096;
    private static final List<String> SENSITIVE_KEY_PARTS = List.of(
            "password", "secret", "token", "signature", "authorization", "credential", "privatekey",
            "private_key", "accesskey", "access_key", "secretkey", "secret_key", "documentobject",
            "objectkey", "object_key", "rawbody", "raw_body");

    private final ObjectMapper objectMapper;

    public String toSafeJson(Object detail) {
        if (detail == null) {
            return null;
        }
        try {
            return clip(objectMapper.writeValueAsString(sanitizeValue(detail)));
        } catch (JsonProcessingException ex) {
            return "{\"serialization\":\"failed\"}";
        }
    }

    @SuppressWarnings("unchecked")
    private Object sanitizeValue(Object value) {
        if (value == null
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof CharSequence) {
            return value;
        }
        if (value instanceof TemporalAccessor || value instanceof Enum<?>) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                sanitized.put(key, isSensitiveKey(key) ? REDACTED : sanitizeValue(entry.getValue()));
            }
            return sanitized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : iterable) {
                sanitized.add(sanitizeValue(item));
            }
            return sanitized;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> sanitized = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                sanitized.add(sanitizeValue(Array.get(value, i)));
            }
            return sanitized;
        }
        return sanitizeValue(objectMapper.convertValue(value, Map.class));
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
        for (String sensitivePart : SENSITIVE_KEY_PARTS) {
            String normalizedSensitivePart = sensitivePart.replace("_", "").toLowerCase(Locale.ROOT);
            if (normalized.contains(normalizedSensitivePart)) {
                return true;
            }
        }
        return false;
    }

    private String clip(String json) {
        if (json == null || json.length() <= MAX_DETAIL_JSON_LENGTH) {
            return json;
        }
        Map<String, Object> clipped = new LinkedHashMap<>();
        clipped.put("truncated", true);
        clipped.put("originalLength", json.length());
        clipped.put("preview", json.substring(0, Math.min(3500, json.length())));
        try {
            return objectMapper.writeValueAsString(clipped);
        } catch (JsonProcessingException ex) {
            return "{\"truncated\":true,\"serialization\":\"failed\"}";
        }
    }
}

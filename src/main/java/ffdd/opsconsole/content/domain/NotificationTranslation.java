package ffdd.opsconsole.content.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public record NotificationTranslation(String title, String body, String ctaLabel) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public NotificationTranslation {
        title = title == null ? "" : title.trim();
        body = body == null ? "" : body.trim();
        ctaLabel = ctaLabel == null ? "" : ctaLabel.trim();
    }

    public static String json(Map<String, NotificationTranslation> translations) {
        if (translations == null) return null;
        try {
            return JSON.writeValueAsString(translations);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("NOTIFICATION_TRANSLATIONS_INVALID", e);
        }
    }

    public static Map<String, NotificationTranslation> read(String json) {
        if (json == null) return Map.of();
        try {
            return JSON.readValue(json, new TypeReference<Map<String, NotificationTranslation>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("NOTIFICATION_TRANSLATIONS_INVALID", e);
        }
    }
}

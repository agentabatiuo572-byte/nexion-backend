package ffdd.opsconsole.content.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public record SupportFaqTranslation(String question, String answer) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public SupportFaqTranslation {
        question = question == null ? "" : question.trim();
        answer = answer == null ? "" : answer.trim();
    }

    public static String json(Map<String, SupportFaqTranslation> translations) {
        if (translations == null) return null;
        try {
            return JSON.writeValueAsString(translations);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("SUPPORT_FAQ_TRANSLATION_INVALID", exception);
        }
    }

    public static Map<String, SupportFaqTranslation> read(String value) {
        if (value == null) return Map.of();
        try {
            return JSON.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("SUPPORT_FAQ_TRANSLATION_INVALID", exception);
        }
    }
}

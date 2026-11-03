package ffdd.opsconsole.content.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.annotations.AutomapConstructor;

public record SupportFaqView(
        String id,
        String category,
        String question,
        String answer,
        String status,
        String surface,
        String language,
        Integer sortOrder,
        Integer version,
        LocalDateTime updatedAt,
        @JsonIgnore String translationsJson) {
    @AutomapConstructor
    public SupportFaqView {}

    public SupportFaqView(String id, String category, String question, String answer, String status,
            String surface, String language, Integer sortOrder, Integer version, LocalDateTime updatedAt) {
        this(id, category, question, answer, status, surface, language, sortOrder, version, updatedAt, null);
    }

    @JsonProperty("translations")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, SupportFaqTranslation> translations() { return SupportFaqTranslation.read(translationsJson); }

    public SupportFaqView forLanguage(String code) {
        SupportFaqTranslation translation = "en-US".equalsIgnoreCase(language) ? translations().get(code) : null;
        return new SupportFaqView(id, category, translation == null ? question : translation.question(),
                translation == null ? answer : translation.answer(), status, surface,
                translation == null ? language : code, sortOrder, version, updatedAt);
    }
}

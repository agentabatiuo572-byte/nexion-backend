package ffdd.opsconsole.content.dto;

import ffdd.opsconsole.content.domain.SupportFaqTranslation;
import java.util.Map;

public record SupportFaqUpsertRequest(
        String category,
        String surface,
        String question,
        String answer,
        String status,
        String language,
        Integer sortOrder,
        String expectedStatus,
        Integer expectedVersion,
        String operator,
        String reason, Map<String, SupportFaqTranslation> translations) {
    public SupportFaqUpsertRequest(String category, String surface, String question, String answer, String status,
            String language, Integer sortOrder, String expectedStatus, Integer expectedVersion, String operator, String reason) {
        this(category, surface, question, answer, status, language, sortOrder, expectedStatus, expectedVersion, operator, reason, null);
    }
    public SupportFaqUpsertRequest(
            String category, String surface, String question, String answer, String status,
            String language, Integer sortOrder, String operator, String reason) {
        this(category, surface, question, answer, status, language, sortOrder, null, null, operator, reason);
    }
}

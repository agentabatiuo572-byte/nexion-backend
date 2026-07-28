package ffdd.opsconsole.content.dto;

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
        String reason) {
    public SupportFaqUpsertRequest(
            String category, String surface, String question, String answer, String status,
            String language, Integer sortOrder, String operator, String reason) {
        this(category, surface, question, answer, status, language, sortOrder, null, null, operator, reason);
    }
}

package ffdd.opsconsole.content.dto;

public record SupportFaqUpsertRequest(
        String category,
        String surface,
        String question,
        String answer,
        String status,
        String operator,
        String reason) {
}

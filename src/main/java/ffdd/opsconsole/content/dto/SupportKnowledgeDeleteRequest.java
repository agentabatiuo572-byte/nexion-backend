package ffdd.opsconsole.content.dto;

public record SupportKnowledgeDeleteRequest(
        String expectedStatus,
        Integer expectedVersion,
        String operator,
        String reason) {
    public SupportKnowledgeDeleteRequest(String operator, String reason) {
        this(null, null, operator, reason);
    }
}

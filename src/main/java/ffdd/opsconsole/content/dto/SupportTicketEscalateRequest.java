package ffdd.opsconsole.content.dto;

public record SupportTicketEscalateRequest(
        String ownerAgentId,
        String ownerAgentName,
        String operator,
        String reason,
        String expectedStatus,
        Long expectedVersion) {
    public SupportTicketEscalateRequest(
            String ownerAgentId,
            String ownerAgentName,
            String operator,
            String reason) {
        this(ownerAgentId, ownerAgentName, operator, reason, null, null);
    }
}

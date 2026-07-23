package ffdd.opsconsole.content.dto;

public record SupportTicketEscalateRequest(
        String ownerAgentId,
        String ownerAgentName,
        String operator,
        String reason) {
}

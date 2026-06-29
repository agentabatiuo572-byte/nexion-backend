package ffdd.opsconsole.content.domain;

public record AdvisorRoutingDecision(
        String targetType,
        String targetId,
        String targetName,
        Long agentAdminId,
        boolean dedicated,
        boolean fallbackTicket,
        String reason) {
}

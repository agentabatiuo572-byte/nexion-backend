package ffdd.opsconsole.content.dto;

public record SupportAgentAssignmentRequest(
        Long userId,
        String operator,
        String reason) {
}

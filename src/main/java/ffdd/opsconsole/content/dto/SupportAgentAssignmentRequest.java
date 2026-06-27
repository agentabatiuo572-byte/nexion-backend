package ffdd.opsconsole.content.dto;

public record SupportAgentAssignmentRequest(
        Long userId,
        String assignmentType,
        String operator,
        String reason) {
}

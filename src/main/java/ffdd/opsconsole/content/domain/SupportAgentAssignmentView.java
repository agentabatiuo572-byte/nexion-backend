package ffdd.opsconsole.content.domain;

public record SupportAgentAssignmentView(
        Long id,
        Long agentAdminId,
        Long userId,
        String userNo,
        String nickname,
        String assignmentType,
        String status,
        String startsAt,
        String endsAt,
        String operator,
        String reason,
        String updatedAt) {
}

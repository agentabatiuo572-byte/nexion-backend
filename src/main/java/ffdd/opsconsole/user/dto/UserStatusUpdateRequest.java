package ffdd.opsconsole.user.dto;

public record UserStatusUpdateRequest(
        String status,
        String reason,
        String operator) {
}

package ffdd.opsconsole.user.dto;

public record UserKycStatusUpdateRequest(
        String status,
        String reason,
        String operator) {
}

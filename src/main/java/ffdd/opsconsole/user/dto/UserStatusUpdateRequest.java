package ffdd.opsconsole.user.dto;

public record UserStatusUpdateRequest(
        String status,
        String reasonCode,
        String reason,
        String operator) {

    public UserStatusUpdateRequest(String status, String reason, String operator) {
        this(status, null, reason, operator);
    }
}

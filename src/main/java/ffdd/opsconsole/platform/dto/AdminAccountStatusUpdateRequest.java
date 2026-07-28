package ffdd.opsconsole.platform.dto;

public record AdminAccountStatusUpdateRequest(
        String status,
        String reason,
        String operator,
        String expectedVersion) {
    public AdminAccountStatusUpdateRequest(String status, String reason, String operator) {
        this(status, reason, operator, null);
    }
}

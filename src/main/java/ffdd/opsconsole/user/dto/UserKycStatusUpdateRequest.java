package ffdd.opsconsole.user.dto;

public record UserKycStatusUpdateRequest(
        String status,
        String expectedState,
        String reasonCode,
        String reason,
        String evidenceRef,
        String operator) {
    public UserKycStatusUpdateRequest(String status, String reason, String operator) {
        this(status, null, null, reason, null, operator);
    }
}

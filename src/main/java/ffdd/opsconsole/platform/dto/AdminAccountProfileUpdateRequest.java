package ffdd.opsconsole.platform.dto;

public record AdminAccountProfileUpdateRequest(
        String username,
        String displayName,
        String email,
        String reason,
        String operator,
        String expectedVersion) {
    public AdminAccountProfileUpdateRequest(
            String username,
            String displayName,
            String email,
            String reason,
            String operator) {
        this(username, displayName, email, reason, operator, null);
    }
}

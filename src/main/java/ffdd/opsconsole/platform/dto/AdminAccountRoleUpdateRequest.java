package ffdd.opsconsole.platform.dto;

public record AdminAccountRoleUpdateRequest(
        String role,
        String reason,
        String operator,
        String expectedVersion) {
    public AdminAccountRoleUpdateRequest(String role, String reason, String operator) {
        this(role, reason, operator, null);
    }
}

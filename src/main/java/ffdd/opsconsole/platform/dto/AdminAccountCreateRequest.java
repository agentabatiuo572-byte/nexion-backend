package ffdd.opsconsole.platform.dto;

public record AdminAccountCreateRequest(
        String displayName,
        String email,
        String role,
        String deliver,
        String reason,
        String operator,
        String initialPassword) {
    public AdminAccountCreateRequest(
            String displayName,
            String email,
            String role,
            String deliver,
            String reason,
            String operator) {
        this(displayName, email, role, deliver, reason, operator, null);
    }
}

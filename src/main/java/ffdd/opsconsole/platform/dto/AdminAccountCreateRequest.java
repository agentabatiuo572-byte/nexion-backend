package ffdd.opsconsole.platform.dto;

public record AdminAccountCreateRequest(
        String username,
        String displayName,
        String email,
        String role,
        String ignoredCredentialDelivery,
        String reason,
        String operator,
        String initialPassword) {
    public AdminAccountCreateRequest(
            String displayName,
            String email,
            String role,
            String ignoredCredentialDelivery,
            String reason,
            String operator) {
        this(null, displayName, email, role, ignoredCredentialDelivery, reason, operator, null);
    }

    public AdminAccountCreateRequest(
            String displayName,
            String email,
            String role,
            String ignoredCredentialDelivery,
            String reason,
            String operator,
            String initialPassword) {
        this(null, displayName, email, role, ignoredCredentialDelivery, reason, operator, initialPassword);
    }
}

package ffdd.opsconsole.platform.dto;

public record AdminAccountRoleUpdateRequest(
        String role,
        String reason,
        String operator) {
}

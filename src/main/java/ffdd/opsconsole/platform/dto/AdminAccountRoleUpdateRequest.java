package ffdd.opsconsole.platform.dto;

public record AdminAccountRoleUpdateRequest(
        String role,
        String tier,
        String reason,
        String operator) {
}

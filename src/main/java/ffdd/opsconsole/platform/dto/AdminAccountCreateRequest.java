package ffdd.opsconsole.platform.dto;

public record AdminAccountCreateRequest(
        String displayName,
        String email,
        String role,
        String deliver,
        String reason,
        String operator) {
}

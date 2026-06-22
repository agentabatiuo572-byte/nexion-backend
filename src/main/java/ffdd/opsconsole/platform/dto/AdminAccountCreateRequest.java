package ffdd.opsconsole.platform.dto;

public record AdminAccountCreateRequest(
        String displayName,
        String email,
        String role,
        String tier,
        String deliver,
        String reason,
        String operator) {
}

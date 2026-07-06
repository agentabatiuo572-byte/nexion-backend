package ffdd.opsconsole.platform.dto;

public record AdminAccountProfileUpdateRequest(
        String username,
        String displayName,
        String email,
        String reason,
        String operator) {
}

package ffdd.opsconsole.user.dto;

public record UserSessionRevokeAllRequest(
        String reason,
        String operator) {
}

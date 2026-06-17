package ffdd.opsconsole.user.dto;

public record UserSessionRevokeRequest(
        String reason,
        String operator) {
}

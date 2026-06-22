package ffdd.opsconsole.user.dto;

public record UserImpersonationTerminateRequest(
        String reason,
        String operator) {
}

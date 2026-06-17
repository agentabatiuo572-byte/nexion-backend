package ffdd.opsconsole.user.dto;

public record UserImpersonationRequest(
        Integer ttlMinutes,
        String reason,
        String operator) {
}

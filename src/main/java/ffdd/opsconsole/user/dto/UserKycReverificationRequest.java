package ffdd.opsconsole.user.dto;

public record UserKycReverificationRequest(
        String action,
        String reason,
        String operator) {
}

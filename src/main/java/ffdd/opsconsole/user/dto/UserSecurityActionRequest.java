package ffdd.opsconsole.user.dto;

public record UserSecurityActionRequest(
        String reason,
        String operator,
        String kycVerificationChannel,
        String kycVerificationTicket,
        String kycVerifiedAt,
        Boolean identityConfirmed,
        String lockKind) {

    public UserSecurityActionRequest(String reason, String operator) {
        this(reason, operator, null, null, null, false, null);
    }
}

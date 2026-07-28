package ffdd.opsconsole.auth.dto;

public record UserRegistrationOtpResponse(
        String challengeNo,
        int resendAfterSec,
        String deliveryHint) {
}

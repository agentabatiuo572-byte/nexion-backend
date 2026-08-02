package ffdd.opsconsole.auth.dto;

public record UserOtpLoginChallengeResponse(
        String challengeNo,
        int resendAfterSec,
        String deliveryHint) {
}

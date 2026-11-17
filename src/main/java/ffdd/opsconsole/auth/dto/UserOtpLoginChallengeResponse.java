package ffdd.opsconsole.auth.dto;

public record UserOtpLoginChallengeResponse(
        String challengeNo,
        int resendAfterSec,
        String deliveryHint,
        String lockedUntil) {
    public UserOtpLoginChallengeResponse(String challengeNo, int resendAfterSec, String deliveryHint) {
        this(challengeNo, resendAfterSec, deliveryHint, null);
    }
}

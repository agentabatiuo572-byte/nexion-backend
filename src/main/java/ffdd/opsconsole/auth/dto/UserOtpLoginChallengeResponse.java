package ffdd.opsconsole.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

public record UserOtpLoginChallengeResponse(
        String challengeNo,
        int resendAfterSec,
        String deliveryHint,
        String lockedUntil,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer expiresInSec) {
    public UserOtpLoginChallengeResponse(String challengeNo, int resendAfterSec, String deliveryHint) {
        this(challengeNo, resendAfterSec, deliveryHint, null, null);
    }

    public UserOtpLoginChallengeResponse(
            String challengeNo, int resendAfterSec, String deliveryHint, int expiresInSec) {
        this(challengeNo, resendAfterSec, deliveryHint, null, expiresInSec);
    }

    public UserOtpLoginChallengeResponse(
            String challengeNo, int resendAfterSec, String deliveryHint, String lockedUntil) {
        this(challengeNo, resendAfterSec, deliveryHint, lockedUntil, null);
    }
}

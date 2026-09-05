package ffdd.opsconsole.auth.captcha;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Safe production fallback until a vendor adapter is configured. It deliberately
 * does not interpret browser data as proof.
 */
@Component
final class HoldCaptchaTicketVerifier implements CaptchaTicketVerifier {
    @Override public boolean supports(Environment environment) { return true; }
    @Override public CaptchaTicketVerification verifyAndConsume(CaptchaScene scene, String ticket, String clientAddress) {
        return CaptchaTicketVerification.reject("USER_CAPTCHA_VERIFIER_UNAVAILABLE");
    }
}

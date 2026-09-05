package ffdd.opsconsole.auth.captcha;

import org.springframework.core.env.Environment;

/** Pluggable server authority; browser code is never a verifier. */
public interface CaptchaTicketVerifier {
    boolean supports(Environment environment);
    CaptchaTicketVerification verifyAndConsume(CaptchaScene scene, String ticket, String clientAddress);
}

package ffdd.opsconsole.auth.captcha;

import org.springframework.core.env.Environment;

/** Pluggable server authority; browser code is never a verifier. */
public interface CaptchaTicketVerifier {
    boolean supports(Environment environment);
    CaptchaTicketVerification verifyAndConsume(CaptchaScene scene, String ticket, String clientAddress);

    /** A deployed verifier must win over test-only fixtures when both are present. */
    default int priority() { return 0; }
}

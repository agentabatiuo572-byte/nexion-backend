package ffdd.opsconsole.auth.captcha;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Production Redis-backed authority. It always takes precedence over test fixtures. */
@Component
final class SelfHostedCaptchaTicketVerifier implements CaptchaTicketVerifier {
    private final SelfHostedCaptchaService captcha;

    SelfHostedCaptchaTicketVerifier(SelfHostedCaptchaService captcha) {
        this.captcha = captcha;
    }

    @Override public boolean supports(Environment environment) { return true; }
    @Override public int priority() { return 100; }

    @Override
    public CaptchaTicketVerification verifyAndConsume(CaptchaScene scene, String ticket, String clientAddress) {
        return captcha.consumeTicket(scene, ticket, clientAddress);
    }
}

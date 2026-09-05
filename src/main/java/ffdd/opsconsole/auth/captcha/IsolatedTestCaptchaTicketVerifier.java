package ffdd.opsconsole.auth.captcha;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Explicit dev/test-only fixture verifier. Production never registers this bean. */
@Component
@Profile({"dev", "test"})
final class IsolatedTestCaptchaTicketVerifier implements CaptchaTicketVerifier {
    private final Set<String> consumed = ConcurrentHashMap.newKeySet();

    @Override public boolean supports(Environment environment) { return true; }

    @Override public CaptchaTicketVerification verifyAndConsume(CaptchaScene scene, String ticket, String clientAddress) {
        if (ticket == null || !ticket.matches("test-captcha:" + scene.name().toLowerCase()
                + ":[0-9]{10}:[A-Za-z0-9_-]{16,128}")) {
            return CaptchaTicketVerification.reject("USER_CAPTCHA_TICKET_INVALID");
        }
        long expiresAt = Long.parseLong(ticket.split(":", 4)[2]);
        if (expiresAt <= System.currentTimeMillis() / 1000L) {
            return CaptchaTicketVerification.reject("USER_CAPTCHA_TICKET_EXPIRED");
        }
        return consumed.add(ticket)
                ? CaptchaTicketVerification.pass()
                : CaptchaTicketVerification.reject("USER_CAPTCHA_TICKET_REPLAYED");
    }
}

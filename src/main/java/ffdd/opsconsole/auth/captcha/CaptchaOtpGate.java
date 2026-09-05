package ffdd.opsconsole.auth.captcha;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.user.application.RegistrationRiskCaptchaWindow;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** Server-side CAPTCHA requirement and verification boundary for every public OTP send. */
@Service
@RequiredArgsConstructor
public class CaptchaOtpGate {
    private static final String ALWAYS_SCENES = "auth.risk.captcha_always_scenes";
    private static final String AFTER_SENDS = "auth.risk.captcha_after_sends";
    private final Environment environment;
    private final PlatformConfigFacade configs;
    private final List<CaptchaTicketVerifier> verifiers;
    private final Clock clock;

    public Decision checkAndConsume(CaptchaScene scene, String ticket, String clientAddress, int successfulSendsLast24h) {
        RegistrationRiskCaptchaWindow.State window = RegistrationRiskCaptchaWindow.state(
                configs.activeValue(RegistrationRiskCaptchaWindow.CONFIG_KEY).orElse(null), clock);
        if (window.disabled()) return Decision.granted();
        if (!required(scene, successfulSendsLast24h)) return Decision.granted();
        if (ticket == null || ticket.isBlank() || ticket.length() > 2048) return Decision.reject(428, "USER_CAPTCHA_REQUIRED");
        CaptchaTicketVerifier verifier = verifiers.stream().filter(value -> !(value instanceof HoldCaptchaTicketVerifier))
                .filter(value -> value.supports(environment)).findFirst()
                .orElseGet(() -> verifiers.stream().filter(value -> value.supports(environment)).findFirst().orElse(null));
        if (verifier == null) return Decision.reject(503, "USER_CAPTCHA_VERIFIER_UNAVAILABLE");
        CaptchaTicketVerification result = verifier.verifyAndConsume(scene, ticket.trim(), clientAddress);
        return result.passed() ? Decision.granted() : Decision.reject(
                "USER_CAPTCHA_VERIFIER_UNAVAILABLE".equals(result.code()) ? 503 : 428, result.code());
    }

    private boolean required(CaptchaScene scene, int successfulSendsLast24h) {
        String raw = configs.activeValue(ALWAYS_SCENES).orElse("register");
        Set<String> always = Arrays.stream(raw.split(",")).map(String::trim)
                .filter(value -> !value.isEmpty()).map(value -> value.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        if (!Set.of("REGISTER", "LOGIN", "RESET").containsAll(always)) return true;
        int after = integer(AFTER_SENDS, 2, 0, 50);
        if (after < 0) return true;
        return always.contains(scene.name()) || successfulSendsLast24h >= after;
    }

    private int integer(String key, int fallback, int min, int max) {
        String raw = configs.activeValue(key).orElse(null);
        if (raw == null) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException ignored) { }
        return -1;
    }

    public record Decision(boolean allowed, int status, String code) {
        static Decision granted() { return new Decision(true, 0, "OK"); }
        static Decision reject(int status, String code) { return new Decision(false, status, code); }
    }
}

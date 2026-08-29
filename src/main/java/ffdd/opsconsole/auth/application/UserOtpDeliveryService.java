package ffdd.opsconsole.auth.application;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** Delivers login OTPs through the configured trusted SMS/provider webhook. */
@Service
@RequiredArgsConstructor
public class UserOtpDeliveryService {
    private static final String LOCAL_FIXED_CODE = "123456";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${nexion.auth.user-otp.delivery-url:}")
    private final String deliveryUrl;
    @Value("${nexion.auth.user-otp.local-fixed-code-enabled:false}")
    private final boolean localFixedCodeEnabled;
    private final RestClient.Builder restClientBuilder;
    private final Environment environment;

    public boolean available() {
        return localFixedCodeAllowed() || StringUtils.hasText(deliveryUrl);
    }

    public String verificationCode() {
        if (localFixedCodeAllowed()) return LOCAL_FIXED_CODE;
        if (!StringUtils.hasText(deliveryUrl)) {
            throw new IllegalStateException("USER_OTP_DELIVERY_UNAVAILABLE");
        }
        return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    }

    public void deliver(String countryCode, String phone, String challengeNo, String code, int ttlMinutes) {
        if (localFixedCodeAllowed()) {
            if (!LOCAL_FIXED_CODE.equals(code)) throw new IllegalStateException("USER_OTP_LOCAL_CODE_INVALID");
            return;
        }
        if (!StringUtils.hasText(deliveryUrl)) throw new IllegalStateException("USER_OTP_DELIVERY_UNAVAILABLE");
        restClientBuilder.build().post()
                .uri(deliveryUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "countryCode", countryCode,
                        "phone", phone,
                        "challengeNo", challengeNo,
                        "code", code,
                        "ttlMinutes", ttlMinutes))
                .retrieve()
                .toBodilessEntity();
    }

    private boolean localFixedCodeAllowed() {
        if (!localFixedCodeEnabled || StringUtils.hasText(deliveryUrl)) return false;
        String[] profiles = environment.getActiveProfiles();
        return profiles.length == 1
                && ("dev".equals(profiles[0]) || "test".equals(profiles[0]));
    }
}

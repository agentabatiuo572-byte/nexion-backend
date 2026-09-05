package ffdd.opsconsole.auth.application;

import java.security.SecureRandom;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** Delivers user OTPs through ITNIO or the explicitly enabled local test code. */
@Service
@RequiredArgsConstructor
public class UserOtpDeliveryService {
    private static final String LOCAL_FIXED_CODE = "123456";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${nexion.auth.user-otp.local-fixed-code-enabled:false}")
    private final boolean localFixedCodeEnabled;
    private final Environment environment;
    private final ItnioSmsClient itnioSmsClient;

    /** Coarse capability check for settings that do not yet have the user's country code. */
    public boolean available() {
        return itnioSmsClient.available() || chinaFixedCodeRuntimeAllowed();
    }

    public boolean available(String countryCode) {
        if (!OtpPhoneCanonicalizer.isSupportedCountryCode(countryCode)) return false;
        if (isChinaCountryCode(countryCode)) return chinaFixedCodeAllowed(countryCode);
        return itnioSmsClient.enabled() && itnioSmsClient.available();
    }

    public String verificationCode(String countryCode) {
        if (!OtpPhoneCanonicalizer.isSupportedCountryCode(countryCode)) {
            throw new IllegalStateException("USER_OTP_DELIVERY_UNAVAILABLE");
        }
        if (isChinaCountryCode(countryCode)) {
            if (chinaFixedCodeAllowed(countryCode)) return LOCAL_FIXED_CODE;
            throw new IllegalStateException("USER_OTP_DELIVERY_UNAVAILABLE");
        }
        if (itnioSmsClient.enabled()) {
            if (!itnioSmsClient.available()) {
                throw new IllegalStateException("USER_OTP_DELIVERY_UNAVAILABLE");
            }
        } else {
            throw new IllegalStateException("USER_OTP_DELIVERY_UNAVAILABLE");
        }
        return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    }

    public void deliver(String countryCode, String phone, String challengeNo, String code, int ttlMinutes) {
        if (!OtpPhoneCanonicalizer.isSupportedCountryCode(countryCode)) {
            throw new IllegalStateException("USER_OTP_DELIVERY_UNAVAILABLE");
        }
        if (isChinaCountryCode(countryCode)) {
            if (chinaFixedCodeAllowed(countryCode)) {
                if (!LOCAL_FIXED_CODE.equals(code)) throw new IllegalStateException("USER_OTP_LOCAL_CODE_INVALID");
                return;
            }
            throw new IllegalStateException("USER_OTP_DELIVERY_UNAVAILABLE");
        }
        if (itnioSmsClient.enabled()) {
            if (!itnioSmsClient.available()) {
                throw new IllegalStateException("USER_OTP_DELIVERY_UNAVAILABLE");
            }
            itnioSmsClient.send(countryCode, phone, challengeNo, code, ttlMinutes);
            return;
        }
        throw new IllegalStateException("USER_OTP_DELIVERY_UNAVAILABLE");
    }

    private boolean chinaFixedCodeAllowed(String countryCode) {
        return isChinaCountryCode(countryCode) && chinaFixedCodeRuntimeAllowed();
    }

    private boolean isChinaCountryCode(String countryCode) {
        String normalized = countryCode == null ? "" : countryCode.trim().replace(" ", "");
        if (normalized.startsWith("+")) normalized = normalized.substring(1);
        return "86".equals(normalized);
    }

    private boolean chinaFixedCodeRuntimeAllowed() {
        if (!localFixedCodeEnabled) return false;
        String[] profiles = environment.getActiveProfiles();
        return profiles.length == 1
                && ("dev".equals(profiles[0]) || "test".equals(profiles[0]));
    }
}

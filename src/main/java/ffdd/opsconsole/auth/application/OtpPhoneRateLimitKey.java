package ffdd.opsconsole.auth.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Shared rolling quota key for every SMS OTP purpose in the same user environment. */
final class OtpPhoneRateLimitKey {
    private OtpPhoneRateLimitKey() {
    }

    static String from(String namespace, String countryCode, String phone) {
        String destination = OtpPhoneCanonicalizer.toE164Digits(countryCode, phone);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((namespace + ":otp-destination:" + destination).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}

package ffdd.opsconsole.auth.application;

import java.util.Locale;

final class NexGridOtpMessage {
    private static final String SECURITY_NOTICE =
            "Never share this code with anyone, including NexGrid Support.";

    private NexGridOtpMessage() {
    }

    static String render(String countryCode, String challengeNo, String code, int ttlMinutes) {
        if (code == null || !code.matches("\\d{6}") || ttlMinutes < 1 || ttlMinutes > 15) {
            throw new IllegalArgumentException("USER_OTP_MESSAGE_INPUT_INVALID");
        }
        String normalizedCountryCode = countryCode == null
                ? ""
                : countryCode.trim().replace(" ", "").replaceFirst("^\\+", "");
        if ("84".equals(normalizedCountryCode)) {
            return String.format(Locale.ROOT,
                    "Mã xác minh của bạn là %s, có hiệu lực trong %d phút. [NexGrid]",
                    code, ttlMinutes);
        }
        return String.format(Locale.ROOT,
                "NexGrid verification code: %s. Expires in %d min. %s",
                code, ttlMinutes, SECURITY_NOTICE);
    }
}

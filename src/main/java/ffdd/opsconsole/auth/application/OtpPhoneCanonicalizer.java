package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.shared.security.SupportedUserPhonePolicy;

/** Produces one provider and rate-limit identity for equivalent international phone inputs. */
final class OtpPhoneCanonicalizer {
    private OtpPhoneCanonicalizer() {
    }

    static String toE164Digits(String countryCode, String phone) {
        return SupportedUserPhonePolicy.toE164Digits(countryCode, phone);
    }

    static boolean isSupportedCountryCode(String countryCode) {
        return SupportedUserPhonePolicy.isSupportedCountryCode(countryCode);
    }
}

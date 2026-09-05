package ffdd.opsconsole.shared.security;

import java.util.Set;

/** One server-owned allowlist and national-number policy for NexGrid user identities. */
public final class SupportedUserPhonePolicy {
    private static final Set<String> SUPPORTED_COUNTRY_CODES = Set.of("84", "86");

    private SupportedUserPhonePolicy() {
    }

    public static boolean isSupportedCountryCode(String countryCode) {
        return SUPPORTED_COUNTRY_CODES.contains(normalizeCountryCode(countryCode));
    }

    public static boolean isSupportedDestination(String countryCode, String phone) {
        try {
            toE164Digits(countryCode, phone);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static String toE164Digits(String countryCode, String phone) {
        String country = normalizeCountryCode(countryCode);
        String subscriber = phone == null ? "" : phone.trim();
        if (!SUPPORTED_COUNTRY_CODES.contains(country) || !subscriber.matches("[0-9]{6,15}")) {
            throw invalidDestination();
        }
        subscriber = normalizeSupportedNationalNumber(country, subscriber);
        String destination = country + subscriber;
        if (!destination.matches("[1-9][0-9]{7,14}")) throw invalidDestination();
        return destination;
    }

    private static String normalizeCountryCode(String countryCode) {
        String country = countryCode == null ? "" : countryCode.trim().replace(" ", "");
        return country.startsWith("+") ? country.substring(1) : country;
    }

    private static String normalizeSupportedNationalNumber(String country, String subscriber) {
        String normalized = country.equals("84") && subscriber.startsWith("0")
                ? subscriber.substring(1) : subscriber;
        String pattern = switch (country) {
            case "84" -> "[35789][0-9]{8}";
            case "86" -> "1[3-9][0-9]{9}";
            default -> throw invalidDestination();
        };
        if (!normalized.matches(pattern)) throw invalidDestination();
        return normalized;
    }

    private static IllegalArgumentException invalidDestination() {
        return new IllegalArgumentException("OTP_PHONE_DESTINATION_INVALID");
    }
}

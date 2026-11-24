package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.math.BigDecimal;
import java.util.List;

record PayoutAddressProtection(int effectiveDelayHours, int changeCooldownDays) {
    static final String EFFECTIVE_DELAY_KEY = "withdrawal.address_effective_delay_hours";
    static final String CHANGE_COOLDOWN_KEY = "withdrawal.address_change_cooldown_days";
    static final String QUOTE_VERSION_KEY = "withdrawal.d5.quote_version";
    static final String EFFECTIVE_DELAY_FIELD = "addressEffectiveDelayHours";
    static final String CHANGE_COOLDOWN_FIELD = "addressChangeCooldownDays";
    static final String UNAVAILABLE = "PAYOUT_ADDRESS_PROTECTION_UNAVAILABLE";
    static final int MAX_DAYS = 365;
    static final int MAX_HOURS = 24 * MAX_DAYS;

    static boolean isField(String field) {
        return EFFECTIVE_DELAY_FIELD.equals(field) || CHANGE_COOLDOWN_FIELD.equals(field);
    }

    static PayoutAddressProtection read(PlatformConfigFacade config) {
        var values = config.activeValuesByKeys(List.of(EFFECTIVE_DELAY_KEY, CHANGE_COOLDOWN_KEY));
        int hours = parse(values.get(EFFECTIVE_DELAY_KEY), MAX_HOURS);
        int days = parse(values.get(CHANGE_COOLDOWN_KEY), MAX_DAYS);
        return hours > 0 && days > 0 ? new PayoutAddressProtection(hours, days) : null;
    }

    static boolean valid(BigDecimal value, int max) {
        return value != null && value.signum() > 0 && value.compareTo(BigDecimal.valueOf(max)) <= 0
                && value.stripTrailingZeros().scale() <= 0;
    }

    private static int parse(String raw, int max) {
        if (raw == null) return 0;
        try {
            var value = new BigDecimal(raw.trim());
            return valid(value, max) ? value.intValueExact() : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}

package ffdd.opsconsole.device.domain;

import java.math.BigDecimal;
import org.springframework.util.StringUtils;

/** Server-owned, nullable commerce display specifications for one SKU. */
public final class DeviceSkuSpecifications {
    private static final int MAX_DECIMAL_PRECISION = 18;
    private static final int MAX_DECIMAL_SCALE = 6;

    private DeviceSkuSpecifications() { }

    public static String validatePhoneDailyEarn(BigDecimal value) {
        if (value == null) return null;
        if (value.signum() < 0 || value.precision() > MAX_DECIMAL_PRECISION || value.scale() > MAX_DECIMAL_SCALE) {
            return "SKU_PHONE_DAILY_EARN_INVALID";
        }
        return null;
    }

    public static void requirePhoneDailyEarn(BigDecimal value) {
        String error = validatePhoneDailyEarn(value);
        if (error != null) throw new IllegalArgumentException(error);
    }

    public static String display(String value) {
        return StringUtils.hasText(value) ? value.trim() : "unavailable";
    }

    public static String dailyDisplay(BigDecimal value, String unit) {
        return value == null ? "unavailable" : value.stripTrailingZeros().toPlainString() + " " + unit;
    }
}

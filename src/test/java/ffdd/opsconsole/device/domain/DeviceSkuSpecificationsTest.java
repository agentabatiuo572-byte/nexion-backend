package ffdd.opsconsole.device.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DeviceSkuSpecificationsTest {
    @Test
    void acceptsZeroAndSixDecimalPhoneEarnings() {
        assertThat(DeviceSkuSpecifications.validatePhoneDailyEarn(new BigDecimal("0"))).isNull();
        assertThat(DeviceSkuSpecifications.validatePhoneDailyEarn(new BigDecimal("999999999999.999999"))).isNull();
    }

    @Test
    void rejectsNegativeOrTooPrecisePhoneEarnings() {
        assertThatThrownBy(() -> DeviceSkuSpecifications.requirePhoneDailyEarn(new BigDecimal("-0.01")))
                .hasMessage("SKU_PHONE_DAILY_EARN_INVALID");
        assertThatThrownBy(() -> DeviceSkuSpecifications.requirePhoneDailyEarn(new BigDecimal("1.1234567")))
                .hasMessage("SKU_PHONE_DAILY_EARN_INVALID");
    }

    @Test
    void emptyDisplaySpecsRemainUnavailable() {
        assertThat(DeviceSkuSpecifications.display(null)).isEqualTo("unavailable");
        assertThat(DeviceSkuSpecifications.display("  ")).isEqualTo("unavailable");
        assertThat(DeviceSkuSpecifications.display("99.9%")).isEqualTo("99.9%");
        assertThat(DeviceSkuSpecifications.dailyDisplay(new BigDecimal("0.060000"), "USDT/day"))
                .isEqualTo("0.06 USDT/day");
    }
}

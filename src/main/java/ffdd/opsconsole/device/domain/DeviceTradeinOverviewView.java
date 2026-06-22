package ffdd.opsconsole.device.domain;

import java.math.BigDecimal;
import java.util.List;

public record DeviceTradeinOverviewView(
        BigDecimal averageAgeMonths,
        Long cliffDeviceCount,
        Long tradeinMonthCount,
        BigDecimal tradeinDiscountUsdt,
        Long k2ArbitrageHits,
        List<DeviceTradeinTxView> txStats) {
}

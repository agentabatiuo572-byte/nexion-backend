package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

public record DeviceBundleDiscountUpdateRequest(
        BigDecimal twoItemsPct,
        BigDecimal threeItemsPct,
        BigDecimal fourPlusItemsPct,
        Long expectedVersion,
        String reason,
        String operator) {
}

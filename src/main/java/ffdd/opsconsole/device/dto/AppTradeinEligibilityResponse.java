package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;
import java.util.List;

public record AppTradeinEligibilityResponse(
        boolean enabled,
        boolean eligible,
        String decisionCode,
        Long targetProductId,
        String targetProductNo,
        String targetProductName,
        BigDecimal targetPriceUsdt,
        boolean requireHigherPrice,
        int maxDevicesPerOrder,
        List<Source> sources,
        String decisionSource) {

    public record Source(
            Long sourceDeviceId,
            String sourceProductName,
            boolean eligible,
            String reasonCode) {
    }
}

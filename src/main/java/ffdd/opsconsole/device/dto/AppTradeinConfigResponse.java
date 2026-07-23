package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;
import java.util.List;

public record AppTradeinConfigResponse(
        boolean enabled,
        String eligibility,
        List<BigDecimal> outputRatioCutsPct,
        List<BigDecimal> creditRatesPct,
        boolean requireHigherPrice,
        int maxDevicesPerOrder,
        String source) {
}

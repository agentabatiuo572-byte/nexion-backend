package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

public record DeviceGenerationGatePatchRequest(
        String name,
        Integer releaseMonth,
        String phase,
        BigDecimal discount,
        Boolean eligibility,
        Integer phaseOffset,
        Boolean forceUnlock,
        String status,
        String reason,
        String operator) {
}
